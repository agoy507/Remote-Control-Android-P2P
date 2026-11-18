package com.agoy507.mdremote.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agoy507.mdremote.model.ServerEndpointConfig
import com.agoy507.mdremote.model.ServerHealthResult
import com.agoy507.mdremote.model.SignalingStatus
import com.agoy507.mdremote.ui.components.SignalingStatusPill
import com.agoy507.mdremote.ui.components.TacticalCard
import com.agoy507.mdremote.ui.theme.MonoTelemetryStyle
import kotlinx.coroutines.launch

@Composable
fun ServerConfigScreen(
    currentConfig: ServerEndpointConfig,
    signalingStatus: SignalingStatus,
    healthResult: ServerHealthResult?,
    isCheckingServer: Boolean,
    snackbarHostState: SnackbarHostState,
    onSaveAndReconnect: (ServerEndpointConfig) -> Unit,
    onTestServerHealth: (ServerEndpointConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var signalingUrl by remember { mutableStateOf(currentConfig.signalingUrl) }
    var stunUrl by remember { mutableStateOf(currentConfig.stunUrl) }
    var turnUrl by remember { mutableStateOf(currentConfig.turnUrl) }
    var apiToken by remember { mutableStateOf(currentConfig.serverApiToken) }
    var showDockerGuide by remember { mutableStateOf(false) }

    LaunchedEffect(currentConfig) {
        signalingUrl = currentConfig.signalingUrl
        stunUrl = currentConfig.stunUrl
        turnUrl = currentConfig.turnUrl
        apiToken = currentConfig.serverApiToken
    }

    val draftConfig = ServerEndpointConfig(
        signalingUrl = signalingUrl,
        stunUrl = stunUrl,
        turnUrl = turnUrl,
        serverApiToken = apiToken,
    )

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
                        text = "Container Server P2P",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = "Konfigurasi Node.js Signaling & coturn STUN/TURN",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SignalingStatusPill(
                    status = signalingStatus,
                    onClick = { onTestServerHealth(draftConfig) },
                )
            }

            TacticalCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text("Endpoint Signaling & ICE", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = signalingUrl,
                    onValueChange = { signalingUrl = it },
                    label = { Text("URL WebSocket Signaling") },
                    placeholder = { Text("wss://remote.domainanda.com/ws") },
                    supportingText = { Text("Gunakan wss:// untuk produksi atau ws://IP:8080/ws di LAN/Emulator") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("signaling_url_input"),
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = stunUrl,
                    onValueChange = { stunUrl = it },
                    label = { Text("URL STUN Tambahan") },
                    placeholder = { Text("stun:stun.l.google.com:19302") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stun_url_input"),
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = turnUrl,
                    onValueChange = { turnUrl = it },
                    label = { Text("URL TURN Manual (Opsional)") },
                    placeholder = { Text("Otomatis diambil via HMAC dari server signaling") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("turn_url_input"),
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = apiToken,
                    onValueChange = { apiToken = it },
                    label = { Text("Token Otorisasi Server (Opsional)") },
                    placeholder = { Text("Kosongkan jika SERVER_API_TOKEN tidak diset") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("server_api_token_input"),
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilledTonalButton(
                        onClick = { onTestServerHealth(draftConfig) },
                        enabled = !isCheckingServer && signalingUrl.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("test_server_health_button"),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (isCheckingServer) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Uji Server")
                    }

                    Button(
                        onClick = {
                            onSaveAndReconnect(draftConfig)
                            scope.launch {
                                snackbarHostState.showSnackbar("Konfigurasi server disimpan & menghubungkan ulang.")
                            }
                        },
                        enabled = signalingUrl.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("save_server_config_button"),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Simpan")
                    }
                }
            }

            // Live Health Result Card
            AnimatedVisibility(visible = healthResult != null) {
                healthResult?.let { result ->
                    TacticalCard(
                        borderColor = if (result.reachable) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = if (result.reachable) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                tint = if (result.reachable) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                            )
                            Column {
                                Text(
                                    text = if (result.reachable) "Container Server Terjangkau" else "Server Tidak Terjangkau",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = result.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (result.reachable) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Latensi: ${result.latencyMs} ms • Peer Aktif: ${result.activePeers} • ICE Server: ${result.iceServerCount}",
                                style = MonoTelemetryStyle.copy(
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                }
            }

            // Docker Compose Self-Hosting Guide Card
            TacticalCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                        Text("Panduan Deploy Container Server", style = MaterialTheme.typography.titleLarge)
                    }
                    OutlinedButton(
                        onClick = { showDockerGuide = !showDockerGuide },
                        modifier = Modifier.testTag("toggle_docker_guide_button"),
                    ) {
                        Icon(
                            imageVector = if (showDockerGuide) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Paket lengkap Docker Compose (Node.js Signaling + coturn TURN/STUN) tersedia di folder server/ dalam proyek ini.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                AnimatedVisibility(visible = showDockerGuide) {
                    Column(
                        modifier = Modifier.padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.background,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) {
                            Text(
                                text = """
                                    cd server
                                    cp .env.example .env
                                    # Edit PUBLIC_HOST, PUBLIC_IP, TURN_SECRET
                                    docker compose up -d --build
                                    curl http://localhost:8080/health
                                """.trimIndent(),
                                style = MonoTelemetryStyle.copy(
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.secondary,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CloudDone,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "Buka port TCP 8080 (atau 443 via Caddy/Nginx WSS), TCP/UDP 3478, dan UDP 49152-49252 untuk relay TURN.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
