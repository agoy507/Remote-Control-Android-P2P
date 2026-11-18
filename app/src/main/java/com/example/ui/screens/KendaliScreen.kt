package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ControllerSessionState
import com.example.model.SignalingStatus
import com.example.model.TrustedDeviceEntry
import com.example.model.formatDeviceId
import com.example.model.normalizeDeviceId
import com.example.ui.components.E2eeShieldBadge
import com.example.ui.components.SignalingStatusPill
import com.example.ui.components.TacticalCard
import com.example.ui.theme.MonoTelemetryStyle

@Composable
fun KendaliScreen(
    signalingStatus: SignalingStatus,
    controllerState: ControllerSessionState,
    recentDevices: List<TrustedDeviceEntry>,
    onConnect: (targetId: String, password: String) -> Unit,
    onCancelConnect: () -> Unit,
    onRemoveRecentDevice: (String) -> Unit,
    onNavigateToServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var targetIdInput by rememberSaveable { mutableStateOf("") }
    var passwordInput by rememberSaveable { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    val isConnecting = controllerState is ControllerSessionState.ConnectingSignaling ||
        controllerState is ControllerSessionState.HandshakingE2ee ||
        controllerState is ControllerSessionState.NegotiatingIce

    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 700.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Kendalikan Perangkat",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = "Akses layar & kontrol sentuh jarak jauh berlatensi rendah",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SignalingStatusPill(
                    status = signalingStatus,
                    onClick = onNavigateToServer,
                )
            }

            TacticalCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Target Koneksi P2P", style = MaterialTheme.typography.titleLarge)
                    E2eeShieldBadge()
                }
                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = targetIdInput,
                    onValueChange = { raw ->
                        targetIdInput = normalizeDeviceId(raw).take(12)
                    },
                    label = { Text("ID Perangkat Target") },
                    placeholder = { Text("Contoh: 482910374") },
                    leadingIcon = {
                        Icon(Icons.Filled.Devices, contentDescription = null)
                    },
                    supportingText = {
                        if (targetIdInput.isNotEmpty()) {
                            Text(
                                text = "Format: ${formatDeviceId(targetIdInput)}",
                                style = MonoTelemetryStyle.copy(fontSize = 12.sp),
                            )
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    enabled = !isConnecting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("target_device_id_input"),
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("Kata Sandi Sesi Target") },
                    placeholder = { Text("Masukkan kata sandi sesi") },
                    leadingIcon = {
                        Icon(Icons.Filled.Key, contentDescription = null)
                    },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = "Tampilkan atau sembunyikan kata sandi",
                            )
                        }
                    },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !isConnecting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("target_session_password_input"),
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (isConnecting) {
                    OutlinedButton(
                        onClick = onCancelConnect,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("cancel_connect_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Batalkan Koneksi")
                    }
                } else {
                    Button(
                        onClick = { onConnect(targetIdInput, passwordInput) },
                        enabled = normalizeDeviceId(targetIdInput).length >= 6 && passwordInput.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("connect_target_button"),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Filled.CastConnected, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Hubungkan Sesi Terenkripsi", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            // Live Handshake / Error Status Card
            AnimatedVisibility(visible = controllerState !is ControllerSessionState.Idle) {
                val isError = controllerState is ControllerSessionState.Error
                TacticalCard(
                    borderColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    containerColor = if (isError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    when (controllerState) {
                        is ControllerSessionState.ConnectingSignaling -> {
                            Text(
                                text = "1/3 • Menghubungi ${formatDeviceId(controllerState.targetId)} via Signaling…",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        is ControllerSessionState.HandshakingE2ee -> {
                            Text(
                                text = "2/3 • Pertukaran Kunci ECDH P-256 & Verifikasi HMAC…",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        is ControllerSessionState.NegotiatingIce -> {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "3/3 • Membangun Jalur P2P WebRTC (STUN/TURN)…",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "Sidik Jari: ${controllerState.safetyFingerprint}",
                                    style = MonoTelemetryStyle.copy(
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.secondary,
                                    ),
                                )
                            }
                        }
                        is ControllerSessionState.Error -> {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Koneksi Gagal",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    text = controllerState.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                        else -> Unit
                    }
                }
            }

            // Recent Trusted Devices
            TacticalCard {
                Text(
                    text = "Perangkat Terpercaya Terakhir",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (recentDevices.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Belum ada riwayat perangkat. Perangkat yang berhasil terhubung E2EE akan tampil di sini untuk akses cepat.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        recentDevices.forEach { entry ->
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isConnecting) {
                                        targetIdInput = normalizeDeviceId(entry.deviceId)
                                    }
                                    .testTag("recent_device_${entry.deviceId}"),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.alias,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = formatDeviceId(entry.deviceId),
                                            style = MonoTelemetryStyle.copy(
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.primary,
                                            ),
                                        )
                                        if (entry.lastFingerprint.isNotBlank()) {
                                            Text(
                                                text = "E2EE: ${entry.lastFingerprint}",
                                                style = MonoTelemetryStyle.copy(
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.secondary,
                                                ),
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { onRemoveRecentDevice(entry.deviceId) },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.DeleteOutline,
                                            contentDescription = "Hapus perangkat dari riwayat",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
