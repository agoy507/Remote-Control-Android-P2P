package com.agoy507.mdremote.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agoy507.mdremote.R
import com.agoy507.mdremote.model.IncomingConnectionRequest
import com.agoy507.mdremote.model.SignalingStatus
import com.agoy507.mdremote.model.formatDeviceId
import com.agoy507.mdremote.ui.theme.AlertCoral
import com.agoy507.mdremote.ui.theme.DeepAmberContainer
import com.agoy507.mdremote.ui.theme.DeepCoralContainer
import com.agoy507.mdremote.ui.theme.DeepEmeraldContainer
import com.agoy507.mdremote.ui.theme.EmeraldSoftText
import com.agoy507.mdremote.ui.theme.MonoTelemetryStyle
import com.agoy507.mdremote.ui.theme.MutedSteelText
import com.agoy507.mdremote.ui.theme.NeonEmerald
import com.agoy507.mdremote.ui.theme.TacticalAmber
import com.agoy507.mdremote.ui.theme.TacticalOutline
import com.agoy507.mdremote.ui.theme.TacticalOutlineSubtle

@Composable
fun TacticalCard(
    modifier: Modifier = Modifier,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content,
        )
    }
}

@Composable
fun SignalingStatusPill(
    status: SignalingStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = when (status) {
        is SignalingStatus.Registered -> {
            val countText = if (status.iceCount > 0) " • ${status.iceCount} ICE" else ""
            StatusTheme(
                dot = NeonEmerald,
                text = EmeraldSoftText,
                label = "Online$countText",
                bg = DeepEmeraldContainer.copy(alpha = 0.7f),
                border = NeonEmerald.copy(alpha = 0.4f),
            )
        }
        is SignalingStatus.Connecting -> {
            StatusTheme(
                dot = TacticalAmber,
                text = Color(0xFFFDE68A),
                label = "Menghubungkan…",
                bg = DeepAmberContainer.copy(alpha = 0.7f),
                border = TacticalAmber.copy(alpha = 0.4f),
            )
        }
        is SignalingStatus.Error -> {
            StatusTheme(
                dot = AlertCoral,
                text = Color(0xFFFCA5A5),
                label = "Terputus",
                bg = DeepCoralContainer.copy(alpha = 0.7f),
                border = AlertCoral.copy(alpha = 0.4f),
            )
        }
        is SignalingStatus.Disconnected -> {
            StatusTheme(
                dot = MutedSteelText,
                text = MutedSteelText,
                label = "Offline",
                bg = TacticalOutlineSubtle,
                border = TacticalOutline,
            )
        }
    }

    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseAlpha",
    )

    Surface(
        onClick = onClick,
        modifier = modifier.testTag("signaling_status_pill"),
        shape = RoundedCornerShape(100.dp),
        color = theme.bg,
        border = BorderStroke(1.dp, theme.border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .alpha(if (status is SignalingStatus.Registered || status is SignalingStatus.Connecting) alpha else 1f)
                    .clip(CircleShape)
                    .background(theme.dot),
            )
            Text(
                text = theme.label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = theme.text,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class StatusTheme(
    val dot: Color,
    val text: Color,
    val label: String,
    val bg: Color,
    val border: Color,
)

@Composable
fun E2eeShieldBadge(
    label: String = "E2EE AES-256-GCM",
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = stringResource(R.string.cd_e2ee_badge),
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
fun AccessibilityDisclosureDialog(
    onConfirmOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.TouchApp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = "Izin Layanan Aksesibilitas",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "KendaliP2P memerlukan Layanan Aksesibilitas hanya untuk meneruskan sentuhan, geser layar, input teks, dan tombol navigasi dari pengendali jarak jauh saat sesi Host aktif.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Aplikasi tidak pernah membaca kata sandi atau mengirim data layar ke server tanpa enkripsi End-to-End.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmOpenSettings,
                modifier = Modifier.testTag("confirm_open_accessibility_settings"),
            ) {
                Text("Buka Pengaturan")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Batal")
            }
        },
    )
}

@Composable
fun IncomingApprovalDialog(
    request: IncomingConnectionRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onReject,
        icon = {
            Icon(
                imageVector = Icons.Filled.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = "Permintaan Kendali Masuk",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Perangkat berikut meminta akses melihat dan mengontrol layar Anda:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = request.fromAlias,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "ID: ${formatDeviceId(request.fromDeviceId)}",
                            style = MonoTelemetryStyle.copy(color = MaterialTheme.colorScheme.primary),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Sidik Jari Keamanan E2EE:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = request.safetyFingerprint,
                            style = MonoTelemetryStyle.copy(color = MaterialTheme.colorScheme.secondary),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onApprove,
                modifier = Modifier.testTag("approve_incoming_session_button"),
            ) {
                Text("Izinkan Sesi")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onReject,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("reject_incoming_session_button"),
            ) {
                Text("Tolak")
            }
        },
    )
}
