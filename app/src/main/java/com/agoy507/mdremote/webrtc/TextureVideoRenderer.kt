package com.agoy507.mdremote.webrtc

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.Matrix
import android.view.TextureView
import androidx.compose.ui.geometry.Offset
import com.agoy507.mdremote.ui.components.GlViewportCrop
import com.agoy507.mdremote.ui.components.RemoteViewportMath
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Custom OpenGL drawer that crops UV texture coordinates directly inside the fragment/vertex pass
 * onto the full-resolution screen viewport, preventing blurry digital upscaling during pinch-zoom.
 */
internal class ZoomableGlDrawer(
    private val delegate: RendererCommon.GlDrawer = GlRectDrawer(),
) : RendererCommon.GlDrawer {

    @Volatile var zoom: Float = 1f
    @Volatile var panX: Float = 0f
    @Volatile var panY: Float = 0f
    @Volatile var rotatedFrameWidth: Int = 0
    @Volatile var rotatedFrameHeight: Int = 0

    // Accessed only on EglRenderer's single GL render thread
    private val cropMatrix = FloatArray(16)
    private val combinedMatrix = FloatArray(16)

    override fun drawOes(
        oesTextureId: Int,
        texMatrix: FloatArray,
        frameWidth: Int,
        frameHeight: Int,
        viewportX: Int,
        viewportY: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ) {
        val crop = prepareDraw(texMatrix, frameWidth, frameHeight, viewportWidth, viewportHeight) ?: return
        delegate.drawOes(
            oesTextureId,
            combinedMatrix,
            frameWidth,
            frameHeight,
            crop.glX,
            crop.glY,
            crop.glWidth,
            crop.glHeight,
        )
    }

    override fun drawRgb(
        textureId: Int,
        texMatrix: FloatArray,
        frameWidth: Int,
        frameHeight: Int,
        viewportX: Int,
        viewportY: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ) {
        val crop = prepareDraw(texMatrix, frameWidth, frameHeight, viewportWidth, viewportHeight) ?: return
        delegate.drawRgb(
            textureId,
            combinedMatrix,
            frameWidth,
            frameHeight,
            crop.glX,
            crop.glY,
            crop.glWidth,
            crop.glHeight,
        )
    }

    override fun drawYuv(
        yuvTextures: IntArray,
        texMatrix: FloatArray,
        frameWidth: Int,
        frameHeight: Int,
        viewportX: Int,
        viewportY: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ) {
        val crop = prepareDraw(texMatrix, frameWidth, frameHeight, viewportWidth, viewportHeight) ?: return
        delegate.drawYuv(
            yuvTextures,
            combinedMatrix,
            frameWidth,
            frameHeight,
            crop.glX,
            crop.glY,
            crop.glWidth,
            crop.glHeight,
        )
    }

    override fun release() {
        delegate.release()
    }

    private fun prepareDraw(
        texMatrix: FloatArray,
        frameWidth: Int,
        frameHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ): GlViewportCrop? {
        val effectiveW = if (rotatedFrameWidth > 0) rotatedFrameWidth else frameWidth
        val effectiveH = if (rotatedFrameHeight > 0) rotatedFrameHeight else frameHeight
        val crop = RemoteViewportMath.computeGlCrop(
            viewportW = viewportWidth.toFloat(),
            viewportH = viewportHeight.toFloat(),
            frameW = effectiveW,
            frameH = effectiveH,
            zoom = zoom,
            pan = Offset(panX, panY),
        ) ?: return null

        // Map top-down video UVs [vTop..vBottom] to OpenGL bottom-up texture coordinates
        Matrix.setIdentityM(cropMatrix, 0)
        Matrix.translateM(cropMatrix, 0, crop.uLeft, 1f - crop.vBottom, 0f)
        Matrix.scaleM(
            cropMatrix,
            0,
            (crop.uRight - crop.uLeft).coerceAtLeast(1e-5f),
            (crop.vBottom - crop.vTop).coerceAtLeast(1e-5f),
            1f,
        )
        Matrix.multiplyMM(combinedMatrix, 0, texMatrix, 0, cropMatrix, 0)
        return crop
    }
}

