package com.lizongying.mytv0.playback

import java.net.URI
import java.util.Locale

/** Pure request validation, shared by both playback engines. No URL/header values are logged. */
data class NativePlaybackRequest(
    val url: String,
    val scheme: String,
    val sourceType: String,
    val headers: Map<String, String>,
    val vlcOptions: List<String>
) {
    val isHttp: Boolean get() = scheme == "http" || scheme == "https"

    companion object {
        fun parse(url: String?, headers: Map<String, String>?, sourceType: String, software: Boolean): NativePlaybackRequest {
            require(!url.isNullOrBlank()) { "此频道没有可播放的媒体地址。" }
            val uri = try { URI(url) } catch (_: Exception) {
                throw IllegalArgumentException("媒体地址格式无效。")
            }
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: ""
            val schemes = if (software) setOf("http", "https", "file", "rtsp", "rtmp", "rtp", "udp")
                else setOf("http", "https", "file", "rtsp", "rtmp")
            require(scheme in schemes) { "此播放器不支持该地址类型，请使用媒体直连地址。" }
            val http = scheme == "http" || scheme == "https"
            require(!http || !uri.host.isNullOrBlank()) { "媒体服务器地址无效。" }
            val path = (uri.path ?: "").lowercase(Locale.ROOT)
            val extension = path.substringAfterLast('/').substringAfterLast('.', "")
            require(!http || extension !in setOf("html", "htm", "shtml", "aspx", "jsp") && !path.endsWith("/")) {
                "这是网页地址；原生播放器需要媒体直连地址。请使用官网网页播放。"
            }
            val inferredType = when {
                extension == "m3u8" -> "HLS"
                extension == "mpd" -> "DASH"
                scheme == "rtsp" -> "RTSP"
                scheme == "rtmp" -> "RTMP"
                scheme == "rtp" || scheme == "udp" -> "RTP"
                scheme == "file" || extension in setOf("mp4", "m4v", "mkv", "ts", "m2ts", "webm", "flv", "mov", "avi") -> "PROGRESSIVE"
                sourceType in setOf("HLS", "DASH", "PROGRESSIVE") -> sourceType
                else -> "UNKNOWN"
            }
            require(!software || !http || inferredType != "UNKNOWN") {
                "软件解码需要媒体直连地址（如 m3u8、mpd、mp4）。网页请用官网网页播放；无后缀的媒体源需在配置中声明格式。"
            }
            val safeHeaders = linkedMapOf<String, String>()
            headers.orEmpty().forEach { (key, value) ->
                require(key.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && value.none { it == '\r' || it == '\n' || it == '\u0000' }) {
                    "直播源的请求头格式无效。"
                }
                val normalized = key.lowercase(Locale.ROOT)
                require(safeHeaders.keys.none { it.equals(key, ignoreCase = true) }) { "直播源包含重复请求头。" }
                if (software) {
                    require(http && normalized in setOf("user-agent", "referer")) {
                        "软件播放器无法完整传递此源的请求头。请切换自动播放器，或使用不依赖这些请求头的媒体源。"
                    }
                } else if (!http) {
                    require(scheme == "rtsp" && normalized == "user-agent") {
                        "此协议无法完整传递直播源要求的请求头，请更换媒体源。"
                    }
                }
                safeHeaders[key] = value
            }
            val options = if (software) safeHeaders.map { (key, value) ->
                if (key.equals("user-agent", ignoreCase = true)) ":http-user-agent=$value" else ":http-referrer=$value"
            } else emptyList()
            return NativePlaybackRequest(url, scheme, inferredType, safeHeaders, options)
        }
    }
}
