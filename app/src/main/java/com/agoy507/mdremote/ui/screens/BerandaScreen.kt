package com.agoy507.mdremote.ui.screens

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.agoy507.mdremote.R
import com.agoy507.mdremote.model.DeviceIdentity
import com.agoy507.mdremote.model.HostStatus
import com.agoy507.mdremote.model.SignalingStatus
import com.agoy507.mdremote.model.TrustedDeviceEntry
import com.agoy507.mdremote.model.formatDeviceId
import com.agoy507.mdremote.ui.components.AccessibilityDisclosureDialog
import com.agoy507.mdremote.ui.components.E2eeShieldBadge
import com.agoy507.mdremote.ui.components.SignalingStatusPill
import com.agoy507.mdremote.ui.components.TacticalCard
import com.agoy507.mdremote.ui.theme.MonoTelemetryStyle
import kotlinx.coroutines.launch

@Composable
fun BerandaScreen(
    identity: DeviceIdentity,
    signalingStatus: SignalingStatus,
    hostStatus: HostStatus,
    accessibilityEnabled: Boolean,
    requireVisualApproval: Boolean,
    allowTouchControl: Boolean,
    trustedControllers: List<TrustedDeviceEntry> = emptyList(),
    snackbarHostState: SnackbarHostState,
    onRefreshPermissions: () -> Unit,
    onNavigateToServer: () -> Unit,
    onRegeneratePassword: () -> Unit,
    onUpdateCustomPassword: (String) -> Unit,
    onToggleVisualApproval: (Boolean) -> Unit,
    onToggleAllowTouchControl: (Boolean) -> Unit,
    onRemoveTrustedController: (String) -> Unit = {},
    onStartHostWithProjection: (Intent) -> Unit,
    onStopHost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var passwordVisible by remember { mutableStateOf(false) }
    var showEditPasswordDialog by remember { mutableStateOf(false) }
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }
    var hasNotificationPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshPermissions()
                hasNotificationPermission =
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasNotificationPermission = granted
        if (!granted) {
            scope.launch {
                snackbarHostState.showSnackbar("Izin notifikasi diperlukan agar status Host tampil saat berbagi layar.")
            }
        }
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            onStartHostWithProjection(result.data!!)
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Izin tangkapan layar dibatalkan.")
            }
        }
    }

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
            // Hero Header Card with generated P2P network artwork
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp)
                    .clip(MaterialTheme.shapes.large),
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_p2p_banner),
                    contentDescription = stringResource(R.string.cd_hero_banner),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0x66090D12),
                                    Color(0xEE090D12),
                                ),
                            ),
                        )
                        .padding(16.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SignalingStatusPill(
                                status = signalingStatus,
                                onClick = onNavigateToServer,
                            )
                            E2eeShieldBadge()
                        }
                        Column {
                            Text(
                                text = "Node Host Perangkat Ini",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                text = "Terowongan P2P WebRTC + AES-256-GCM • ${identity.deviceAlias}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Device ID & Session Password Card
            TacticalCard {
                Text(
                    text = "Kredensial Akses Instan",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Bagikan ID & Kata Sandi Sesi ini kepada perangkat pengendali tepercaya.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(14.dp))

                // Device ID row
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                text = "ID PERANGKAT",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = formatDeviceId(identity.deviceId),
                                style = MonoTelemetryStyle.copy(
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    letterSpacing = 1.5.sp,
                                ),
                                modifier = Modifier.testTag("device_id_text"),
                            )
                        }
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("ID Perangkat KendaliP2P", formatDeviceId(identity.deviceId)),
                                )
                                scope.launch {
                                    snackbarHostState.showSnackbar("ID Perangkat disalin ke papan klip.")
                                }
                            },
                            modifier = Modifier.testTag("copy_device_id_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = stringResource(R.string.cd_copy_device_id),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Session Password row
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "KATA SANDI SESI",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = if (passwordVisible) identity.sessionPassword else "••••••••",
                                style = MonoTelemetryStyle.copy(
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary,
                                    letterSpacing = 2.sp,
                                ),
                                modifier = Modifier.testTag("session_password_text"),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { passwordVisible = !passwordVisible },
                                modifier = Modifier.testTag("toggle_password_visibility_button"),
                            ) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = stringResource(R.string.cd_toggle_password),
                                )
                            }
                            IconButton(
                                onClick = { showEditPasswordDialog = true },
                                modifier = Modifier.testTag("edit_session_password_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = "Ubah kata sandi sesi",
                                )
                            }
                            IconButton(
                                onClick = {
                                    onRegeneratePassword()
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Kata sandi sesi diperbarui.")
                                    }
                                },
                                modifier = Modifier.testTag("regenerate_password_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = stringResource(R.string.cd_regenerate_password),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            // Host Status / Active Connection Card
            AnimatedVisibility(visible = hostStatus !is HostStatus.Idle) {
                TacticalCard(
                    borderColor = when (hostStatus) {
                        is HostStatus.Connected -> MaterialTheme.colorScheme.secondary
                        is HostStatus.Error -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    when (hostStatus) {
                        is HostStatus.Starting -> {
                            Text(
                                text = "Menyiapkan Layanan MediaProjection…",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        is HostStatus.WaitingForPeer -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ScreenShare,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Column {
                                    Text(
                                        text = "Mode Host Aktif • Menunggu Koneksi",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = "Layar siap dibagikan secara E2EE saat pengendali memasukkan ID & kata sandi.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        is HostStatus.Connected -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Dikendalikan oleh ${hostStatus.controllerAlias}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                    E2eeShieldBadge(label = hostStatus.telemetry.routeType.label)
                                }
                                Text(
                                    text = "Sidik Jari: ${hostStatus.telemetry.safetyFingerprint}",
                                    style = MonoTelemetryStyle.copy(
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                                Text(
                                    text = "RTT ${hostStatus.telemetry.rttMs} ms • ${hostStatus.telemetry.fps} FPS • ${hostStatus.telemetry.bitrateKbps} kbps",
                                    style = MonoTelemetryStyle.copy(
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                    ),
                                )
                            }
                        }
                        is HostStatus.Error -> {
                            Text(
                                text = hostStatus.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        HostStatus.Idle -> Unit
                    }
                }
            }

            // Host Permissions & Security Policy Card
            TacticalCard {
                Text(
                    text = "Kesiapan Sistem & Keamanan Host",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Accessibility permission item
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = if (accessibilityEnabled) Icons.Filled.CheckCircle else Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = if (accessibilityEnabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                        )
                        Column {
                            Text(
                                text = "Layanan Aksesibilitas (Kontrol Sentuh)",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = if (accessibilityEnabled) "Aktif • Gestur jarak jauh siap" else "Belum aktif • Hanya mode lihat layar",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!accessibilityEnabled) {
                        FilledTonalButton(
                            onClick = { showAccessibilityDisclosure = true },
                            modifier = Modifier.testTag("enable_accessibility_button"),
                        ) {
                            Icon(Icons.Filled.TouchApp, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Aktifkan")
                        }
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.NotificationsActive,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                            Column {
                                Text("Izin Notifikasi Foreground", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Diperlukan agar sesi berbagi layar dapat dipantau.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        FilledTonalButton(
                            onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            modifier = Modifier.testTag("enable_notifications_button"),
                        ) {
                            Text("Izinkan")
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 14.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )

                // Toggle: Visual Approval
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Konfirmasi Pengendali Baru", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (requireVisualApproval) {
                                "Perangkat yang pernah terhubung otomatis disetujui. Perangkat baru memerlukan konfirmasi."
                            } else {
                                "Otomatis terima semua koneksi yang memasukkan kata sandi sesi yang benar."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = requireVisualApproval,
                        onCheckedChange = onToggleVisualApproval,
                        modifier = Modifier.testTag("switch_visual_approval"),
                    )
                }

                if (trustedControllers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Pengendali Tepercaya (${trustedControllers.size}) • Bebas Konfirmasi",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        trustedControllers.forEach { controller ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = controller.alias,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    )
                                    Text(
                                        text = formatDeviceId(controller.deviceId),
                                        style = MonoTelemetryStyle.copy(
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                    )
                                }
                                IconButton(
                                    onClick = { onRemoveTrustedController(controller.deviceId) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DeleteOutline,
                                        contentDescription = "Hapus izin otomatis",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Toggle: Allow Touch Control
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Izinkan Kendali Sentuh & Navigasi", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Nonaktifkan jika hanya ingin mengizinkan mode pantau layar.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = allowTouchControl,
                        onCheckedChange = onToggleAllowTouchControl,
                        modifier = Modifier.testTag("switch_allow_touch"),
                    )
                }
            }

            // Primary Host Action CTA
            val isHostRunning = hostStatus !is HostStatus.Idle
            Button(
                onClick = {
                    if (isHostRunning) {
                        onStopHost()
                    } else {
                        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("host_mode_primary_button"),
                shape = RoundedCornerShape(14.dp),
                colors = if (isHostRunning) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                },
            ) {
                Icon(
                    imageVector = if (isHostRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isHostRunning) "Hentikan Mode Host" else "Aktifkan Mode Host (Bagikan Layar)",
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Enkripsi End-to-End ECDH P-256 + AES-256-GCM aktif pada setiap sesi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    if (showAccessibilityDisclosure) {
        AccessibilityDisclosureDialog(
            onConfirmOpenSettings = {
                showAccessibilityDisclosure = false
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            },
            onDismiss = { showAccessibilityDisclosure = false },
        )
    }

    if (showEditPasswordDialog) {
        var customPwd by remember { mutableStateOf(identity.sessionPassword) }
        AlertDialog(
            onDismissRequest = { showEditPasswordDialog = false },
            title = { Text("Ubah Kata Sandi Sesi") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Minimal 4 karakter. Kata sandi tidak pernah dikirim dalam bentuk teks biasa ke server.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = customPwd,
                        onValueChange = { customPwd = it },
                        singleLine = true,
                        label = { Text("Kata Sandi Sesi Baru") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_password_input"),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (customPwd.trim().length >= 4) {
                            onUpdateCustomPassword(customPwd)
                            showEditPasswordDialog = false
                        }
                    },
                    enabled = customPwd.trim().length >= 4,
                    modifier = Modifier.testTag("save_custom_password_button"),
                ) {
                    Text("Simpan")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showEditPasswordDialog = false }) {
                    Text("Batal")
                }
            },
        )
    }
}
