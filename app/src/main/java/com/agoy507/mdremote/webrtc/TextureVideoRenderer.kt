package com.agoy507.mdremote.webrtc

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Custom OpenGL drawer that crops UV texture coordinates directly onto the full-resolution
 * screen viewport and applies a high-precision (highp) 9-tap Contrast-Adaptive Sharpening (CAS)
 * + isotropic edge-reconstruction YUV shader so zoomed desktop text stays razor-sharp at 1x–5x.
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

    private var yuvProgram: Int = 0
    private var inPosLocation: Int = -1
    private var inTcLocation: Int = -1
    private var texMatrixLocation: Int = -1
    private var texelSizeLocation: Int = -1
    private var zoomLocation: Int = -1
    private var customShaderFailed: Boolean = false

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
        val effectiveW = (if (rotatedFrameWidth > 0) rotatedFrameWidth else frameWidth).coerceAtLeast(1)
        val effectiveH = (if (rotatedFrameHeight > 0) rotatedFrameHeight else frameHeight).coerceAtLeast(1)

        if (!customShaderFailed && ensureYuvCasProgram()) {
            GLES20.glUseProgram(yuvProgram)

            for (i in 0..2) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuvTextures[i])
            }

            GLES20.glUniformMatrix4fv(texMatrixLocation, 1, false, combinedMatrix, 0)
            GLES20.glUniform2f(texelSizeLocation, 1f / effectiveW.toFloat(), 1f / effectiveH.toFloat())
            GLES20.glUniform1f(zoomLocation, zoom.coerceIn(1f, 5f))

            GLES20.glEnableVertexAttribArray(inPosLocation)
            GLES20.glVertexAttribPointer(inPosLocation, 2, GLES20.GL_FLOAT, false, 0, FULL_RECT_VERTEX_BUF)
            GLES20.glEnableVertexAttribArray(inTcLocation)
            GLES20.glVertexAttribPointer(inTcLocation, 2, GLES20.GL_FLOAT, false, 0, FULL_RECT_TEX_BUF)

            GLES20.glViewport(crop.glX, crop.glY, crop.glWidth, crop.glHeight)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            for (i in 0..2) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            }
            return
        }

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
        if (yuvProgram != 0) {
            runCatching { GLES20.glDeleteProgram(yuvProgram) }
            yuvProgram = 0
        }
        delegate.release()
    }

    private fun ensureYuvCasProgram(): Boolean {
        if (yuvProgram != 0) return true
        return try {
            val vert = compileShader(GLES20.GL_VERTEX_SHADER, YUV_VERTEX_SHADER)
            val frag = compileShader(GLES20.GL_FRAGMENT_SHADER, YUV_CAS_FRAGMENT_SHADER)
            if (vert == 0 || frag == 0) {
                if (vert != 0) GLES20.glDeleteShader(vert)
                if (frag != 0) GLES20.glDeleteShader(frag)
                customShaderFailed = true
                return false
            }

            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vert)
            GLES20.glAttachShader(prog, frag)
            GLES20.glLinkProgram(prog)
            GLES20.glDeleteShader(vert)
            GLES20.glDeleteShader(frag)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                GLES20.glDeleteProgram(prog)
                customShaderFailed = true
                return false
            }

            GLES20.glUseProgram(prog)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "y_tex"), 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "u_tex"), 1)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "v_tex"), 2)

            inPosLocation = GLES20.glGetAttribLocation(prog, "in_pos")
            inTcLocation = GLES20.glGetAttribLocation(prog, "in_tc")
            texMatrixLocation = GLES20.glGetUniformLocation(prog, "texMatrix")
            texelSizeLocation = GLES20.glGetUniformLocation(prog, "u_texelSize")
            zoomLocation = GLES20.glGetUniformLocation(prog, "u_zoom")

            yuvProgram = prog
            true
        } catch (_: Throwable) {
            customShaderFailed = true
            false
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
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

    companion object {
        private val FULL_RECT_VERTEX_BUF: FloatBuffer = createFloatBuffer(
            floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f,
            ),
        )

        private val FULL_RECT_TEX_BUF: FloatBuffer = createFloatBuffer(
            floatArrayOf(
                0f, 0f,
                1f, 0f,
                0f, 1f,
                1f, 1f,
            ),
        )

        private fun createFloatBuffer(coords: FloatArray): FloatBuffer {
            val bb = ByteBuffer.allocateDirect(coords.size * 4)
            bb.order(ByteOrder.nativeOrder())
            val fb = bb.asFloatBuffer()
            fb.put(coords)
            fb.position(0)
            return fb
        }

        private const val YUV_VERTEX_SHADER = """
            precision highp float;
            varying highp vec2 tc;
            attribute vec4 in_pos;
            attribute vec4 in_tc;
            uniform mat4 texMatrix;
            void main() {
                gl_Position = in_pos;
                tc = (texMatrix * in_tc).xy;
            }
        """

        private const val YUV_CAS_FRAGMENT_SHADER = """
            precision highp float;
            varying highp vec2 tc;
            uniform sampler2D y_tex;
            uniform sampler2D u_tex;
            uniform sampler2D v_tex;
            uniform vec2 u_texelSize;
            uniform float u_zoom;

            float sampleY(vec2 uv) {
                return texture2D(y_tex, clamp(uv, vec2(0.0), vec2(1.0))).r;
            }

            void main() {
                vec2 d = u_texelSize * 0.62;
                float c  = sampleY(tc);
                float n  = sampleY(tc + vec2(0.0, -d.y));
                float s  = sampleY(tc + vec2(0.0,  d.y));
                float w  = sampleY(tc + vec2(-d.x, 0.0));
                float e  = sampleY(tc + vec2( d.x, 0.0));
                float nw = sampleY(tc + vec2(-d.x, -d.y));
                float ne = sampleY(tc + vec2( d.x, -d.y));
                float sw = sampleY(tc + vec2(-d.x,  d.y));
                float se = sampleY(tc + vec2( d.x,  d.y));

                float mnCross = min(min(min(n, s), min(w, e)), c);
                float mnDiag  = min(min(nw, ne), min(sw, se));
                float mn      = min(mnCross, mnDiag);

                float mxCross = max(max(max(n, s), max(w, e)), c);
                float mxDiag  = max(max(nw, ne), max(sw, se));
                float mx      = max(mxCross, mxDiag);

                float span = mx - mn;

                // 9-tap isotropic neighborhood average (cross weighted 2x, diagonals 1x)
                float avg = (2.0 * (n + s + w + e) + (nw + ne + sw + se)) / 12.0;

                // Contrast-Adaptive Sharpening (CAS) with strict [mn, mx] anti-ringing clamp
                float zoomFactor = clamp((u_zoom - 1.0) / 3.0, 0.0, 1.0);
                float edgeMask   = smoothstep(0.02, 0.10, span);
                float casStrength = mix(0.45, 0.95, zoomFactor) * edgeMask;
                float yCas = clamp(c + (c - avg) * casStrength, mn, mx);

                // Isotropic value-space S-curve edge reconstruction during zoom
                float t = clamp((yCas - mn) / max(span, 1e-4), 0.0, 1.0);
                float edgeLow = mix(0.06, 0.24, zoomFactor);
                float sCurve = smoothstep(edgeLow, 1.0 - edgeLow, t);
                float yEdge = mix(mn, mx, sCurve);
                float yFinal = mix(yCas, yEdge, zoomFactor * edgeMask * 0.78);

                float y = yFinal * 1.16438;
                float u = texture2D(u_tex, tc).r;
                float v = texture2D(v_tex, tc).r;

                gl_FragColor = vec4(
                    y + 1.59603 * v - 0.874202,
                    y - 0.391762 * u - 0.812968 * v + 0.531668,
                    y + 2.01723 * u - 1.08563,
                    1.0
                );
            }
        """
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
