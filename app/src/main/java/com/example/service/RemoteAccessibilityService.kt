package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.model.RemoteSystemAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AccessibilityBridge {
    private var activeService: RemoteAccessibilityService? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    internal fun attach(service: RemoteAccessibilityService) {
        activeService = service
        _isConnected.value = true
    }

    internal fun detach(service: RemoteAccessibilityService) {
        if (activeService === service) {
            activeService = null
            _isConnected.value = false
        }
    }

    fun isAccessibilityEnabledInSettings(context: Context): Boolean {
        val expectedComponent = ComponentName(context, RemoteAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val component = ComponentName.unflattenFromString(splitter.next())
            if (component == expectedComponent) return true
        }
        return _isConnected.value
    }

    fun dispatchTap(xNorm: Float, yNorm: Float, isLongPress: Boolean): Boolean =
        activeService?.performNormalizedTap(xNorm, yNorm, isLongPress) ?: false

    fun dispatchSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
    ): Boolean = activeService?.performNormalizedSwipe(startX, startY, endX, endY, durationMs) ?: false

    fun dispatchSystemAction(action: RemoteSystemAction): Boolean =
        activeService?.performRemoteSystemAction(action) ?: false

    fun dispatchTextInput(text: String): Boolean =
        activeService?.performRemoteTextInput(text) ?: false
}

class RemoteAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityBridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events not buffered; gestures are injected on demand from encrypted P2P commands.
    }

    override fun onInterrupt() {
        // No continuous speech/feedback resources held.
    }

    override fun onDestroy() {
        AccessibilityBridge.detach(this)
        super.onDestroy()
    }

    fun performNormalizedTap(xNorm: Float, yNorm: Float, isLongPress: Boolean): Boolean {
        val (w, h) = displayDimensions()
        val x = (xNorm.coerceIn(0f, 1f) * w).coerceIn(1f, (w - 1).toFloat())
        val y = (yNorm.coerceIn(0f, 1f) * h).coerceIn(1f, (h - 1).toFloat())
        val path = Path().apply { moveTo(x, y) }
        val duration = if (isLongPress) 600L else 45L
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun performNormalizedSwipe(
        startXNorm: Float,
        startYNorm: Float,
        endXNorm: Float,
        endYNorm: Float,
        durationMs: Long,
    ): Boolean {
        val (w, h) = displayDimensions()
        val sx = (startXNorm.coerceIn(0f, 1f) * w).coerceIn(1f, (w - 1).toFloat())
        val sy = (startYNorm.coerceIn(0f, 1f) * h).coerceIn(1f, (h - 1).toFloat())
        val ex = (endXNorm.coerceIn(0f, 1f) * w).coerceIn(1f, (w - 1).toFloat())
        val ey = (endYNorm.coerceIn(0f, 1f) * h).coerceIn(1f, (h - 1).toFloat())
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        val clampedDuration = durationMs.coerceIn(50L, 2000L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, clampedDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun performRemoteSystemAction(action: RemoteSystemAction): Boolean {
        return when (action) {
            RemoteSystemAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            RemoteSystemAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            RemoteSystemAction.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            RemoteSystemAction.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            RemoteSystemAction.LOCK_SCREEN -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                } else {
                    false
                }
            }
        }
    }

    fun performRemoteTextInput(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditableNode(root) ?: return false
        val existing = focused.text?.toString().orEmpty()
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                existing + text,
            )
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = findFirstEditableNode(child)
            if (match != null) return match
        }
        return null
    }

    private fun displayDimensions(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        return metrics.widthPixels.coerceAtLeast(1) to metrics.heightPixels.coerceAtLeast(1)
    }
}
