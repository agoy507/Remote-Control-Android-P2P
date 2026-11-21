package com.agoy507.mdremote.ui.components

import android.content.Context
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ModifierKeyState {
    OFF,
    LATCHED,
    LOCKED,
    ;

    fun next(): ModifierKeyState = when (this) {
        OFF -> LATCHED
        LATCHED -> LOCKED
        LOCKED -> OFF
    }

    fun isActive(): Boolean = this != OFF
}

data class ModifierKeysState(
    val ctrl: ModifierKeyState = ModifierKeyState.OFF,
    val alt: ModifierKeyState = ModifierKeyState.OFF,
    val shift: ModifierKeyState = ModifierKeyState.OFF,
    val meta: ModifierKeyState = ModifierKeyState.OFF,
) {
    fun hasAnyActive(): Boolean =
        ctrl.isActive() || alt.isActive() || shift.isActive() || meta.isActive()

    fun releaseLatched(): ModifierKeysState = ModifierKeysState(
        ctrl = if (ctrl == ModifierKeyState.LATCHED) ModifierKeyState.OFF else ctrl,
        alt = if (alt == ModifierKeyState.LATCHED) ModifierKeyState.OFF else alt,
        shift = if (shift == ModifierKeyState.LATCHED) ModifierKeyState.OFF else shift,
        meta = if (meta == ModifierKeyState.LATCHED) ModifierKeyState.OFF else meta,
    )
}

/**
 * Hidden 1dp focusable editor bridge (modeled after RealVNC's CapturingEditText) that allows
 * the Android soft keyboard and hardware keyboards to stream real-time keystrokes, Backspace,
 * Enter, and arrow keys directly to the remote PC.
 */
class RemoteImeCaptureView(context: Context) : View(context) {

    var onCommitText: ((String) -> Unit)? = null
    var onSpecialKey: ((String) -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType =
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions =
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_ACTION_NONE

        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val payload = text?.toString().orEmpty()
                if (payload.isNotEmpty()) {
                    dispatchCommittedText(payload)
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtLeast(1)) {
                    onSpecialKey?.invoke("BACKSPACE")
                }
                return true
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtLeast(1)) {
                    onSpecialKey?.invoke("BACKSPACE")
                }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                onSpecialKey?.invoke("ENTER")
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (handleAndroidKeyEvent(event)) return true
                } else if (event.action == KeyEvent.ACTION_MULTIPLE && event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                    event.characters?.takeIf { it.isNotEmpty() }?.let { dispatchCommittedText(it) }
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleAndroidKeyEvent(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    fun showSoftKeyboard() {
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        post {
            requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun hideSoftKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
        clearFocus()
    }

    private fun dispatchCommittedText(raw: String) {
        val buffer = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\r' || ch == '\n') {
                if (buffer.isNotEmpty()) {
                    onCommitText?.invoke(buffer.toString())
                    buffer.clear()
                }
                onSpecialKey?.invoke("ENTER")
                if (ch == '\r' && i + 1 < raw.length && raw[i + 1] == '\n') {
                    i++
                }
            } else {
                buffer.append(ch)
            }
            i++
        }
        if (buffer.isNotEmpty()) {
            onCommitText?.invoke(buffer.toString())
        }
    }

    private fun handleAndroidKeyEvent(event: KeyEvent): Boolean {
        val special = when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> "BACKSPACE"
            KeyEvent.KEYCODE_FORWARD_DEL -> "DELETE"
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "ENTER"
            KeyEvent.KEYCODE_TAB -> "TAB"
            KeyEvent.KEYCODE_ESCAPE -> "ESCAPE"
            KeyEvent.KEYCODE_DPAD_UP -> "UP"
            KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
            KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
            KeyEvent.KEYCODE_MOVE_HOME -> "HOME"
            KeyEvent.KEYCODE_MOVE_END -> "END"
            KeyEvent.KEYCODE_PAGE_UP -> "PAGE_UP"
            KeyEvent.KEYCODE_PAGE_DOWN -> "PAGE_DOWN"
            KeyEvent.KEYCODE_INSERT -> "INSERT"
            KeyEvent.KEYCODE_SYSRQ -> "PRINT_SCREEN"
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
                "F${event.keyCode - KeyEvent.KEYCODE_F1 + 1}"
            else -> null
        }
        if (special != null) {
            onSpecialKey?.invoke(special)
            return true
        }

        if ((event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) &&
            event.keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z
        ) {
            val letter = ('A' + (event.keyCode - KeyEvent.KEYCODE_A)).toString()
            onCommitText?.invoke(letter)
            return true
        }

        val unicode = event.unicodeChar
        if (unicode != 0 && (unicode and KeyCharacterMap.COMBINING_ACCENT) == 0) {
            dispatchCommittedText(String(Character.toChars(unicode)))
            return true
        }
        return false
    }
}

