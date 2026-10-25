package com.lizongying.mytv0

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lizongying.mytv0.databinding.PlayerBinding
import com.lizongying.mytv0.models.TVModel
import com.lizongying.mytv0.playback.CztvOfficialResolver
import com.lizongying.mytv0.playback.CztvWebSource
import com.lizongying.mytv0.playback.NativePlaybackRequest
import com.lizongying.mytv0.playback.SourceAttemptPlan
import com.lizongying.mytv0.playback.TextureFrameObserver
import com.lizongying.mytv0.playback.VideoFrameEvidence
import com.lizongying.mytv0.requests.HttpClient
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer as VlcPlayer

/** A fresh, bounded playback session is created for every channel/mode change. */
@OptIn(UnstableApi::class)
class PlayerFragment : Fragment() {
    private var _binding: PlayerBinding? = null
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var libVlc: LibVLC? = null
    private var vlcPlayer: VlcPlayer? = null
    private var vlcMedia: Media? = null
    private var textureObserver: TextureFrameObserver? = null
    private var sizeListener: View.OnLayoutChangeListener? = null
    private var tvModel: TVModel? = null
    private var generation = 0L
    private var resumePlayback = false
    private var firstVideoFrame = false
    private var playingSignal = false
    private var frameEvidence = VideoFrameEvidence()
    private var firstFrameTimeout: Runnable? = null
    private var evidencePoll: Runnable? = null
    private var sourceAttempts: SourceAttemptPlan? = null
    private var pendingSourceSwitch: Runnable? = null
    private var softwareDecodeForRequest = true
    private var officialResolution: CztvOfficialResolver.Resolution? = null
    private var officialResolutionTimeout: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = PlayerBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? MainActivity)?.ready(TAG)
    }

    /** Public API used by the settings screen; also works after a failed attempt. */
    fun updatePlayer() {
        tvModel?.let { play(it) }
    }

    fun play(tvModel: TVModel) {
        releaseSession()
        this.tvModel = tvModel
        resumePlayback = true
        sourceAttempts = null
        if (_binding != null && isResumed) beginPlaybackRequest(tvModel)
    }

    /** Stop an isolated test without accidentally resuming the previous channel. */
    fun stopPlayback() {
        resumePlayback = false
        tvModel = null
        releaseSession()
        sourceAttempts = null
        _binding?.playbackStatus?.visibility = View.GONE
    }

    private fun beginPlaybackRequest(tv: TVModel) {
        // Settings changes explicitly call play(); a source fallback never changes engines.
        softwareDecodeForRequest = SP.softDecode
        sourceAttempts = SourceAttemptPlan(
            tv.tv.uris, tv.videoIndexValue,
            if (isCompatibilitySample(tv)) 1 else MAX_SOURCE_ATTEMPTS
        )
        startPlayback(tv)
    }

    private fun startPlayback(tv: TVModel) {
        val binding = _binding ?: return
        val plan = sourceAttempts ?: return
        val session = ++generation
        val attempt = plan.next()
        firstVideoFrame = false
        playingSignal = false
        frameEvidence = VideoFrameEvidence()
        binding.playerView.visibility = if (softwareDecodeForRequest) View.GONE else View.VISIBLE
        binding.vlcTexture.visibility = if (softwareDecodeForRequest) View.VISIBLE else View.GONE
        binding.playbackStatus.text = if (plan.attemptedCount > 1) {
            "正在尝试备用源（${plan.attemptedCount}/${plan.totalAttempts}）…"
        } else if (softwareDecodeForRequest) "正在等待软件解码的视频画面…" else "正在等待视频画面…"
        binding.playbackStatus.visibility = View.VISIBLE
        if (attempt == null) {
            fail(tv, session, "此频道没有可播放的媒体地址。")
            return
        }
        try {
            require(selectVideoIndex(tv, attempt.index) && tv.getVideoUrl() == attempt.url) {
                "频道地址已更新，请重新选择频道。"
            }
            if (CztvWebSource.recognizes(attempt.url)) {
                CztvWebSource.validateEntryHeaders(tv.tv.headers)
                resolveOfficialZhejiang(tv, session)
                return
            }
            val request = NativePlaybackRequest.parse(
                attempt.url, tv.tv.headers, tv.tv.sourceType.name, softwareDecodeForRequest
            )
            startMediaPlayback(tv, session, request)
        } catch (error: IllegalArgumentException) {
            fail(tv, session, error.message ?: "播放地址或设置无效。")
        } catch (error: Exception) {
            Log.e(TAG, "session=$session source setup failed (${error.javaClass.simpleName})")
            fail(tv, session, "播放器启动失败，请切换播放器或频道。")
        }
    }

    private fun resolveOfficialZhejiang(tv: TVModel, session: Long) {
        _binding?.playbackStatus?.text = "正在连接浙江卫视官方源…"
        Log.i(TAG, "session=$session resolving official Zhejiang web entry")
        // At most 15 s to refresh public website data, then the normal 10 s first-frame budget.
        officialResolutionTimeout = Runnable {
            if (isCurrent(tv, session)) fail(tv, session, "连接浙江卫视官方源超时，请稍后重试。")
        }.also { handler.postDelayed(it, OFFICIAL_RESOLVE_TIMEOUT_MS) }
        officialResolution = CztvOfficialResolver(HttpClient.okHttpClient).resolve { result ->
            handler.post {
                if (!isCurrent(tv, session)) return@post
                officialResolutionTimeout?.let(handler::removeCallbacks)
                officialResolutionTimeout = null
                officialResolution = null
                result.fold(onSuccess = { url ->
                    try {
                        // Keep only the stable web entry in TVModel/configuration. The expiring
                        // URL and public playback headers belong to this in-memory attempt.
                        val request = NativePlaybackRequest.parse(url, CztvWebSource.playbackHeaders, "HLS", softwareDecodeForRequest)
                        _binding?.playbackStatus?.text = "已连接官方源，正在等待视频画面…"
                        startMediaPlayback(tv, session, request, privateUrl = true)
                    } catch (_: Exception) {
                        fail(tv, session, "浙江卫视官方视频格式已变化，请稍后重试。")
                    }
                }, onFailure = {
                    Log.w(TAG, "session=$session official Zhejiang resolution failed")
                    fail(tv, session, "暂时无法取得浙江卫视官方视频，请稍后重试。")
                })
            }
        }
    }

    private fun startMediaPlayback(tv: TVModel, session: Long, request: NativePlaybackRequest, privateUrl: Boolean = false) {
        if (!isCurrent(tv, session)) return
        try {
            val plan = sourceAttempts ?: return
            // Never log the URL or header values: either may contain credentials.
            Log.i(TAG, "session=$session engine=${if (softwareDecodeForRequest) "VLC-software" else "Exo-auto"} scheme=${request.scheme} sourceAttempt=${plan.attemptedCount}/${plan.totalAttempts}")
            if (softwareDecodeForRequest) startVlc(tv, session, request, privateUrl) else startExo(tv, session, request)
            if (!isCurrent(tv, session)) return
            firstFrameTimeout = Runnable {
                if (!isCurrent(tv, session) || firstVideoFrame) return@Runnable
                val message = if (playingSignal) {
                    "播放器已启动，但没有收到可显示的视频帧。请切换播放器或频道；有声音不代表视频正常。"
                } else {
                    "等待视频画面超时。请检查网络和媒体地址，或切换播放器。"
                }
                fail(tv, session, message)
            }.also { handler.postDelayed(it, FIRST_FRAME_TIMEOUT_MS) }
        } catch (error: Exception) {
            Log.e(TAG, "session=$session playback setup failed (${error.javaClass.simpleName})")
            fail(tv, session, "播放器启动失败，请切换播放器或频道。")
        } catch (error: LinkageError) {
            val engine = if (softwareDecodeForRequest) "VLC-software" else "Exo-auto"
            Log.e(TAG, "session=$session engine=$engine initialization linkage error", error)
            fail(tv, session, if (softwareDecodeForRequest) {
                "此设备无法加载软件解码库，请切换播放器。"
            } else {
                "自动播放器组件与此设备不兼容，请切换软件解码。"
            })
        }
    }

    private fun selectVideoIndex(tv: TVModel, index: Int): Boolean {
        if (index !in tv.tv.uris.indices) return false
        // nextVideo() wraps; its Boolean means "now at last index", not "all tried".
        // Never call setReady() here: that would publish another play request/reset this plan.
        repeat(tv.tv.uris.size) {
            if (tv.videoIndexValue == index) return true
            tv.nextVideo()
        }
        return tv.videoIndexValue == index
    }

    private fun startVlc(tv: TVModel, session: Long, request: NativePlaybackRequest, privateUrl: Boolean) {
        val binding = _binding ?: return
        val options = arrayListOf("--stats", "--no-video-title-show")
        if (privateUrl) options.add("--quiet") // Native URL diagnostics must not expose expiring tokens.
        val engine = LibVLC(requireContext().applicationContext, options)
        libVlc = engine
        val output = VlcPlayer(engine)
        vlcPlayer = output
        val media = Media(engine, Uri.parse(request.url))
        vlcMedia = media
        // This disables video hardware decoding. FFmpeg audio renderers alone do not do this.
        media.setHWDecoderEnabled(false, false)
        request.vlcOptions.forEach(media::addOption)
        media.addOption(":network-caching=1500")
        output.setEventListener { event ->
            if (!isCurrent(tv, session)) return@setEventListener
            when (event.type) {
                VlcPlayer.Event.Playing -> {
                    playingSignal = true
                    Log.i(TAG, "session=$session VLC Playing (not video confirmation)")
                }
                VlcPlayer.Event.Vout -> {
                    frameEvidence.videoOutputs = event.voutCount
                    Log.i(TAG, "session=$session VLC Vout=${event.voutCount} (not first frame)")
                }
                VlcPlayer.Event.EncounteredError -> fail(tv, session, "软件播放器无法播放此媒体。请检查直播源，或切换播放器。")
                VlcPlayer.Event.EndReached -> playbackEnded(tv, session)
            }
        }
        val vout = output.vlcVout
        vout.setVideoView(binding.vlcTexture)
        // No layout listener selects VLC's OpenGL output. It fits the video's real sample
        // aspect ratio into this full-window TextureView; decoding remains software-only.
        vout.attachViews()
        output.setScale(0f)
        output.setAspectRatio(null)
        fun resize() {
            if (!isCurrent(tv, session)) return
            val width = binding.vlcTexture.width
            val height = binding.vlcTexture.height
            if (width > 0 && height > 0) vout.setWindowSize(width, height)
        }
        sizeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resize() }
            .also { binding.vlcTexture.addOnLayoutChangeListener(it) }
        resize()
        textureObserver = TextureFrameObserver.attach(binding.vlcTexture) {
            if (isCurrent(tv, session)) frameEvidence.textureUpdates++
        }
        output.media = media
        output.play()
        evidencePoll = object : Runnable {
            override fun run() {
                if (!isCurrent(tv, session) || firstVideoFrame) return
                val stats = media.stats
                frameEvidence.decodedVideo = stats?.decodedVideo ?: 0
                frameEvidence.displayedPictures = stats?.displayedPictures ?: 0
                if (frameEvidence.hasVideoFrames()) {
                    Log.i(TAG, "session=$session VLC video frames: texture=${frameEvidence.textureUpdates}, decoded=${frameEvidence.decodedVideo}, displayed=${frameEvidence.displayedPictures}")
                    // This verifies delivery, not the visual contents of a black TV screen.
                    videoReady(tv, session)
                } else handler.postDelayed(this, 250L)
            }
        }.also { handler.postDelayed(it, 250L) }
    }

    private fun startExo(tv: TVModel, session: Long, request: NativePlaybackRequest) {
        val context = requireContext()
        val renderers = DefaultRenderersFactory(context)
            .setMediaCodecSelector(PlayerMediaCodecSelector())
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val builder = ExoPlayer.Builder(context).setRenderersFactory(renderers)
        if (request.isHttp) {
            // Preserve every HTTP header in the automatic/hardware comparison path.
            val dataSource = OkHttpDataSource.Factory(HttpClient.okHttpClient)
                .setDefaultRequestProperties(request.headers)
            builder.setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
        }
        val output = builder.build()
        player = output
        output.repeatMode = Player.REPEAT_MODE_OFF
        output.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (isCurrent(tv, session)) {
                    Log.i(TAG, "session=$session Exo onRenderedFirstFrame")
                    videoReady(tv, session)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isCurrent(tv, session) && isPlaying) playingSignal = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) playbackEnded(tv, session)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "session=$session Exo error=${error.errorCode}")
                fail(tv, session, "自动播放器无法播放此媒体（${error.errorCode}）。请切换软件解码或频道。")
            }
        })
        _binding?.playerView?.player = output
        if (request.scheme == "rtsp" || request.scheme == "rtmp") {
            // These existing sources support only the headers validated by the parser.
            val item = tv.getMediaItem() ?: throw IllegalArgumentException("直播地址无效。")
            val source = tv.getMediaSource()
            if (source != null) output.setMediaSource(source) else output.setMediaItem(item)
        } else {
            // In particular, file: samples must use Exo's default local data source,
            // never the TVModel's HTTP-only HLS source.
            val item = MediaItem.Builder().setUri(request.url)
            when (request.sourceType) {
                "HLS" -> item.setMimeType(MimeTypes.APPLICATION_M3U8)
                "DASH" -> item.setMimeType(MimeTypes.APPLICATION_MPD)
            }
            output.setMediaItem(item.build())
        }
        output.prepare()
        output.play()
    }

    private fun isCurrent(tv: TVModel, session: Long) =
        _binding != null && generation == session && tvModel === tv

    private fun videoReady(tv: TVModel, session: Long) {
        if (!isCurrent(tv, session) || firstVideoFrame) return
        firstVideoFrame = true
        firstFrameTimeout?.let(handler::removeCallbacks)
        firstFrameTimeout = null
        _binding?.playbackStatus?.visibility = View.GONE
        tv.confirmVideoIndex()
        tv.retryTimes = 0
        tv.setErrInfo("")
    }

    private fun playbackEnded(tv: TVModel, session: Long) {
        if (!isCurrent(tv, session)) return
        if (firstVideoFrame && isCompatibilitySample(tv)) {
            // The finite, bundled diagnostic clip is deliberately not looped.
            resumePlayback = false
            _binding?.playbackStatus?.text = "自检短片已播放结束。请确认刚才是否同时看到画面并听到声音。"
            _binding?.playbackStatus?.visibility = View.VISIBLE
            releaseSession()
            sourceAttempts = null
        } else fail(tv, session, "媒体已结束，或直播源已中断。请重新选择频道。")
    }

    private fun isCompatibilitySample(tv: TVModel) =
        tv.tv.id == -1 && tv.tv.name == "compatibility-sample"

    private fun fail(tv: TVModel, session: Long, message: String) {
        if (!isCurrent(tv, session)) return
        val plan = sourceAttempts
        releaseSession()
        if (plan != null && plan.hasNext && resumePlayback && isResumed) {
            Log.w(TAG, "session=$session source failed; switching to ${plan.attemptedCount + 1}/${plan.totalAttempts}")
            _binding?.playbackStatus?.text = "当前地址未能播放，正在切换备用源（${plan.attemptedCount + 1}/${plan.totalAttempts}）…"
            _binding?.playbackStatus?.visibility = View.VISIBLE
            val releasedGeneration = generation
            // Queue a separate attempt, never recursively re-enter native setup/error callbacks.
            pendingSourceSwitch = Runnable {
                pendingSourceSwitch = null
                if (isCurrent(tv, releasedGeneration) && sourceAttempts === plan &&
                    resumePlayback && isResumed) startPlayback(tv)
            }.also { handler.postDelayed(it, SOURCE_SWITCH_DELAY_MS) }
            return
        }
        Log.w(TAG, "session=$session stopped with visible error after ${plan?.attemptedCount ?: 0} source attempts")
        resumePlayback = false
        sourceAttempts = null
        val finalMessage = if (plan != null && plan.attemptedCount > 1) {
            val limit = if (plan.isLimited) "（已达到本轮尝试上限）" else ""
            "已尝试 ${plan.attemptedCount} 个地址，均未能播放$limit。$message"
        } else message
        _binding?.playbackStatus?.text = finalMessage
        _binding?.playbackStatus?.visibility = View.VISIBLE
        // Only final failure hides the player; intermediate source failures keep it visible.
        tv.setErrInfo(finalMessage)
    }

    private fun releaseSession() {
        ++generation // Invalidate queued native/Exo callbacks before stopping either engine.
        officialResolutionTimeout?.let(handler::removeCallbacks)
        officialResolutionTimeout = null
        officialResolution?.cancel()
        officialResolution = null
        pendingSourceSwitch?.let(handler::removeCallbacks)
        pendingSourceSwitch = null
        firstFrameTimeout?.let(handler::removeCallbacks)
        firstFrameTimeout = null
        evidencePoll?.let(handler::removeCallbacks)
        evidencePoll = null
        sizeListener?.let { _binding?.vlcTexture?.removeOnLayoutChangeListener(it) }
        sizeListener = null
        textureObserver?.detach()
        textureObserver = null
        _binding?.playerView?.player = null
        player?.release()
        player = null
        vlcPlayer?.let {
            it.setEventListener(null)
            it.stop()
            it.vlcVout.detachViews()
            it.release()
        }
        vlcPlayer = null
        vlcMedia?.release()
        vlcMedia = null
        libVlc?.release()
        libVlc = null
    }

    class PlayerMediaCodecSelector : MediaCodecSelector {
        override fun getDecoderInfos(mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean): MutableList<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
            val infos = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            if (mimeType == MimeTypes.VIDEO_H265 && !requiresSecureDecoder && !requiresTunnelingDecoder) {
                infos.find { it.name == "c2.android.hevc.decoder" }?.let { return mutableListOf(it) }
            }
            return infos
        }
    }

    fun showVolume(visibility: Int) {
        _binding?.icon?.visibility = visibility
        _binding?.volume?.visibility = visibility
        hideVolume()
    }

    fun setVolumeMax(volume: Int) { _binding?.volume?.max = volume }

    fun setVolume(progress: Int, volume: Boolean = false) {
        val binding = _binding ?: return
        val context = context ?: return
        binding.volume.progress = progress
        binding.icon.setImageDrawable(ContextCompat.getDrawable(context,
            if (volume) {
                if (progress > 0) R.drawable.volume_up_24px else R.drawable.volume_off_24px
            } else R.drawable.light_mode_24px))
    }

    fun hideVolume() {
        handler.removeCallbacks(hideVolumeRunnable)
        if (_binding != null) handler.postDelayed(hideVolumeRunnable, 2_000L)
    }

    fun hideVolumeNow() {
        handler.removeCallbacks(hideVolumeRunnable)
        hideVolumeRunnable.run()
    }

    private val hideVolumeRunnable = Runnable {
        _binding?.icon?.visibility = View.GONE
        _binding?.volume?.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (resumePlayback && player == null && vlcPlayer == null) tvModel?.let(::beginPlaybackRequest)
    }

    override fun onPause() {
        releaseSession()
        sourceAttempts = null
        super.onPause()
    }

    override fun onDestroyView() {
        releaseSession()
        sourceAttempts = null
        handler.removeCallbacks(hideVolumeRunnable)
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "PlayerFragment"
        private const val FIRST_FRAME_TIMEOUT_MS = 10_000L
        private const val MAX_SOURCE_ATTEMPTS = 5
        private const val SOURCE_SWITCH_DELAY_MS = 250L
        private const val OFFICIAL_RESOLVE_TIMEOUT_MS = 15_000L
    }
}
