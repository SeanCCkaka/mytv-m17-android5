package com.lizongying.mytv0.playback

import android.graphics.SurfaceTexture
import android.view.TextureView

/** Observe texture delivery without stealing ownership from LibVLC's surface listener. */
class TextureFrameObserver private constructor(
    private val view: TextureView,
    private val delegate: TextureView.SurfaceTextureListener,
    private val onUpdate: () -> Unit
) : TextureView.SurfaceTextureListener {
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) =
        delegate.onSurfaceTextureAvailable(surface, width, height)

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) =
        delegate.onSurfaceTextureSizeChanged(surface, width, height)

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean =
        delegate.onSurfaceTextureDestroyed(surface)

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        delegate.onSurfaceTextureUpdated(surface)
        onUpdate()
    }

    fun detach() {
        if (view.surfaceTextureListener === this) view.surfaceTextureListener = delegate
    }

    companion object {
        /** Call after IVLCVout.attachViews() installed its listener. */
        fun attach(view: TextureView, onUpdate: () -> Unit): TextureFrameObserver {
            val delegate = checkNotNull(view.surfaceTextureListener) { "Video surface listener missing" }
            return TextureFrameObserver(view, delegate, onUpdate).also { view.surfaceTextureListener = it }
        }
    }
}
