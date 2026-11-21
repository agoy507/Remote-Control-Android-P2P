package com.agoy507.mdremote.ui.components

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.min

data class FittedVideoRect(
    val baseWidth: Float,
    val baseHeight: Float,
    val videoLeft: Float,
    val videoTop: Float,
    val scaledWidth: Float,
    val scaledHeight: Float,
)

/**
 * Pure coordinate-mapping and pinch-zoom/pan math shared between [KanvasRemoteScreen]
 * and unit tests so controller touches and virtual cursor positions match the exact
 * aspect-fitted remote frame without letterbox offset distortion.
 */
object RemoteViewportMath {

    fun isPortraitFrame(frameW: Int, frameH: Int): Boolean =
        frameW > 0 && frameH > 0 && frameH > frameW

    fun computeBaseSize(
        viewportW: Float,
        viewportH: Float,
        frameW: Int,
        frameH: Int,
    ): Pair<Float, Float> {
        val vw = viewportW.coerceAtLeast(1f)
        val vh = viewportH.coerceAtLeast(1f)
        val aspect = if (frameW > 0 && frameH > 0) {
            frameW.toFloat() / frameH.toFloat()
        } else {
            16f / 9f
        }
        return if (vw / vh > aspect) {
            val baseH = vh
            val baseW = (baseH * aspect).coerceAtLeast(1f)
            baseW to baseH
        } else {
            val baseW = vw
            val baseH = (baseW / aspect).coerceAtLeast(1f)
            baseW to baseH
        }
    }

    fun clampPan(
        pan: Offset,
        viewportW: Float,
        viewportH: Float,
        baseW: Float,
        baseH: Float,
        zoom: Float,
        bottomInsetPx: Float = 0f,
    ): Offset {
        val vw = viewportW.coerceAtLeast(1f)
        val vh = viewportH.coerceAtLeast(1f)
        val scaledW = baseW * zoom
        val scaledH = baseH * zoom

        val maxPanX = ((scaledW - vw) / 2f).coerceAtLeast(0f)
        val maxPanY = ((scaledH - vh) / 2f).coerceAtLeast(0f)
        val minPanY = min(-maxPanY, (vh - scaledH) / 2f - bottomInsetPx.coerceAtLeast(0f))

        return Offset(
            x = pan.x.coerceIn(-maxPanX, maxPanX),
            y = pan.y.coerceIn(minPanY, maxPanY),
        )
    }

    fun computeVideoRect(
        viewportW: Float,
        viewportH: Float,
        baseW: Float,
        baseH: Float,
        zoom: Float,
        pan: Offset,
    ): FittedVideoRect {
        val scaledW = (baseW * zoom).coerceAtLeast(1f)
        val scaledH = (baseH * zoom).coerceAtLeast(1f)
        val left = (viewportW - scaledW) / 2f + pan.x
        val top = (viewportH - scaledH) / 2f + pan.y
        return FittedVideoRect(
            baseWidth = baseW,
            baseHeight = baseH,
            videoLeft = left,
            videoTop = top,
            scaledWidth = scaledW,
            scaledHeight = scaledH,
        )
    }

    fun screenToNorm(screenPos: Offset, rect: FittedVideoRect): Offset {
        val nx = ((screenPos.x - rect.videoLeft) / rect.scaledWidth.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val ny = ((screenPos.y - rect.videoTop) / rect.scaledHeight.coerceAtLeast(1f)).coerceIn(0f, 1f)
        return Offset(nx, ny)
    }

    fun normToScreen(normPos: Offset, rect: FittedVideoRect): Offset {
        val sx = rect.videoLeft + normPos.x.coerceIn(0f, 1f) * rect.scaledWidth
        val sy = rect.videoTop + normPos.y.coerceIn(0f, 1f) * rect.scaledHeight
        return Offset(sx, sy)
    }

    fun applyPinchTransform(
        currentZoom: Float,
        currentPan: Offset,
        zoomChange: Float,
        panChange: Offset,
        centroid: Offset,
        viewportW: Float,
        viewportH: Float,
        baseW: Float,
        baseH: Float,
        minZoom: Float = 1f,
        maxZoom: Float = 5f,
        bottomInsetPx: Float = 0f,
    ): Pair<Float, Offset> {
        val safeZoom = currentZoom.coerceAtLeast(0.01f)
        val newZoom = (safeZoom * zoomChange).coerceIn(minZoom, maxZoom)
        val scaleFactor = newZoom / safeZoom
        val center = Offset(viewportW / 2f, viewportH / 2f)

        val anchoredPan = (centroid - center) * (1f - scaleFactor) +
            currentPan * scaleFactor +
            panChange

        val clampedPan = clampPan(
            pan = anchoredPan,
            viewportW = viewportW,
            viewportH = viewportH,
            baseW = baseW,
            baseH = baseH,
            zoom = newZoom,
            bottomInsetPx = bottomInsetPx,
        )
        return newZoom to clampedPan
    }

    fun autoPanForCursor(
        cursorNorm: Offset,
        currentPan: Offset,
        viewportW: Float,
        viewportH: Float,
        baseW: Float,
        baseH: Float,
        zoom: Float,
        edgeMarginPx: Float,
        topInsetPx: Float,
        bottomInsetPx: Float,
    ): Offset {
        if (zoom <= 1.01f && bottomInsetPx <= 0f) return currentPan

        val rect = computeVideoRect(viewportW, viewportH, baseW, baseH, zoom, currentPan)
        val cursorScreen = normToScreen(cursorNorm, rect)

        val safeLeft = edgeMarginPx
        val safeRight = (viewportW - edgeMarginPx).coerceAtLeast(safeLeft + 1f)
        val safeTop = (topInsetPx + edgeMarginPx).coerceAtMost(viewportH * 0.45f)
        val safeBottom = (viewportH - bottomInsetPx - edgeMarginPx).coerceAtLeast(safeTop + 1f)

        var dx = 0f
        if (cursorScreen.x < safeLeft) {
            dx = safeLeft - cursorScreen.x
        } else if (cursorScreen.x > safeRight) {
            dx = safeRight - cursorScreen.x
        }

        var dy = 0f
        if (cursorScreen.y < safeTop) {
            dy = safeTop - cursorScreen.y
        } else if (cursorScreen.y > safeBottom) {
            dy = safeBottom - cursorScreen.y
        }

        if (abs(dx) < 0.5f && abs(dy) < 0.5f) return currentPan

        return clampPan(
            pan = currentPan + Offset(dx, dy),
            viewportW = viewportW,
            viewportH = viewportH,
            baseW = baseW,
            baseH = baseH,
            zoom = zoom,
            bottomInsetPx = bottomInsetPx,
        )
    }
}
