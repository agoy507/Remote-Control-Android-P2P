package com.agoy507.mdremote

import androidx.compose.ui.geometry.Offset
import com.agoy507.mdremote.ui.components.RemoteViewportMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteViewportMathTest {

    @Test
    fun `aspect fitted 16x9 frame inside portrait viewport maps without letterbox distortion`() {
        val viewportW = 1080f
        val viewportH = 2280f
        val (baseW, baseH) = RemoteViewportMath.computeBaseSize(viewportW, viewportH, 1920, 1080)

        assertEquals(1080f, baseW, 0.01f)
        assertEquals(607.5f, baseH, 0.01f)

        val rect = RemoteViewportMath.computeVideoRect(viewportW, viewportH, baseW, baseH, zoom = 1f, pan = Offset.Zero)
        assertEquals(0f, rect.videoLeft, 0.01f)
        assertEquals(836.25f, rect.videoTop, 0.01f)

        val topCenterNorm = RemoteViewportMath.screenToNorm(Offset(540f, 836.25f), rect)
        assertEquals(0.5f, topCenterNorm.x, 0.001f)
        assertEquals(0f, topCenterNorm.y, 0.001f)

        val bottomRightNorm = RemoteViewportMath.screenToNorm(Offset(1080f, 836.25f + 607.5f), rect)
        assertEquals(1f, bottomRightNorm.x, 0.001f)
        assertEquals(1f, bottomRightNorm.y, 0.001f)
    }

    @Test
    fun `pinch zoom keeps video coordinate under centroid stationary`() {
        val viewportW = 1080f
        val viewportH = 2280f
        val (baseW, baseH) = RemoteViewportMath.computeBaseSize(viewportW, viewportH, 1920, 1080)
        val initialZoom = 1f
        val initialPan = Offset.Zero
        val centroid = Offset(720f, 1020f)

        val rectBefore = RemoteViewportMath.computeVideoRect(viewportW, viewportH, baseW, baseH, initialZoom, initialPan)
        val normBefore = RemoteViewportMath.screenToNorm(centroid, rectBefore)

        val (newZoom, newPan) = RemoteViewportMath.applyPinchTransform(
            currentZoom = initialZoom,
            currentPan = initialPan,
            zoomChange = 2.5f,
            panChange = Offset.Zero,
            centroid = centroid,
            viewportW = viewportW,
            viewportH = viewportH,
            baseW = baseW,
            baseH = baseH,
            bottomInsetPx = 0f,
        )
        assertEquals(2.5f, newZoom, 0.001f)

        // When zoomed height (1518.75) still fits viewport (2280), X stays anchored and Y remains centered
        val rectAfter = RemoteViewportMath.computeVideoRect(viewportW, viewportH, baseW, baseH, newZoom, newPan)
        val normAfter = RemoteViewportMath.screenToNorm(centroid, rectAfter)
        assertEquals(normBefore.x, normAfter.x, 0.002f)
        assertEquals(0f, newPan.y, 0.01f)

        // When zoomed so both axes exceed viewport (4.5x), both X and Y stay anchored under centroid
        val fullCentroid = Offset(720f, 1110f)
        val fullBefore = RemoteViewportMath.screenToNorm(fullCentroid, rectBefore)
        val (fullZoom, fullPan) = RemoteViewportMath.applyPinchTransform(
            currentZoom = initialZoom,
            currentPan = initialPan,
            zoomChange = 4.5f,
            panChange = Offset.Zero,
            centroid = fullCentroid,
            viewportW = viewportW,
            viewportH = viewportH,
            baseW = baseW,
            baseH = baseH,
            bottomInsetPx = 0f,
        )
        val fullRectAfter = RemoteViewportMath.computeVideoRect(viewportW, viewportH, baseW, baseH, fullZoom, fullPan)
        val fullAfter = RemoteViewportMath.screenToNorm(fullCentroid, fullRectAfter)
        assertEquals(fullBefore.x, fullAfter.x, 0.002f)
        assertEquals(fullBefore.y, fullAfter.y, 0.002f)
    }

    @Test
    fun `clampPan centers without IME and allows upward pan when IME covers bottom`() {
        val viewportW = 1080f
        val viewportH = 2280f
        val (baseW, baseH) = RemoteViewportMath.computeBaseSize(viewportW, viewportH, 1920, 1080)

        val clampedNoIme = RemoteViewportMath.clampPan(
            pan = Offset(120f, -300f),
            viewportW = viewportW,
            viewportH = viewportH,
            baseW = baseW,
            baseH = baseH,
            zoom = 1f,
            bottomInsetPx = 0f,
        )
        assertEquals(0f, clampedNoIme.x, 0.01f)
        assertEquals(0f, clampedNoIme.y, 0.01f)

        val imeBottomInset = 950f
        val clampedWithIme = RemoteViewportMath.clampPan(
            pan = Offset(0f, -500f),
            viewportW = viewportW,
            viewportH = viewportH,
            baseW = baseW,
            baseH = baseH,
            zoom = 1f,
            bottomInsetPx = imeBottomInset,
        )
        // Video bottom = 836.25 + 607.5 = 1443.75, visibleBottom = 2280 - 950 = 1330 => minPanY = -113.75
        assertEquals(-113.75f, clampedWithIme.y, 0.05f)
        val rectWithIme = RemoteViewportMath.computeVideoRect(viewportW, viewportH, baseW, baseH, 1f, clampedWithIme)
        assertEquals(viewportH - imeBottomInset, rectWithIme.videoTop + rectWithIme.scaledHeight, 0.1f)
    }

    @Test
    fun `autoPanForCursor keeps virtual cursor within visible safe bounds when zoomed`() {
        val viewportW = 1080f
        val viewportH = 2280f
        val (baseW, baseH) = RemoteViewportMath.computeBaseSize(viewportW, viewportH, 1920, 1080)
        val zoom = 3f
        val edgeMargin = 40f
        val topInset = 120f
        val bottomInset = 60f

        val newPan = RemoteViewportMath.autoPanForCursor(
            cursorNorm = Offset(0.98f, 0.5f),
            currentPan = Offset.Zero,
            viewportW = viewportW,
            viewportH = viewportH,
            baseW = baseW,
            baseH = baseH,
            zoom = zoom,
            edgeMarginPx = edgeMargin,
            topInsetPx = topInset,
            bottomInsetPx = bottomInset,
        )

        assertTrue("Expected negative horizontal pan to keep right-edge cursor visible", newPan.x < -100f)
        val newRect = RemoteViewportMath.computeVideoRect(viewportW, viewportH, baseW, baseH, zoom, newPan)
        val cursorScreen = RemoteViewportMath.normToScreen(Offset(0.98f, 0.5f), newRect)
        assertTrue(cursorScreen.x <= viewportW - edgeMargin + 0.5f)
    }

    @Test
    fun `isPortraitFrame detects portrait mobile frames vs landscape desktop frames`() {
        assertTrue(RemoteViewportMath.isPortraitFrame(1080, 2280))
        org.junit.Assert.assertFalse(RemoteViewportMath.isPortraitFrame(1920, 1080))
        org.junit.Assert.assertFalse(RemoteViewportMath.isPortraitFrame(1280, 720))
        org.junit.Assert.assertFalse(RemoteViewportMath.isPortraitFrame(0, 0))
    }

    @Test
    fun `computeGlCrop computes exact visible UV sub-rect and OpenGL viewport`() {
        val viewportW = 1080f
        val viewportH = 2280f

        // 1. At zoom 1x, full 1920x1080 frame uses full UVs (0..1) and centered GL viewport
        val crop1x = RemoteViewportMath.computeGlCrop(
            viewportW = viewportW,
            viewportH = viewportH,
            frameW = 1920,
            frameH = 1080,
            zoom = 1f,
            pan = Offset.Zero,
        )!!
        assertEquals(0f, crop1x.uLeft, 0.001f)
        assertEquals(1f, crop1x.uRight, 0.001f)
        assertEquals(0f, crop1x.vTop, 0.001f)
        assertEquals(1f, crop1x.vBottom, 0.001f)
        assertEquals(0, crop1x.glX)
        assertEquals(1080, crop1x.glWidth)
        assertEquals(608, crop1x.glHeight)
        assertEquals(836, crop1x.glY)

        // 2. At zoom 4x with pan, UVs match screenToNorm of visible screen intersection and stay in bounds
        val zoom = 4f
        val pan = Offset(-640f, 120f)
        val crop4x = RemoteViewportMath.computeGlCrop(
            viewportW = viewportW,
            viewportH = viewportH,
            frameW = 1920,
            frameH = 1080,
            zoom = zoom,
            pan = pan,
        )!!
        assertTrue(crop4x.glX >= 0 && crop4x.glX + crop4x.glWidth <= viewportW.toInt())
        assertTrue(crop4x.glY >= 0 && crop4x.glY + crop4x.glHeight <= viewportH.toInt())
        assertTrue(crop4x.uRight > crop4x.uLeft && crop4x.uLeft >= 0f && crop4x.uRight <= 1f)
        assertTrue(crop4x.vBottom > crop4x.vTop && crop4x.vTop >= 0f && crop4x.vBottom <= 1f)

        val (bw, bh) = RemoteViewportMath.computeBaseSize(viewportW, viewportH, 1920, 1080)
        val rect4x = RemoteViewportMath.computeVideoRect(viewportW, viewportH, bw, bh, zoom, pan)
        val visLeft = maxOf(0f, rect4x.videoLeft)
        val visRight = minOf(viewportW, rect4x.videoLeft + rect4x.scaledWidth)
        val visTop = maxOf(0f, rect4x.videoTop)
        val visBottom = minOf(viewportH, rect4x.videoTop + rect4x.scaledHeight)
        val expectedTopLeft = RemoteViewportMath.screenToNorm(Offset(visLeft, visTop), rect4x)
        val expectedBotRight = RemoteViewportMath.screenToNorm(Offset(visRight, visBottom), rect4x)
        assertEquals(expectedTopLeft.x, crop4x.uLeft, 0.001f)
        assertEquals(expectedTopLeft.y, crop4x.vTop, 0.001f)
        assertEquals(expectedBotRight.x, crop4x.uRight, 0.001f)
        assertEquals(expectedBotRight.y, crop4x.vBottom, 0.001f)
    }

    @Test
    fun `top aligned landscape video sits below top anchor at 1x and 1_6x and anchors zoom cleanly`() {
        val viewportW = 1080f
        val viewportH = 2400f
        val topAnchor = 180f
        val (baseW, baseH) = RemoteViewportMath.computeBaseSize(viewportW, viewportH, 1366, 768)

        // At 1x and 1.6x, videoTop stays pinned right at topAnchor (no gap above!)
        val rect1x = RemoteViewportMath.computeVideoRect(
            viewportW, viewportH, baseW, baseH, zoom = 1f, pan = Offset.Zero, alignTop = true, topAnchorPx = topAnchor,
        )
        assertEquals(topAnchor, rect1x.videoTop, 0.01f)

        val crop16x = RemoteViewportMath.computeGlCrop(
            viewportW = viewportW,
            viewportH = viewportH,
            frameW = 1366,
            frameH = 768,
            zoom = 1.6f,
            pan = Offset.Zero,
            alignTop = true,
            topAnchorPx = topAnchor,
        )!!
        val expectedGlTop = viewportH - topAnchor
        assertEquals(expectedGlTop, (crop16x.glY + crop16x.glHeight).toFloat(), 1.5f)

        // At 4x zoom, vertical panning down to the bottom of the screen is allowed without exposing above topAnchor
        val clamped = RemoteViewportMath.clampPan(
            pan = Offset(0f, 150f),
            viewportW = viewportW,
            viewportH = viewportH,
            baseW = baseW,
            baseH = baseH,
            zoom = 4f,
            alignTop = true,
            topAnchorPx = topAnchor,
        )
        assertEquals(0f, clamped.y, 0.01f)
    }
}
