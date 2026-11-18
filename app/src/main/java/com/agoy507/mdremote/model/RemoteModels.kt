package com.agoy507.mdremote.model

import kotlinx.serialization.Serializable

@Serializable
data class DeviceIdentity(
    val deviceId: String,
    val deviceAlias: String,
    val sessionPassword: String,
)

@Serializable
data class ServerEndpointConfig(
    val signalingUrl: String = DEFAULT_SIGNALING_URL,
    val serverApiToken: String = "",
) {
    companion object {
        // Production server — remote.agoy507.my.id via Cloudflare Tunnel (wss)
        const val DEFAULT_SIGNALING_URL = "wss://remote.agoy507.my.id/ws"
        // Fallback untuk dev lokal / emulator
        const val LOCAL_SIGNALING_URL = "ws://10.0.2.2:8080/ws"
    }
}
// Kredensial ICE ephemeral yang diterima dari respons 'registered' signaling server.
// Berlaku 1 jam (TTL dikontrol server). Client tidak perlu menyimpan password TURN.
data class EphemeralIceConfig(
    val iceServers: List<org.webrtc.PeerConnection.IceServer>,
    val ttlSeconds: Int,
    val receivedAtMs: Long = System.currentTimeMillis(),
) {
    fun isExpired(): Boolean =
        System.currentTimeMillis() > receivedAtMs + (ttlSeconds * 1000L) - 60_000L // renew 1 menit sebelum expired
}

@Serializable
data class TrustedDeviceEntry(
    val deviceId: String,
    val alias: String,
    val lastConnectedEpochMs: Long,
    val lastFingerprint: String = "",
)

@Serializable
enum class StreamQualityPreset(
    val label: String,
    val description: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val fps: Int,
    val maxBitrateKbps: Int,
) {
    ULTRA_LOW_LATENCY(
        label = "Latensi Ultra",
        description = "720p • 60 FPS • Respons tercepat",
        maxWidth = 720,
        maxHeight = 1280,
        fps = 60,
        maxBitrateKbps = 2500,
    ),
    BALANCED(
        label = "Seimbang",
        description = "720p • 30 FPS • Stabil untuk Wi-Fi/4G",
        maxWidth = 720,
        maxHeight = 1280,
        fps = 30,
        maxBitrateKbps = 1500,
    ),
    DATA_SAVER(
        label = "Hemat Data",
        description = "540p • 24 FPS • Hemat kuota seluler",
        maxWidth = 540,
        maxHeight = 960,
        fps = 24,
        maxBitrateKbps = 800,
    ),
}

enum class RemoteRouteType(val label: String) {
    DIRECT_P2P("P2P Langsung"),
    TURN_RELAY("Relay TURN"),
    NEGOTIATING("Menegoisasi Rute"),
}

data class ConnectionTelemetry(
    val rttMs: Int = 0,
    val fps: Int = 0,
    val bitrateKbps: Int = 0,
    val routeType: RemoteRouteType = RemoteRouteType.NEGOTIATING,
    val packetLossPercent: Float = 0f,
    val e2eeVerified: Boolean = false,
    val safetyFingerprint: String = "",
)

sealed interface SignalingStatus {
    data object Disconnected : SignalingStatus
    data object Connecting : SignalingStatus
    data class Registered(val iceCount: Int) : SignalingStatus
    data class Error(val message: String) : SignalingStatus
}

sealed interface HostStatus {
    data object Idle : HostStatus
    data object Starting : HostStatus
    data class WaitingForPeer(val safetyHint: String = "") : HostStatus
    data class Connected(
        val controllerId: String,
        val controllerAlias: String,
        val telemetry: ConnectionTelemetry,
    ) : HostStatus
    data class Error(val message: String) : HostStatus
}

data class IncomingConnectionRequest(
    val sessionId: String,
    val fromDeviceId: String,
    val fromAlias: String,
    val safetyFingerprint: String,
)

sealed interface ControllerSessionState {
    data object Idle : ControllerSessionState
    data class ConnectingSignaling(val targetId: String) : ControllerSessionState
    data class HandshakingE2ee(val targetId: String) : ControllerSessionState
    data class NegotiatingIce(
        val targetId: String,
        val safetyFingerprint: String,
    ) : ControllerSessionState
    data class Connected(
        val targetId: String,
        val targetAlias: String,
        val telemetry: ConnectionTelemetry,
    ) : ControllerSessionState
    data class Error(
        val message: String,
        val recoverable: Boolean = true,
    ) : ControllerSessionState
}

data class ServerHealthResult(
    val reachable: Boolean,
    val latencyMs: Int,
    val activePeers: Int = 0,
    val iceServerCount: Int = 0,
    val turnAvailable: Boolean = false,
    val message: String,
)

fun normalizeDeviceId(raw: String): String =
    raw.filter { it.isLetterOrDigit() }.uppercase()

fun formatDeviceId(raw: String): String {
    val clean = normalizeDeviceId(raw)
    return if (clean.length == 9) {
        "${clean.substring(0, 3)}-${clean.substring(3, 6)}-${clean.substring(6, 9)}"
    } else {
        clean
    }
}
