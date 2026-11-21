package com.agoy507.mdremote.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.agoy507.mdremote.R
import com.agoy507.mdremote.model.ConnectionTelemetry
import com.agoy507.mdremote.model.RemoteMouseAction
import com.agoy507.mdremote.model.RemoteMouseButton
import com.agoy507.mdremote.model.RemoteSystemAction
import com.agoy507.mdremote.model.StreamQualityPreset
import com.agoy507.mdremote.model.formatDeviceId
import com.agoy507.mdremote.ui.components.ExtensionKeyBar
import com.agoy507.mdremote.ui.components.ModifierKeysState
import com.agoy507.mdremote.ui.components.MouseOverlayButtons
import com.agoy507.mdremote.ui.components.RemoteImeCaptureView
import com.agoy507.mdremote.ui.components.RemoteViewportMath
import com.agoy507.mdremote.ui.theme.ElectricCyan
import com.agoy507.mdremote.ui.theme.MonoTelemetryStyle
import com.agoy507.mdremote.webrtc.TextureVideoRenderer
import com.agoy507.mdremote.webrtc.WebRtcRemoteManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

private enum class RemoteInputMode {
    TOUCHPAD,
    TOUCHSCREEN,
}

private enum class CanvasGesturePhase {
    UNDECIDED,
    SINGLE_FINGER_TAP_OR_DRAG,
    MULTI_FINGER_UNDECIDED,
    PINCH_ZOOM,
    TWO_FINGER_SCROLL,
    MULTI_FINGER_DONE,
}