/**
 * Custom WebRTC VideoSink backed by [TextureView] + [EglRenderer] with native-resolution
 * OpenGL zoom & pan rendering and cached last-frame redraws for crisp desktop text at 1x–5x.
 */
class TextureVideoRenderer(context: Context) : TextureView(context), VideoSink, TextureView.SurfaceTextureListener {

    private val zoomableDrawer = ZoomableGlDrawer()
    private val eglRenderer = EglRenderer("RemoteTextureRenderer")
    private val frameLock = Any()

    @Volatile
    private var initialized = false
    private var resolutionCallback: ((Int, Int) -> Unit)? = null
    private var lastFrameWidth = 0
    private var lastFrameHeight = 0
    private var cachedFrame: VideoFrame? = null

    private var appliedZoom = 1f
    private var appliedPanX = 0f
    private var appliedPanY = 0f

    fun init(
        sharedContext: EglBase.Context,
        onResolutionChanged: (width: Int, height: Int) -> Unit,
    ) {
        if (initialized) return
        resolutionCallback = onResolutionChanged
        eglRenderer.init(sharedContext, EglBase.CONFIG_PLAIN, zoomableDrawer)
        eglRenderer.setMirror(false)
        eglRenderer.setLayoutAspectRatio(0f)
        initialized = true
        isOpaque = true
        surfaceTextureListener = this

        if (isAvailable) {
            surfaceTexture?.let { texture ->
                eglRenderer.createEglSurface(texture)
                redrawCachedFrame()
            }
        }
    }

    fun updateViewportTransform(zoom: Float, pan: Offset) {
        if (abs(zoom - appliedZoom) < 1e-4f &&
            abs(pan.x - appliedPanX) < 1e-4f &&
            abs(pan.y - appliedPanY) < 1e-4f
        ) {
            return
        }
        appliedZoom = zoom
        appliedPanX = pan.x
        appliedPanY = pan.y

        zoomableDrawer.zoom = zoom
        zoomableDrawer.panX = pan.x
        zoomableDrawer.panY = pan.y
        redrawCachedFrame()
    }

    override fun onFrame(frame: VideoFrame) {
        if (!initialized) return
        val w = frame.rotatedWidth
        val h = frame.rotatedHeight
        if (w > 0 && h > 0) {
            zoomableDrawer.rotatedFrameWidth = w
            zoomableDrawer.rotatedFrameHeight = h
            if (w != lastFrameWidth || h != lastFrameHeight) {
                lastFrameWidth = w
                lastFrameHeight = h
                post { resolutionCallback?.invoke(w, h) }
            }
        }

        synchronized(frameLock) {
            if (!initialized) return
            frame.retain()
            cachedFrame?.release()
            cachedFrame = frame
        }
        eglRenderer.onFrame(frame)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (!initialized) return
        eglRenderer.createEglSurface(surface)
        eglRenderer.setLayoutAspectRatio(0f)
        redrawCachedFrame()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (!initialized) return
        eglRenderer.setLayoutAspectRatio(0f)
        redrawCachedFrame()
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
        synchronized(frameLock) {
            initialized = false
            cachedFrame?.release()
            cachedFrame = null
        }
        resolutionCallback = null
        surfaceTextureListener = null
        runCatching { eglRenderer.release() }
    }

    private fun redrawCachedFrame() {
        val frameToDraw = synchronized(frameLock) {
            val f = cachedFrame
            if (initialized && f != null) {
                f.retain()
                f
            } else {
                null
            }
        } ?: return

        try {
            eglRenderer.onFrame(frameToDraw)
        } finally {
            frameToDraw.release()
        }
    }
}
