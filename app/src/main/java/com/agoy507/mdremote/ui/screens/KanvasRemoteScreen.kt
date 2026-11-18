package com.agoy507.mdremote.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
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
import com.agoy507.mdremote.model.RemoteSystemAction
import com.agoy507.mdremote.model.StreamQualityPreset
import com.agoy507.mdremote.model.formatDeviceId
import com.agoy507.mdremote.ui.theme.ElectricCyan
import com.agoy507.mdremote.ui.theme.MonoTelemetryStyle
import com.agoy507.mdremote.webrtc.WebRtcRemoteManager
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun KanvasRemoteScreen(
    targetId: String,
    targetAlias: String,
    telemetry: ConnectionTelemetry,
    currentQuality: StreamQualityPreset,
    webRtcManager: WebRtcRemoteManager,
    onSendTap: (xNorm: Float, yNorm: Float, isLongPress: Boolean) -> Unit,
    onSendSwipe: (startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) -> Unit,
    onSendSystemAction: (RemoteSystemAction) -> Unit,
    onSendTextInput: (String) -> Unit,
    onChangeQuality: (StreamQualityPreset) -> Unit,
    onEndSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }
    var lastTouchOffset by remember { mutableStateOf<Offset?>(null) }
    var hudExpanded by remember { mutableStateOf(true) }
    var showFingerprintDialog by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }

    BackHandler {
        showExitConfirm = true
    }

    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(webRtcManager.eglBase.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
            setMirror(false)
        }
    }

    DisposableEffect(renderer) {
        webRtcManager.attachVideoRenderer(renderer)
        onDispose {
            webRtcManager.detachVideoRenderer(renderer)
            runCatching { renderer.release() }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // WebRTC Remote Video Stream + Touch Injection Layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(canvasSize) {
                    detectTapGestures(
                        onTap = { pos ->
                            lastTouchOffset = pos
                            val xNorm = (pos.x / canvasSize.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val yNorm = (pos.y / canvasSize.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSendTap(xNorm, yNorm, false)
                        },
                        onLongPress = { pos ->
                            lastTouchOffset = pos
                            val xNorm = (pos.x / canvasSize.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val yNorm = (pos.y / canvasSize.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSendTap(xNorm, yNorm, true)
                        },
                    )
                }
                .pointerInput(canvasSize) {
                    var dragStart = Offset.Zero
                    var dragEnd = Offset.Zero
                    var dragStartTimeMs = 0L
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragStart = offset
                            dragEnd = offset
                            dragStartTimeMs = System.currentTimeMillis()
                            lastTouchOffset = offset
                        },
                        onDrag = { change, _ ->
                            dragEnd = change.position
                            lastTouchOffset = dragEnd
                        },
                        onDragEnd = {
                            val w = canvasSize.width.coerceAtLeast(1).toFloat()
                            val h = canvasSize.height.coerceAtLeast(1).toFloat()
                            val duration = (System.currentTimeMillis() - dragStartTimeMs).coerceIn(80L, 1200L)
                            onSendSwipe(
                                (dragStart.x / w).coerceIn(0f, 1f),
                                (dragStart.y / h).coerceIn(0f, 1f),
                                (dragEnd.x / w).coerceIn(0f, 1f),
                                (dragEnd.y / h).coerceIn(0f, 1f),
                                duration,
                            )
                        },
                    )
                }
                .testTag("remote_video_canvas"),
        ) {
            AndroidView(
                factory = { renderer },
                modifier = Modifier.fillMaxSize(),
            )

            // Visual Touch Cursor Feedback
            Canvas(modifier = Modifier.fillMaxSize()) {
                lastTouchOffset?.let { pt ->
                    drawCircle(
                        color = ElectricCyan.copy(alpha = 0.25f),
                        radius = 28.dp.toPx(),
                        center = pt,
                    )
                    drawCircle(
                        color = ElectricCyan,
                        radius = 18.dp.toPx(),
                        center = pt,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
        }

        // Top Telemetry HUD
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier
                                    .clickable { showFingerprintDialog = true }
                                    .testTag("hud_e2ee_fingerprint_button"),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Lock,
                                        contentDescription = stringResource(R.string.cd_e2ee_badge),
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(13.dp),
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
                                )
                                Text(
                                    text = formatDeviceId(targetId),
                                    style = MonoTelemetryStyle.copy(
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                            }
                        }

                        IconButton(
                            onClick = { hudExpanded = !hudExpanded },
                            modifier = Modifier.testTag("toggle_hud_button"),
                        ) {
                            Icon(
                                imageVector = if (hudExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = stringResource(R.string.cd_toggle_hud),
                            )
                        }
                    }

                    AnimatedVisibility(visible = hudExpanded) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TelemetryMetricChip("RTT", "${telemetry.rttMs} ms", MaterialTheme.colorScheme.primary)
                            TelemetryMetricChip("FPS", "${telemetry.fps}", MaterialTheme.colorScheme.secondary)
                            TelemetryMetricChip("BITRATE", "${telemetry.bitrateKbps} kbps", MaterialTheme.colorScheme.onSurface)
                            TelemetryMetricChip("RUTE", telemetry.routeType.label, MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
        }

        // Bottom Quick Control Dock
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DockActionButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    label = "Kembali",
                    contentDesc = stringResource(R.string.cd_remote_back),
                    tag = "dock_back_button",
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSendSystemAction(RemoteSystemAction.BACK)
                    },
                )
                DockActionButton(
                    icon = Icons.Filled.Home,
                    label = "Beranda",
                    contentDesc = stringResource(R.string.cd_remote_home),
                    tag = "dock_home_button",
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSendSystemAction(RemoteSystemAction.HOME)
                    },
                )
                DockActionButton(
                    icon = Icons.Filled.Layers,
                    label = "Terbaru",
                    contentDesc = stringResource(R.string.cd_remote_recents),
                    tag = "dock_recents_button",
                    onClick = { onSendSystemAction(RemoteSystemAction.RECENTS) },
                )
                DockActionButton(
                    icon = Icons.Filled.Notifications,
                    label = "Notif",
                    contentDesc = stringResource(R.string.cd_remote_notifications),
                    tag = "dock_notifications_button",
                    onClick = { onSendSystemAction(RemoteSystemAction.NOTIFICATIONS) },
                )
                DockActionButton(
                    icon = Icons.Filled.Keyboard,
                    label = "Teks",
                    contentDesc = stringResource(R.string.cd_remote_keyboard),
                    tag = "dock_text_button",
                    onClick = { showTextDialog = true },
                )
                DockActionButton(
                    icon = Icons.Filled.Speed,
                    label = "Kualitas",
                    contentDesc = stringResource(R.string.cd_remote_quality),
                    tag = "dock_quality_button",
                    onClick = { showQualityDialog = true },
                )
                DockActionButton(
                    icon = Icons.Filled.PowerSettingsNew,
                    label = "Kunci",
                    contentDesc = stringResource(R.string.cd_remote_lock),
                    tag = "dock_lock_button",
                    onClick = { onSendSystemAction(RemoteSystemAction.LOCK_SCREEN) },
                )
                Spacer(modifier = Modifier.width(4.dp))
                Surface(
                    onClick = { showExitConfirm = true },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("dock_end_session_button"),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.CallEnd,
                            contentDescription = stringResource(R.string.cd_remote_disconnect),
                            tint = Color.White,
                        )
                    }
                }
            }
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
            title = { Text("Kirim Teks Terenkripsi") },
            text = {
                OutlinedTextField(
                    value = textPayload,
                    onValueChange = { textPayload = it },
                    label = { Text("Teks untuk kolom aktif di target") },
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
private fun TelemetryMetricChip(
    label: String,
    value: String,
    valueColor: Color,
) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MonoTelemetryStyle.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = valueColor,
            ),
        )
    }
}

@Composable
private fun DockActionButton(
    icon: ImageVector,
    label: String,
    contentDesc: String,
    tag: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .height(48.dp)
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
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