@Composable
fun KanvasRemoteScreen(
    targetId: String,
    targetAlias: String,
    telemetry: ConnectionTelemetry,
    currentQuality: StreamQualityPreset,
    webRtcManager: WebRtcRemoteManager,
    onSendTap: (xNorm: Float, yNorm: Float, isLongPress: Boolean) -> Unit,
    onSendSwipe: (startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) -> Unit,
    onSendMouseMove: (xNorm: Float, yNorm: Float) -> Unit,
    onSendMouseButton: (xNorm: Float, yNorm: Float, button: RemoteMouseButton, action: RemoteMouseAction) -> Unit,
    onSendMouseScroll: (xNorm: Float, yNorm: Float, deltaX: Float, deltaY: Float) -> Unit,
    onSendKeyEvent: (key: String, ctrl: Boolean, alt: Boolean, shift: Boolean, meta: Boolean) -> Unit,
    onSendSystemAction: (RemoteSystemAction) -> Unit,
    onSendTextInput: (String) -> Unit,
    onChangeQuality: (StreamQualityPreset) -> Unit,
    onEndSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val gestureScope = rememberCoroutineScope()

    var inputMode by remember { mutableStateOf(RemoteInputMode.TOUCHPAD) }
    var userOverrodeInputMode by remember { mutableStateOf(false) }
    var showMouseOverlay by remember { mutableStateOf(false) }
    var keyboardVisible by remember { mutableStateOf(false) }
    var toolbarExpanded by remember { mutableStateOf(true) }
    var toolbarPinned by remember { mutableStateOf(false) }
    var toolbarInteractionTick by remember { mutableIntStateOf(0) }
    var modifiers by remember { mutableStateOf(ModifierKeysState()) }

    var viewportSize by remember { mutableStateOf(IntSize(1, 1)) }
    var frameWidth by remember { mutableIntStateOf(0) }
    var frameHeight by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var cursorNorm by remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    var isDragLocked by remember { mutableStateOf(false) }
    var lastTouchScreenPos by remember { mutableStateOf<Offset?>(null) }

    var showFingerprintDialog by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showNavDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }

    val imeView = remember { RemoteImeCaptureView(context) }
    val imeBottomPx = WindowInsets.ime.getBottom(density).toFloat()
    val statusBarTopPx = WindowInsets.statusBars.getTop(density).toFloat()
    val extensionBarHeightPx = if (keyboardVisible) with(density) { 50.dp.toPx() } else 0f
    val effectiveBottomInsetPx = imeBottomPx + extensionBarHeightPx
    val edgeMarginPx = with(density) { 40.dp.toPx() }
    val topSafeInsetPx = statusBarTopPx + with(density) { 64.dp.toPx() }

    var sawImeActuallyOpen by remember { mutableStateOf(false) }
    LaunchedEffect(imeBottomPx, keyboardVisible) {
        if (!keyboardVisible) {
            sawImeActuallyOpen = false
        } else if (imeBottomPx > with(density) { 80.dp.toPx() }) {
            sawImeActuallyOpen = true
        } else if (sawImeActuallyOpen && imeBottomPx <= 1f) {
            keyboardVisible = false
            sawImeActuallyOpen = false
            imeView.clearFocus()
        }
    }

    // Auto-detect control mode from target frame orientation unless manually overridden
    LaunchedEffect(frameWidth, frameHeight, userOverrodeInputMode) {
        if (!userOverrodeInputMode && frameWidth > 0 && frameHeight > 0) {
            val autoMode = if (RemoteViewportMath.isPortraitFrame(frameWidth, frameHeight)) {
                RemoteInputMode.TOUCHSCREEN
            } else {
                RemoteInputMode.TOUCHPAD
            }
            if (autoMode == RemoteInputMode.TOUCHSCREEN) {
                isDragLocked = false
            }
            inputMode = autoMode
        }
    }

    // Re-clamp pan whenever viewport dimensions, frame aspect, or IME inset changes
    LaunchedEffect(viewportSize, frameWidth, frameHeight, zoom, effectiveBottomInsetPx) {
        val (bw, bh) = RemoteViewportMath.computeBaseSize(
            viewportSize.width.toFloat(),
            viewportSize.height.toFloat(),
            frameWidth,
            frameHeight,
        )
        panOffset = RemoteViewportMath.clampPan(
            pan = panOffset,
            viewportW = viewportSize.width.toFloat(),
            viewportH = viewportSize.height.toFloat(),
            baseW = bw,
            baseH = bh,
            zoom = zoom,
            bottomInsetPx = effectiveBottomInsetPx,
        )
    }

    // Auto-collapse unpinned toolbar after 18s of inactivity (similar to RealVNC)
    LaunchedEffect(toolbarExpanded, toolbarPinned, toolbarInteractionTick) {
        if (toolbarExpanded && !toolbarPinned) {
            delay(18_000L)
            toolbarExpanded = false
        }
    }

    BackHandler {
        if (keyboardVisible) {
            imeView.hideSoftKeyboard()
            keyboardVisible = false
            modifiers = ModifierKeysState()
        } else {
            showExitConfirm = true
        }
    }

    val renderer = remember { TextureVideoRenderer(context) }
    DisposableEffect(renderer) {
        renderer.init(webRtcManager.eglBase.eglBaseContext) { w, h ->
            frameWidth = w
            frameHeight = h
        }
        webRtcManager.attachVideoRenderer(renderer)
        onDispose {
            if (isDragLocked) {
                onSendMouseButton(cursorNorm.x, cursorNorm.y, RemoteMouseButton.LEFT, RemoteMouseAction.UP)
                isDragLocked = false
            }
            imeView.hideSoftKeyboard()
            webRtcManager.detachVideoRenderer(renderer)
            renderer.release()
        }
    }

    fun handleLiveCommitText(raw: String) {
        val currentMods = modifiers
        val ctrl = currentMods.ctrl.isActive()
        val alt = currentMods.alt.isActive()
        val shift = currentMods.shift.isActive()
        val meta = currentMods.meta.isActive()
        if ((ctrl || alt || meta) && raw.length == 1) {
            onSendKeyEvent(raw.uppercase(), ctrl, alt, shift, meta)
            modifiers = currentMods.releaseLatched()
        } else if (shift && raw.length == 1) {
            onSendKeyEvent(raw.uppercase(), false, false, true, false)
            modifiers = currentMods.releaseLatched()
        } else {
            onSendTextInput(raw)
            if (currentMods.hasAnyActive()) {
                modifiers = currentMods.releaseLatched()
            }
        }
    }

    fun handleLiveSpecialKey(key: String) {
        val currentMods = modifiers
        onSendKeyEvent(
            key,
            currentMods.ctrl.isActive(),
            currentMods.alt.isActive(),
            currentMods.shift.isActive(),
            currentMods.meta.isActive(),
        )
        if (currentMods.hasAnyActive()) {
            modifiers = currentMods.releaseLatched()
        }
    }

    fun toggleModifier(mod: String) {
        modifiers = when (mod) {
            "CTRL" -> modifiers.copy(ctrl = modifiers.ctrl.next())
            "ALT" -> modifiers.copy(alt = modifiers.alt.next())
            "SHIFT" -> modifiers.copy(shift = modifiers.shift.next())
            "META" -> modifiers.copy(meta = modifiers.meta.next())
            else -> modifiers
        }
    }

    // Updated state holders so single pointerInput never cancels ongoing pinch/drag mid-gesture
    val latestInputMode by rememberUpdatedState(inputMode)
    val latestViewportSize by rememberUpdatedState(viewportSize)
    val latestFrameWidth by rememberUpdatedState(frameWidth)
    val latestFrameHeight by rememberUpdatedState(frameHeight)
    val latestBottomInset by rememberUpdatedState(effectiveBottomInsetPx)
    val latestTopInset by rememberUpdatedState(topSafeInsetPx)
    val latestEdgeMargin by rememberUpdatedState(edgeMarginPx)

    val latestOnTap by rememberUpdatedState(onSendTap)
    val latestOnSwipe by rememberUpdatedState(onSendSwipe)
    val latestOnMouseMove by rememberUpdatedState(onSendMouseMove)
    val latestOnMouseButton by rememberUpdatedState(onSendMouseButton)
    val latestOnMouseScroll by rememberUpdatedState(onSendMouseScroll)

    val (baseW, baseH) = RemoteViewportMath.computeBaseSize(
        viewportSize.width.toFloat(),
        viewportSize.height.toFloat(),
        frameWidth,
        frameHeight,
    )
    val currentVideoRect = RemoteViewportMath.computeVideoRect(
        viewportW = viewportSize.width.toFloat(),
        viewportH = viewportSize.height.toFloat(),
        baseW = baseW,
        baseH = baseH,
        zoom = zoom,
        pan = panOffset,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Hidden 1dp focusable IME bridge for live typing
        AndroidView(
            factory = { imeView },
            update = { view ->
                view.onCommitText = { text -> handleLiveCommitText(text) }
                view.onSpecialKey = { key -> handleLiveSpecialKey(key) }
            },
            modifier = Modifier
                .size(1.dp)
                .alpha(0f),
        )

        // Video Stream Canvas + Unified Multi-Touch Gesture Engine
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { viewportSize = it }
                .pointerInput(Unit) {
                    var lastTapUpTimeMs = 0L
                    var lastMoveSentTimeMs = 0L

                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        firstDown.consume()

                        val vw = latestViewportSize.width.toFloat().coerceAtLeast(1f)
                        val vh = latestViewportSize.height.toFloat().coerceAtLeast(1f)
                        val (bw, bh) = RemoteViewportMath.computeBaseSize(
                            vw,
                            vh,
                            latestFrameWidth,
                            latestFrameHeight,
                        )
                        val rectAtDown = RemoteViewportMath.computeVideoRect(
                            vw,
                            vh,
                            bw,
                            bh,
                            zoom,
                            panOffset,
                        )

                        val downPos = firstDown.position
                        val downTimeMs = System.currentTimeMillis()
                        val isDoubleTapCandidate = (latestInputMode == RemoteInputMode.TOUCHPAD) &&
                            (downTimeMs - lastTapUpTimeMs <= 300L)

                        var phase = CanvasGesturePhase.UNDECIDED
                        var movedPastSlop = false
                        var longPressSent = false
                        var dragButtonLocked = false
                        var lastSinglePos = downPos
                        var multiDownTimeMs = downTimeMs
                        var peakPointerCount = 1
                        var initialMultiCentroid = Offset.Zero
                        var initialMultiSpan = 0f
                        var prevMultiCentroid = Offset.Zero
                        var prevMultiSpan = 0f
                        var scrollAccX = 0f
                        var scrollAccY = 0f
                        val startNormTouchscreen = RemoteViewportMath.screenToNorm(downPos, rectAtDown)

                        var longPressJob: Job? = null
                        if (latestInputMode == RemoteInputMode.TOUCHSCREEN) {
                            longPressJob = gestureScope.launch {
                                delay(500L)
                                if (!movedPastSlop && peakPointerCount == 1 && !longPressSent) {
                                    longPressSent = true
                                    lastTouchScreenPos = downPos
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    latestOnTap(startNormTouchscreen.x, startNormTouchscreen.y, true)
                                }
                            }
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val activeChanges = event.changes.filter { it.pressed }
                            val count = activeChanges.size
                            peakPointerCount = max(peakPointerCount, count)

                            if (count == 0) {
                                longPressJob?.cancel()
                                event.changes.forEach { it.consume() }

                                if (dragButtonLocked) {
                                    dragButtonLocked = false
                                    isDragLocked = false
                                    latestOnMouseMove(cursorNorm.x, cursorNorm.y)
                                    latestOnMouseButton(
                                        cursorNorm.x,
                                        cursorNorm.y,
                                        RemoteMouseButton.LEFT,
                                        RemoteMouseAction.UP,
                                    )
                                } else if (
                                    phase == CanvasGesturePhase.UNDECIDED ||
                                    phase == CanvasGesturePhase.SINGLE_FINGER_TAP_OR_DRAG
                                ) {
                                    if (!movedPastSlop && !longPressSent) {
                                        if (latestInputMode == RemoteInputMode.TOUCHPAD) {
                                            latestOnMouseMove(cursorNorm.x, cursorNorm.y)
                                            latestOnMouseButton(
                                                cursorNorm.x,
                                                cursorNorm.y,
                                                RemoteMouseButton.LEFT,
                                                RemoteMouseAction.CLICK,
                                            )
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            lastTapUpTimeMs = System.currentTimeMillis()
                                        } else {
                                            lastTouchScreenPos = downPos
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            latestOnTap(startNormTouchscreen.x, startNormTouchscreen.y, false)
                                        }
                                    } else if (movedPastSlop) {
                                        if (latestInputMode == RemoteInputMode.TOUCHPAD) {
                                            latestOnMouseMove(cursorNorm.x, cursorNorm.y)
                                        } else {
                                            val curVw = latestViewportSize.width.toFloat().coerceAtLeast(1f)
                                            val curVh = latestViewportSize.height.toFloat().coerceAtLeast(1f)
                                            val (curBw, curBh) = RemoteViewportMath.computeBaseSize(
                                                curVw,
                                                curVh,
                                                latestFrameWidth,
                                                latestFrameHeight,
                                            )
                                            val curRect = RemoteViewportMath.computeVideoRect(
                                                curVw,
                                                curVh,
                                                curBw,
                                                curBh,
                                                zoom,
                                                panOffset,
                                            )
                                            val endNorm = RemoteViewportMath.screenToNorm(lastSinglePos, curRect)
                                            val duration = (System.currentTimeMillis() - downTimeMs).coerceIn(80L, 1200L)
                                            latestOnSwipe(
                                                startNormTouchscreen.x,
                                                startNormTouchscreen.y,
                                                endNorm.x,
                                                endNorm.y,
                                                duration,
                                            )
                                        }
                                    }
                                } else if (phase == CanvasGesturePhase.MULTI_FINGER_UNDECIDED) {
                                    val elapsed = System.currentTimeMillis() - multiDownTimeMs
                                    if (elapsed <= 350L) {
                                        if (peakPointerCount == 2) {
                                            if (latestInputMode == RemoteInputMode.TOUCHPAD) {
                                                latestOnMouseMove(cursorNorm.x, cursorNorm.y)
                                                latestOnMouseButton(
                                                    cursorNorm.x,
                                                    cursorNorm.y,
                                                    RemoteMouseButton.RIGHT,
                                                    RemoteMouseAction.CLICK,
                                                )
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            } else {
                                                val curVw = latestViewportSize.width.toFloat().coerceAtLeast(1f)
                                                val curVh = latestViewportSize.height.toFloat().coerceAtLeast(1f)
                                                val (curBw, curBh) = RemoteViewportMath.computeBaseSize(
                                                    curVw,
                                                    curVh,
                                                    latestFrameWidth,
                                                    latestFrameHeight,
                                                )
                                                val curRect = RemoteViewportMath.computeVideoRect(
                                                    curVw,
                                                    curVh,
                                                    curBw,
                                                    curBh,
                                                    zoom,
                                                    panOffset,
                                                )
                                                val norm = RemoteViewportMath.screenToNorm(initialMultiCentroid, curRect)
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                latestOnTap(norm.x, norm.y, true)
                                            }
                                        } else if (peakPointerCount >= 3 && latestInputMode == RemoteInputMode.TOUCHPAD) {
                                            latestOnMouseMove(cursorNorm.x, cursorNorm.y)
                                            latestOnMouseButton(
                                                cursorNorm.x,
                                                cursorNorm.y,
                                                RemoteMouseButton.MIDDLE,
                                                RemoteMouseAction.CLICK,
                                            )
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    }
                                }
                                break
                            }

                            if (count >= 2) {
                                longPressJob?.cancel()
                                val centroid = computeCentroid(activeChanges.map { it.position })
                                val span = computeSpan(activeChanges.map { it.position }, centroid)

                                val enteringMulti = phase == CanvasGesturePhase.UNDECIDED ||
                                    (phase == CanvasGesturePhase.SINGLE_FINGER_TAP_OR_DRAG && !movedPastSlop)

                                if (enteringMulti) {
                                    phase = CanvasGesturePhase.MULTI_FINGER_UNDECIDED
                                    multiDownTimeMs = System.currentTimeMillis()
                                    initialMultiCentroid = centroid
                                    initialMultiSpan = span
                                    prevMultiCentroid = centroid
                                    prevMultiSpan = span
                                    scrollAccX = 0f
                                    scrollAccY = 0f
                                } else if (phase == CanvasGesturePhase.MULTI_FINGER_UNDECIDED && count == 2) {
                                    val spanDelta = abs(span - initialMultiSpan)
                                    val centroidMove = (centroid - initialMultiCentroid).getDistance()
                                    val zoomRatio = if (initialMultiSpan > 12f && span > 12f) {
                                        span / initialMultiSpan
                                    } else {
                                        1f
                                    }
                                    if (spanDelta > 16f && abs(1f - zoomRatio) > 0.04f) {
                                        phase = CanvasGesturePhase.PINCH_ZOOM
                                    } else if (centroidMove > 14f) {
                                        phase = if (latestInputMode == RemoteInputMode.TOUCHPAD) {
                                            CanvasGesturePhase.TWO_FINGER_SCROLL
                                        } else {
                                            CanvasGesturePhase.PINCH_ZOOM
                                        }
                                    }
                                }

                                val curVw = latestViewportSize.width.toFloat().coerceAtLeast(1f)
                                val curVh = latestViewportSize.height.toFloat().coerceAtLeast(1f)
                                val (curBw, curBh) = RemoteViewportMath.computeBaseSize(
                                    curVw,
                                    curVh,
                                    latestFrameWidth,
                                    latestFrameHeight,
                                )

                                when (phase) {
                                    CanvasGesturePhase.PINCH_ZOOM -> {
                                        val zChange = if (prevMultiSpan > 12f && span > 12f) {
                                            span / prevMultiSpan
                                        } else {
                                            1f
                                        }
                                        val pChange = centroid - prevMultiCentroid
                                        val (newZoom, newPan) = RemoteViewportMath.applyPinchTransform(
                                            currentZoom = zoom,
                                            currentPan = panOffset,
                                            zoomChange = zChange,
                                            panChange = pChange,
                                            centroid = centroid,
                                            viewportW = curVw,
                                            viewportH = curVh,
                                            baseW = curBw,
                                            baseH = curBh,
                                            minZoom = 1f,
                                            maxZoom = 5f,
                                            bottomInsetPx = latestBottomInset,
                                        )
                                        zoom = newZoom
                                        panOffset = newPan
                                        prevMultiCentroid = centroid
                                        prevMultiSpan = span
                                    }
                                    CanvasGesturePhase.TWO_FINGER_SCROLL -> {
                                        val delta = centroid - prevMultiCentroid
                                        scrollAccX += delta.x
                                        scrollAccY += delta.y
                                        val stepPx = 28f
                                        while (scrollAccY <= -stepPx) {
                                            latestOnMouseScroll(cursorNorm.x, cursorNorm.y, 0f, -1f)
                                            scrollAccY += stepPx
                                        }
                                        while (scrollAccY >= stepPx) {
                                            latestOnMouseScroll(cursorNorm.x, cursorNorm.y, 0f, 1f)
                                            scrollAccY -= stepPx
                                        }
                                        while (scrollAccX <= -stepPx) {
                                            latestOnMouseScroll(cursorNorm.x, cursorNorm.y, 1f, 0f)
                                            scrollAccX += stepPx
                                        }
                                        while (scrollAccX >= stepPx) {
                                            latestOnMouseScroll(cursorNorm.x, cursorNorm.y, -1f, 0f)
                                            scrollAccX -= stepPx
                                        }
                                        prevMultiCentroid = centroid
                                        prevMultiSpan = span
                                    }
                                    else -> {
                                        prevMultiCentroid = centroid
                                        prevMultiSpan = span
                                    }
                                }
                                activeChanges.forEach { it.consume() }
                            } else {
                                // Single finger remaining
                                if (phase == CanvasGesturePhase.MULTI_FINGER_UNDECIDED ||
                                    phase == CanvasGesturePhase.PINCH_ZOOM ||
                                    phase == CanvasGesturePhase.TWO_FINGER_SCROLL ||
                                    phase == CanvasGesturePhase.MULTI_FINGER_DONE
                                ) {
                                    phase = CanvasGesturePhase.MULTI_FINGER_DONE
                                    activeChanges.forEach { it.consume() }
                                } else {
                                    val change = activeChanges[0]
                                    val pos = change.position

                                    if (!movedPastSlop && (pos - downPos).getDistance() > viewConfiguration.touchSlop) {
                                        movedPastSlop = true
                                        phase = CanvasGesturePhase.SINGLE_FINGER_TAP_OR_DRAG
                                        longPressJob?.cancel()
                                        if (latestInputMode == RemoteInputMode.TOUCHPAD && isDoubleTapCandidate) {
                                            dragButtonLocked = true
                                            isDragLocked = true
                                            latestOnMouseButton(
                                                cursorNorm.x,
                                                cursorNorm.y,
                                                RemoteMouseButton.LEFT,
                                                RemoteMouseAction.DOWN,
                                            )
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                        lastSinglePos = pos
                                    }

                                    if (movedPastSlop) {
                                        val delta = pos - lastSinglePos
                                        lastSinglePos = pos

                                        if (latestInputMode == RemoteInputMode.TOUCHPAD) {
                                            val curVw = latestViewportSize.width.toFloat().coerceAtLeast(1f)
                                            val curVh = latestViewportSize.height.toFloat().coerceAtLeast(1f)
                                            val (curBw, curBh) = RemoteViewportMath.computeBaseSize(
                                                curVw,
                                                curVh,
                                                latestFrameWidth,
                                                latestFrameHeight,
                                            )
                                            val curRect = RemoteViewportMath.computeVideoRect(
                                                curVw,
                                                curVh,
                                                curBw,
                                                curBh,
                                                zoom,
                                                panOffset,
                                            )
                                            val sensitivity = 1.35f
                                            val dNormX = (delta.x * sensitivity) / curRect.scaledWidth.coerceAtLeast(1f)
                                            val dNormY = (delta.y * sensitivity) / curRect.scaledHeight.coerceAtLeast(1f)
                                            val nextCursor = Offset(
                                                x = (cursorNorm.x + dNormX).coerceIn(0f, 1f),
                                                y = (cursorNorm.y + dNormY).coerceIn(0f, 1f),
                                            )
                                            cursorNorm = nextCursor
                                            panOffset = RemoteViewportMath.autoPanForCursor(
                                                cursorNorm = nextCursor,
                                                currentPan = panOffset,
                                                viewportW = curVw,
                                                viewportH = curVh,
                                                baseW = curBw,
                                                baseH = curBh,
                                                zoom = zoom,
                                                edgeMarginPx = latestEdgeMargin,
                                                topInsetPx = latestTopInset,
                                                bottomInsetPx = latestBottomInset,
                                            )
                                            val now = System.currentTimeMillis()
                                            if (now - lastMoveSentTimeMs >= 18L) {
                                                lastMoveSentTimeMs = now
                                                latestOnMouseMove(nextCursor.x, nextCursor.y)
                                            }
                                        } else {
                                            lastTouchScreenPos = pos
                                        }
                                    }
                                    change.consume()
                                }
                            }
                        }
                    }
                }
                .testTag("remote_video_canvas"),
        ) {
            val baseWidthDp = with(density) { baseW.toDp() }
            val baseHeightDp = with(density) { baseH.toDp() }

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(baseWidthDp, baseHeightDp)
                    .graphicsLayer(
                        scaleX = zoom,
                        scaleY = zoom,
                        translationX = panOffset.x,
                        translationY = panOffset.y,
                    ),
            ) {
                AndroidView(
                    factory = { renderer },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Virtual PC Cursor (Touchpad mode) or Touch Ring (Touchscreen mode)
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (inputMode == RemoteInputMode.TOUCHPAD) {
                    val pt = RemoteViewportMath.normToScreen(cursorNorm, currentVideoRect)
                    if (isDragLocked) {
                        drawCircle(
                            color = ElectricCyan.copy(alpha = 0.28f),
                            radius = 18.dp.toPx(),
                            center = pt,
                        )
                        drawCircle(
                            color = ElectricCyan,
                            radius = 12.dp.toPx(),
                            center = pt,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                    val arrowPath = Path().apply {
                        moveTo(pt.x, pt.y)
                        lineTo(pt.x, pt.y + 20.dp.toPx())
                        lineTo(pt.x + 5.dp.toPx(), pt.y + 15.5.dp.toPx())
                        lineTo(pt.x + 9.5.dp.toPx(), pt.y + 23.dp.toPx())
                        lineTo(pt.x + 12.5.dp.toPx(), pt.y + 21.5.dp.toPx())
                        lineTo(pt.x + 8.dp.toPx(), pt.y + 14.dp.toPx())
                        lineTo(pt.x + 14.dp.toPx(), pt.y + 14.dp.toPx())
                        close()
                    }
                    drawPath(
                        path = arrowPath,
                        color = Color.Black,
                        style = Stroke(width = 2.8.dp.toPx(), join = StrokeJoin.Round),
                    )
                    drawPath(
                        path = arrowPath,
                        color = if (isDragLocked) ElectricCyan else Color.White,
                    )
                } else {
                    lastTouchScreenPos?.let { pt ->
                        drawCircle(
                            color = ElectricCyan.copy(alpha = 0.25f),
                            radius = 24.dp.toPx(),
                            center = pt,
                        )
                        drawCircle(
                            color = ElectricCyan,
                            radius = 14.dp.toPx(),
                            center = pt,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }
        }

        // Top Collapsible & Pinnable RealVNC-style Toolbar
        if (toolbarExpanded) {
            RemoteExpandedToolbar(
                targetId = targetId,
                targetAlias = targetAlias,
                telemetry = telemetry,
                inputMode = inputMode,
                keyboardVisible = keyboardVisible,
                showMouseOverlay = showMouseOverlay,
                toolbarPinned = toolbarPinned,
                zoom = zoom,
                onInteract = { toolbarInteractionTick++ },
                onShowFingerprint = { showFingerprintDialog = true },
                onToggleMode = {
                    toolbarInteractionTick++
                    userOverrodeInputMode = true
                    inputMode = if (inputMode == RemoteInputMode.TOUCHPAD) {
                        isDragLocked = false
                        RemoteInputMode.TOUCHSCREEN
                    } else {
                        isDragLocked = false
                        RemoteInputMode.TOUCHPAD
                    }
                },
                onToggleKeyboard = {
                    toolbarInteractionTick++
                    if (keyboardVisible) {
                        imeView.hideSoftKeyboard()
                        keyboardVisible = false
                        modifiers = ModifierKeysState()
                    } else {
                        keyboardVisible = true
                        imeView.showSoftKeyboard()
                    }
                },
                onToggleMouseOverlay = {
                    toolbarInteractionTick++
                    showMouseOverlay = !showMouseOverlay
                },
                onResetZoom = {
                    toolbarInteractionTick++
                    zoom = 1f
                    panOffset = Offset.Zero
                },
                onOpenPasteDialog = {
                    toolbarInteractionTick++
                    showTextDialog = true
                },
                onOpenQualityDialog = {
                    toolbarInteractionTick++
                    showQualityDialog = true
                },
                onOpenNavDialog = {
                    toolbarInteractionTick++
                    showNavDialog = true
                },
                onOpenHelpDialog = {
                    toolbarInteractionTick++
                    showHelpDialog = true
                },
                onTogglePin = {
                    toolbarInteractionTick++
                    toolbarPinned = !toolbarPinned
                },
                onCollapse = { toolbarExpanded = false },
                onRequestExit = { showExitConfirm = true },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        } else {
            // Compact floating pill when toolbar is collapsed
            Surface(
                onClick = {
                    toolbarExpanded = true
                    toolbarInteractionTick++
                },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 6.dp)
                    .testTag("collapsed_toolbar_pill"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = stringResource(R.string.cd_e2ee_badge),
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(14.dp),
                    )
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = stringResource(R.string.cd_toggle_hud),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (inputMode == RemoteInputMode.TOUCHPAD) "Touchpad" else "Sentuh",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (zoom > 1.05f) {
                        Text(
                            text = "${"%.1f".format(zoom)}x",
                            style = MonoTelemetryStyle.copy(fontSize = 11.sp, color = MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }

        // Optional Mouse Overlay (hidden when IME is open)
        AnimatedVisibility(
            visible = showMouseOverlay && !keyboardVisible,
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            MouseOverlayButtons(
                onMouseDown = { btn ->
                    onSendMouseMove(cursorNorm.x, cursorNorm.y)
                    onSendMouseButton(cursorNorm.x, cursorNorm.y, btn, RemoteMouseAction.DOWN)
                },
                onMouseUp = { btn ->
                    onSendMouseMove(cursorNorm.x, cursorNorm.y)
                    onSendMouseButton(cursorNorm.x, cursorNorm.y, btn, RemoteMouseAction.UP)
                },
                onMouseClick = { btn ->
                    onSendMouseMove(cursorNorm.x, cursorNorm.y)
                    onSendMouseButton(cursorNorm.x, cursorNorm.y, btn, RemoteMouseAction.CLICK)
                },
                onScroll = { dx, dy ->
                    onSendMouseScroll(cursorNorm.x, cursorNorm.y, dx, dy)
                },
            )
        }

        // Zoom Indicator Pill at Bottom-Center / Bottom-Start
        if (zoom > 1.05f) {
            Surface(
                onClick = {
                    zoom = 1f
                    panOffset = Offset.Zero
                },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .align(if (showMouseOverlay && !keyboardVisible) Alignment.BottomStart else Alignment.BottomCenter)
                    .then(
                        if (keyboardVisible) {
                            Modifier
                                .navigationBarsPadding()
                                .imePadding()
                                .padding(bottom = 54.dp, start = 12.dp, end = 12.dp)
                        } else {
                            Modifier
                                .navigationBarsPadding()
                                .padding(12.dp)
                        },
                    )
                    .testTag("zoom_reset_pill"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ZoomOutMap,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = "${"%.1f".format(zoom)}x • Reset",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // PC Extension Key Bar Docked Directly Above Soft Keyboard
        if (keyboardVisible) {
            ExtensionKeyBar(
                modifiers = modifiers,
                onToggleModifier = { toggleModifier(it) },
                onSendSpecialKey = { key -> handleLiveSpecialKey(key) },
                onSendShortcut = { key, c, a, s, m -> onSendKeyEvent(key, c, a, s, m) },
                onHideKeyboard = {
                    imeView.hideSoftKeyboard()
                    keyboardVisible = false
                    modifiers = ModifierKeysState()
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showFingerprintDialog) {
        AlertDialog(
            onDismissRequest = { showFingerprintDialog = false },
            icon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
            title = { Text("Verifikasi Kunci E2EE") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Pastikan Sidik Jari Keamanan di bawah ini sama persis dengan yang ditampilkan di perangkat Host:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
                    ) {
                        Text(
                            text = telemetry.safetyFingerprint.ifBlank { "Menunggu sidik jari…" },
                            style = MonoTelemetryStyle.copy(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary,
                            ),
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Text(
                        text = "Protokol: WebRTC DTLS-SRTP + ECDH P-256 + AES-256-GCM",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showFingerprintDialog = false }) {
                    Text("Tutup")
                }
            },
        )
    }

    if (showTextDialog) {
        var textPayload by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("Tempel / Kirim Teks Terenkripsi") },
            text = {
                OutlinedTextField(
                    value = textPayload,
                    onValueChange = { textPayload = it },
                    label = { Text("Teks atau kata sandi untuk ditempel di target") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("remote_text_input_field"),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSendTextInput(textPayload)
                        showTextDialog = false
                    },
                    enabled = textPayload.isNotEmpty(),
                    modifier = Modifier.testTag("send_remote_text_button"),
                ) {
                    Text("Kirim")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showTextDialog = false }) {
                    Text("Batal")
                }
            },
        )
    }

    if (showQualityDialog) {
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = { Text("Preset Kualitas & Latensi") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StreamQualityPreset.entries.forEach { preset ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onChangeQuality(preset)
                                    showQualityDialog = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = preset == currentQuality,
                                onClick = {
                                    onChangeQuality(preset)
                                    showQualityDialog = false
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(preset.label, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    preset.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                OutlinedButton(onClick = { showQualityDialog = false }) {
                    Text("Tutup")
                }
            },
        )
    }

    if (showNavDialog) {
        RemoteNavigationDialog(
            onAction = { action ->
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSendSystemAction(action)
                showNavDialog = false
            },
            onDismiss = { showNavDialog = false },
        )
    }

    if (showHelpDialog) {
        RemoteHelpDialog(onDismiss = { showHelpDialog = false })
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("Akhiri Sesi Remote?") },
            text = { Text("Koneksi P2P terenkripsi ke $targetAlias akan diputuskan.") },
            confirmButton = {
                Button(
                    onClick = {
                        showExitConfirm = false
                        onEndSession()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_end_session_button"),
                ) {
                    Text("Akhiri Sesi")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showExitConfirm = false }) {
                    Text("Lanjutkan")
                }
            },
        )
    }
}

@Composable
private fun RemoteExpandedToolbar(
    targetId: String,
    targetAlias: String,
    telemetry: ConnectionTelemetry,
    inputMode: RemoteInputMode,
    keyboardVisible: Boolean,
    showMouseOverlay: Boolean,
    toolbarPinned: Boolean,
    zoom: Float,
    onInteract: () -> Unit,
    onShowFingerprint: () -> Unit,
    onToggleMode: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onToggleMouseOverlay: () -> Unit,
    onResetZoom: () -> Unit,
    onOpenPasteDialog: () -> Unit,
    onOpenQualityDialog: () -> Unit,
    onOpenNavDialog: () -> Unit,
    onOpenHelpDialog: () -> Unit,
    onTogglePin: () -> Unit,
    onCollapse: () -> Unit,
    onRequestExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .clickable {
                                onInteract()
                                onShowFingerprint()
                            }
                            .testTag("hud_e2ee_fingerprint_button"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = stringResource(R.string.cd_e2ee_badge),
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                text = "E2EE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }

                    Column {
                        Text(
                            text = targetAlias,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        Text(
                            text = "${formatDeviceId(targetId)} • ${telemetry.rttMs}ms • ${telemetry.fps}fps • ${telemetry.routeType.label}",
                            style = MonoTelemetryStyle.copy(
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            maxLines = 1,
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onTogglePin,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = if (toolbarPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = stringResource(R.string.cd_remote_pin_toolbar),
                            tint = if (toolbarPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = onCollapse,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("toggle_hud_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ExpandLess,
                            contentDescription = stringResource(R.string.cd_toggle_hud),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                    Surface(
                        onClick = onRequestExit,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("dock_end_session_button"),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.CallEnd,
                                contentDescription = stringResource(R.string.cd_remote_disconnect),
                                tint = Color.White,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarChipButton(
                    icon = if (inputMode == RemoteInputMode.TOUCHPAD) Icons.Filled.Mouse else Icons.Filled.TouchApp,
                    label = if (inputMode == RemoteInputMode.TOUCHPAD) "Touchpad" else "Sentuh",
                    contentDesc = stringResource(R.string.cd_remote_mode_toggle),
                    selected = inputMode == RemoteInputMode.TOUCHPAD,
                    tag = "toolbar_mode_button",
                    onClick = onToggleMode,
                )
                ToolbarChipButton(
                    icon = Icons.Filled.Keyboard,
                    label = "Keyboard",
                    contentDesc = stringResource(R.string.cd_remote_keyboard),
                    selected = keyboardVisible,
                    tag = "dock_live_keyboard_button",
                    onClick = onToggleKeyboard,
                )
                ToolbarChipButton(
                    icon = Icons.Filled.Mouse,
                    label = "Mouse",
                    contentDesc = stringResource(R.string.cd_remote_mouse_overlay),
                    selected = showMouseOverlay,
                    tag = "toolbar_mouse_overlay_button",
                    onClick = onToggleMouseOverlay,
                )
                if (zoom > 1.05f) {
                    ToolbarChipButton(
                        icon = Icons.Filled.ZoomOutMap,
                        label = "Reset 1x",
                        contentDesc = "Reset zoom",
                        selected = true,
                        tag = "toolbar_reset_zoom_button",
                        onClick = onResetZoom,
                    )
                }
                ToolbarChipButton(
                    icon = Icons.Filled.ContentPaste,
                    label = "Tempel",
                    contentDesc = stringResource(R.string.cd_remote_keyboard),
                    selected = false,
                    tag = "dock_text_button",
                    onClick = onOpenPasteDialog,
                )
                ToolbarChipButton(
                    icon = Icons.Filled.Speed,
                    label = "Kualitas",
                    contentDesc = stringResource(R.string.cd_remote_quality),
                    selected = false,
                    tag = "dock_quality_button",
                    onClick = onOpenQualityDialog,
                )
                ToolbarChipButton(
                    icon = Icons.Filled.Apps,
                    label = "Navigasi",
                    contentDesc = stringResource(R.string.cd_remote_home),
                    selected = false,
                    tag = "toolbar_nav_button",
                    onClick = onOpenNavDialog,
                )
                ToolbarChipButton(
                    icon = Icons.AutoMirrored.Filled.HelpOutline,
                    label = "Panduan",
                    contentDesc = stringResource(R.string.cd_remote_help),
                    selected = false,
                    tag = "toolbar_help_button",
                    onClick = onOpenHelpDialog,
                )
            }
        }
    }
}

@Composable
private fun ToolbarChipButton(
    icon: ImageVector,
    label: String,
    contentDesc: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .height(34.dp)
            .testTag(tag),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDesc,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun RemoteNavigationDialog(
    onAction: (RemoteSystemAction) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aksi Sistem & Navigasi") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NavDialogRow(Icons.AutoMirrored.Filled.ArrowBack, "Kembali (Alt+← / Back)", "dock_back_button") {
                    onAction(RemoteSystemAction.BACK)
                }
                NavDialogRow(Icons.Filled.Home, "Start / Beranda (Win / Home)", "dock_home_button") {
                    onAction(RemoteSystemAction.HOME)
                }
                NavDialogRow(Icons.Filled.Layers, "Task View / Terbaru (Win+Tab)", "dock_recents_button") {
                    onAction(RemoteSystemAction.RECENTS)
                }
                NavDialogRow(Icons.Filled.Notifications, "Notifikasi (Win+N)", "dock_notifications_button") {
                    onAction(RemoteSystemAction.NOTIFICATIONS)
                }
                NavDialogRow(Icons.Filled.PowerSettingsNew, "Kunci Layar Target", "dock_lock_button") {
                    onAction(RemoteSystemAction.LOCK_SCREEN)
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Tutup")
            }
        },
    )
}

@Composable
private fun NavDialogRow(
    icon: ImageVector,
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RemoteHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Panduan Kontrol Touchpad") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HelpRow("Geser 1 jari", "Gerakkan kursor mouse (nyaman dari area mana saja)")
                HelpRow("Ketuk 1 jari", "Klik kiri pada posisi kursor")
                HelpRow("Ketuk 2x cepat", "Double-click pada posisi kursor")
                HelpRow("Ketuk 2x tahan & geser", "Drag jendela / pilih teks (klik kiri ditahan)")
                HelpRow("Ketuk 2 jari", "Klik kanan (menu konteks)")
                HelpRow("Ketuk 3 jari", "Klik tengah")
                HelpRow("Geser 2 jari sejajar", "Gulir halaman atas/bawah & kiri/kanan")
                HelpRow("Cubit 2 jari (Pinch)", "Zoom in / zoom out tampilan desktop (1x–5x)")
                HelpRow("Keyboard + Bar Khusus", "Ketik langsung + Esc, Tab, Ctrl, Alt, Win, F1–F12")
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Mengerti")
            }
        },
    )
}

@Composable
private fun HelpRow(gesture: String, description: String) {
    Column {
        Text(
            text = gesture,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun computeCentroid(points: List<Offset>): Offset {
    if (points.isEmpty()) return Offset.Zero
    var sumX = 0f
    var sumY = 0f
    for (p in points) {
        sumX += p.x
        sumY += p.y
    }
    return Offset(sumX / points.size, sumY / points.size)
}

private fun computeSpan(points: List<Offset>, centroid: Offset): Float {
    if (points.size < 2) return 0f
    var sumDist = 0f
    for (p in points) {
        sumDist += (p - centroid).getDistance()
    }
    return (sumDist / points.size) * 2f
}