@Composable
fun ExtensionKeyBar(
    modifiers: ModifierKeysState,
    onToggleModifier: (String) -> Unit,
    onSendSpecialKey: (String) -> Unit,
    onSendShortcut: (key: String, ctrl: Boolean, alt: Boolean, shift: Boolean, meta: Boolean) -> Unit,
    onHideKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .height(46.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(
                onClick = onHideKeyboard,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardHide,
                    contentDescription = "Tutup keyboard",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }

            ModifierChip("Ctrl", modifiers.ctrl) { onToggleModifier("CTRL") }
            ModifierChip("Alt", modifiers.alt) { onToggleModifier("ALT") }
            ModifierChip("Shift", modifiers.shift) { onToggleModifier("SHIFT") }
            ModifierChip("Win", modifiers.meta) { onToggleModifier("META") }

            KeyChip("Esc") { onSendSpecialKey("ESCAPE") }
            KeyChip("Tab") { onSendSpecialKey("TAB") }
            KeyChip("Bksp") { onSendSpecialKey("BACKSPACE") }
            KeyChip("Del") { onSendSpecialKey("DELETE") }
            KeyChip("Enter") { onSendSpecialKey("ENTER") }

            KeyChip("←") { onSendSpecialKey("LEFT") }
            KeyChip("↓") { onSendSpecialKey("DOWN") }
            KeyChip("↑") { onSendSpecialKey("UP") }
            KeyChip("→") { onSendSpecialKey("RIGHT") }

            KeyChip("Alt+Tab") { onSendShortcut("TAB", false, true, false, false) }
            KeyChip("Ctrl+C") { onSendShortcut("C", true, false, false, false) }
            KeyChip("Ctrl+V") { onSendShortcut("V", true, false, false, false) }
            KeyChip("Ctrl+A") { onSendShortcut("A", true, false, false, false) }
            KeyChip("Ctrl+Z") { onSendShortcut("Z", true, false, false, false) }

            KeyChip("Home") { onSendSpecialKey("HOME") }
            KeyChip("End") { onSendSpecialKey("END") }
            KeyChip("PgUp") { onSendSpecialKey("PAGE_UP") }
            KeyChip("PgDn") { onSendSpecialKey("PAGE_DOWN") }
            KeyChip("PrtSc") { onSendSpecialKey("PRINT_SCREEN") }

            for (fn in 1..12) {
                val fKey = "F$fn"
                KeyChip(fKey) { onSendSpecialKey(fKey) }
            }
        }
    }
}

@Composable
private fun ModifierChip(
    label: String,
    state: ModifierKeyState,
    onClick: () -> Unit,
) {
    val bgColor = when (state) {
        ModifierKeyState.OFF -> MaterialTheme.colorScheme.surfaceVariant
        ModifierKeyState.LATCHED -> MaterialTheme.colorScheme.secondaryContainer
        ModifierKeyState.LOCKED -> MaterialTheme.colorScheme.primary
    }
    val textColor = when (state) {
        ModifierKeyState.OFF -> MaterialTheme.colorScheme.onSurface
        ModifierKeyState.LATCHED -> MaterialTheme.colorScheme.onSecondaryContainer
        ModifierKeyState.LOCKED -> MaterialTheme.colorScheme.onPrimary
    }
    val borderColor = when (state) {
        ModifierKeyState.OFF -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        ModifierKeyState.LATCHED -> MaterialTheme.colorScheme.secondary
        ModifierKeyState.LOCKED -> MaterialTheme.colorScheme.primary
    }
    val suffix = when (state) {
        ModifierKeyState.OFF -> ""
        ModifierKeyState.LATCHED -> "•"
        ModifierKeyState.LOCKED -> "🔒"
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .height(32.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$label$suffix",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                fontWeight = if (state.isActive()) FontWeight.Bold else FontWeight.Medium,
                color = textColor,
            )
        }
    }
}

@Composable
private fun KeyChip(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        modifier = Modifier
            .height(32.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
