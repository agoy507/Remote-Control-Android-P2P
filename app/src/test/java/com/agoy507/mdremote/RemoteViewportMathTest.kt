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
}
