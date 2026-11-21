package com.agoy507.mdremote.webrtc

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Custom WebRTC VideoSink backed by [TextureView] + [EglRenderer] so Compose
 * pinch-to-zoom, pan, and clipping transforms apply smoothly without SurfaceView
 * Z-order / hole-punch artifacts.
 */
class TextureVideoRenderer(context: Context) : TextureView(context), VideoSink, TextureView.SurfaceTextureListener {

    private val eglRenderer = EglRenderer("RemoteTextureRenderer")

    @Volatile
    private var initialized = false
    private var resolutionCallback: ((Int, Int) -> Unit)? = null
    private var lastFrameWidth = 0
    private var lastFrameHeight = 0

    fun init(
        sharedContext: EglBase.Context,
        onResolutionChanged: (width: Int, height: Int) -> Unit,
    ) {
        if (initialized) return
        resolutionCallback = onResolutionChanged
        eglRenderer.init(sharedContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        eglRenderer.setMirror(false)
        initialized = true
        isOpaque = true
        surfaceTextureListener = this

        if (isAvailable) {
            surfaceTexture?.let { texture ->
                eglRenderer.createEglSurface(texture)
                if (width > 0 && height > 0) {
                    eglRenderer.setLayoutAspectRatio(width.toFloat() / height.toFloat())
                }
            }
        }
    }

    override fun onFrame(frame: VideoFrame) {
        if (!initialized) return
        val w = frame.rotatedWidth
        val h = frame.rotatedHeight
        if (w > 0 && h > 0 && (w != lastFrameWidth || h != lastFrameHeight)) {
            lastFrameWidth = w
            lastFrameHeight = h
            post { resolutionCallback?.invoke(w, h) }
        }
        eglRenderer.onFrame(frame)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (!initialized) return
        eglRenderer.createEglSurface(surface)
        if (width > 0 && height > 0) {
            eglRenderer.setLayoutAspectRatio(width.toFloat() / height.toFloat())
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (!initialized) return
        if (width > 0 && height > 0) {
            eglRenderer.setLayoutAspectRatio(width.toFloat() / height.toFloat())
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (initialized) {
            val latch = CountDownLatch(1)
            eglRenderer.releaseEglSurface { latch.countDown() }
            runCatching { latch.await(500L, TimeUnit.MILLISECONDS) }
        }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    fun release() {
        if (!initialized) return
        initialized = false
        resolutionCallback = null
        surfaceTextureListener = null
        runCatching { eglRenderer.release() }
    }
}
