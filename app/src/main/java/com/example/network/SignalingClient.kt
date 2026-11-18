package com.example.network

import com.example.model.ServerEndpointConfig
import com.example.model.ServerHealthResult
import com.example.model.SignalingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

sealed interface SignalingEvent {
    data class Registered(
        val deviceId: String,
        val iceServers: List<PeerConnection.IceServer>,
    ) : SignalingEvent

    data class E2eeHello(
        val fromId: String,
        val sessionId: String,
        val controllerPubKeyB64: String,
        val controllerNonceB64: String,
        val alias: String,
    ) : SignalingEvent

    data class E2eeAck(
        val fromId: String,
        val sessionId: String,
        val hostPubKeyB64: String,
        val hostNonceB64: String,
        val hostProofB64: String,
        val hostAlias: String,
    ) : SignalingEvent

    data class E2eeReject(
        val fromId: String,
        val sessionId: String,
        val reason: String,
        val message: String,
    ) : SignalingEvent

    data class EncryptedWebRtcOffer(
        val fromId: String,
        val sessionId: String,
        val clientProofB64: String,
        val encryptedSdpB64: String,
    ) : SignalingEvent

    data class EncryptedWebRtcAnswer(
        val fromId: String,
        val sessionId: String,
        val encryptedSdpB64: String,
    ) : SignalingEvent

    data class EncryptedIceCandidate(
        val fromId: String,
        val sessionId: String,
        val encryptedCandidateB64: String,
    ) : SignalingEvent

    data class SessionEnded(
        val fromId: String,
        val sessionId: String,
        val reason: String,
    ) : SignalingEvent

    data class PeerOffline(
        val targetId: String,
        val sessionId: String,
        val message: String,
    ) : SignalingEvent

    data class ServerError(
        val code: String,
        val message: String,
    ) : SignalingEvent
}

class SignalingClient(
    private val scope: CoroutineScope,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _status = MutableStateFlow<SignalingStatus>(SignalingStatus.Disconnected)
    val status: StateFlow<SignalingStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<SignalingEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SignalingEvent> = _events.asSharedFlow()

    private val _iceServers = MutableStateFlow<List<PeerConnection.IceServer>>(emptyList())
    val iceServers: StateFlow<List<PeerConnection.IceServer>> = _iceServers.asStateFlow()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var activeConfig: ServerEndpointConfig? = null
    private var activeDeviceId: String = ""
    private var activeAlias: String = ""
    private var manualDisconnect = false

    fun connect(config: ServerEndpointConfig, deviceId: String, alias: String) {
        val url = config.signalingUrl.trim()
        if (url.isEmpty()) {
            _status.value = SignalingStatus.Error("URL signaling belum diatur.")
            return
        }
        manualDisconnect = false
        activeConfig = config
        activeDeviceId = deviceId
        activeAlias = alias
        reconnectJob?.cancel()
        openSocket(config, deviceId, alias)
    }

    fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        webSocket?.close(1000, "Client disconnected")
        webSocket = null
        _status.value = SignalingStatus.Disconnected
    }

    fun sendPayload(payload: JSONObject): Boolean {
        val ws = webSocket ?: return false
        return ws.send(payload.toString())
    }

    suspend fun checkServerHealth(config: ServerEndpointConfig, deviceId: String): ServerHealthResult =
        withContext(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            val baseHttpUrl = wsUrlToHttpBase(config.signalingUrl)
                ?: return@withContext ServerHealthResult(
                    reachable = false,
                    latencyMs = 0,
                    message = "Format URL WebSocket tidak valid. Gunakan ws:// atau wss://.",
                )

            val healthClient = OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .build()

            try {
                val reqBuilder = Request.Builder().url("$baseHttpUrl/health")
                if (config.serverApiToken.isNotBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer ${config.serverApiToken}")
                }
                val healthBody = healthClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext ServerHealthResult(
                            reachable = false,
                            latencyMs = (System.currentTimeMillis() - startMs).toInt(),
                            message = "Server merespons HTTP ${resp.code}",
                        )
                    }
                    resp.body?.string().orEmpty()
                }
                val latency = (System.currentTimeMillis() - startMs).toInt().coerceAtLeast(1)
                val healthJson = JSONObject(healthBody)
                val activePeers = healthJson.optInt("activePeers", 0)

                val iceReqBuilder = Request.Builder().url(
                    "$baseHttpUrl/ice-servers?deviceId=${URLEncoder.encode(deviceId, "UTF-8")}",
                )
                if (config.serverApiToken.isNotBlank()) {
                    iceReqBuilder.addHeader("Authorization", "Bearer ${config.serverApiToken}")
                }
                var iceCount = 0
                var turnAvailable = false
                healthClient.newCall(iceReqBuilder.build()).execute().use { iceResp ->
                    if (iceResp.isSuccessful) {
                        val iceJson = JSONObject(iceResp.body?.string().orEmpty())
                        val arr = iceJson.optJSONArray("iceServers") ?: JSONArray()
                        iceCount = arr.length()
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            if (item.has("credential")) {
                                turnAvailable = true
                            }
                        }
                    }
                }

                ServerHealthResult(
                    reachable = true,
                    latencyMs = latency,
                    activePeers = activePeers,
                    iceServerCount = iceCount,
                    turnAvailable = turnAvailable,
                    message = if (turnAvailable) {
                        "Container server aktif • Signaling + TURN HMAC siap ($latency ms)"
                    } else {
                        "Signaling aktif ($latency ms) • TURN belum terkonfigurasi"
                    },
                )
            } catch (ex: Exception) {
                ServerHealthResult(
                    reachable = false,
                    latencyMs = 0,
                    message = "Gagal menjangkau server: ${ex.message ?: "Koneksi ditolak"}",
                )
            }
        }

    private fun openSocket(config: ServerEndpointConfig, deviceId: String, alias: String) {
        webSocket?.cancel()
        _status.value = SignalingStatus.Connecting

        val fullUrl = if (config.serverApiToken.isNotBlank() && !config.signalingUrl.contains("token=")) {
            val sep = if (config.signalingUrl.contains("?")) "&" else "?"
            "${config.signalingUrl}${sep}token=${URLEncoder.encode(config.serverApiToken, "UTF-8")}"
        } else {
            config.signalingUrl
        }

        val request = try {
            Request.Builder().url(fullUrl).build()
        } catch (ex: IllegalArgumentException) {
            _status.value = SignalingStatus.Error("URL tidak valid: ${ex.message}")
            return
        }

        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val reg = JSONObject()
                        .put("type", "register")
                        .put("deviceId", deviceId)
                        .put("alias", alias)
                    webSocket.send(reg.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncomingMessage(text, config)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _status.value = SignalingStatus.Error(t.message ?: "Koneksi terputus")
                    scheduleReconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!manualDisconnect) {
                        _status.value = SignalingStatus.Disconnected
                        scheduleReconnect()
                    }
                }
            },
        )
    }

    private fun scheduleReconnect() {
        if (manualDisconnect) return
        val cfg = activeConfig ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(4_000L)
            if (isActive && !manualDisconnect) {
                openSocket(cfg, activeDeviceId, activeAlias)
            }
        }
    }

    private fun handleIncomingMessage(raw: String, config: ServerEndpointConfig) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (val type = json.optString("type")) {
            "registered" -> {
                val parsedIce = parseIceServers(json.optJSONArray("iceServers"), config)
                _iceServers.value = parsedIce
                _status.value = SignalingStatus.Registered(parsedIce.size)
                _events.tryEmit(
                    SignalingEvent.Registered(
                        deviceId = json.optString("deviceId"),
                        iceServers = parsedIce,
                    ),
                )
            }
            "e2ee_hello" -> {
                _events.tryEmit(
                    SignalingEvent.E2eeHello(
                        fromId = json.optString("fromId"),
                        sessionId = json.optString("sessionId"),
                        controllerPubKeyB64 = json.optString("pubKey"),
                        controllerNonceB64 = json.optString("nonce"),
                        alias = json.optString("alias", "Pengendali"),
                    ),
                )
            }
            "e2ee_ack" -> {
                _events.tryEmit(
                    SignalingEvent.E2eeAck(
                        fromId = json.optString("fromId"),
                        sessionId = json.optString("sessionId"),
                        hostPubKeyB64 = json.optString("pubKey"),
                        hostNonceB64 = json.optString("nonce"),
                        hostProofB64 = json.optString("hostProof"),
                        hostAlias = json.optString("alias", "Host"),
                    ),
                )
            }
            "e2ee_reject" -> {
                _events.tryEmit(
                    SignalingEvent.E2eeReject(
                        fromId = json.optString("fromId"),
                        sessionId = json.optString("sessionId"),
                        reason = json.optString("reason", "REJECTED"),
                        message = json.optString("message", "Sesi ditolak oleh perangkat target."),
                    ),
                )
            }
            "webrtc_offer" -> {
                _events.tryEmit(
                    SignalingEvent.EncryptedWebRtcOffer(
                        fromId = json.optString("fromId"),
                        sessionId = json.optString("sessionId"),
                        clientProofB64 = json.optString("clientProof"),
                        encryptedSdpB64 = json.optString("payload"),
                    ),
                )
            }
            "webrtc_answer" -> {
                _events.tryEmit(
                    SignalingEvent.EncryptedWebRtcAnswer(
                        fromId = json.optString("fromId"),
                        sessionId = json.optString("sessionId"),
                        encryptedSdpB64 = json.optString("payload"),
                    ),
                )
            }
            "webrtc_ice" -> {
                _events.tryEmit(
                    SignalingEvent.EncryptedIceCandidate(
                        fromId = json.optString("fromId"),
                        sessionId = json.optString("sessionId"),
                        encryptedCandidateB64 = json.optString("payload"),
                    ),
                )
            }
            "session_end" -> {
                _events.tryEmit(
                    SignalingEvent.SessionEnded(
                        fromId = json.optString("fromId"),
                        sessionId = json.optString("sessionId"),
                        reason = json.optString("reason", "Sesi diakhiri oleh peer."),
                    ),
                )
            }
            "peer_offline" -> {
                _events.tryEmit(
                    SignalingEvent.PeerOffline(
                        targetId = json.optString("targetId"),
                        sessionId = json.optString("sessionId"),
                        message = json.optString("message", "Perangkat target sedang offline."),
                    ),
                )
            }
            "error" -> {
                _events.tryEmit(
                    SignalingEvent.ServerError(
                        code = json.optString("code", "ERR"),
                        message = json.optString("message", "Kesalahan signaling ($type)."),
                    ),
                )
            }
        }
    }

    private fun parseIceServers(
        array: JSONArray?,
        config: ServerEndpointConfig,
    ): List<PeerConnection.IceServer> {
        val list = mutableListOf<PeerConnection.IceServer>()
        if (array != null) {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val urlsArr = obj.optJSONArray("urls") ?: continue
                val urls = buildList {
                    for (j in 0 until urlsArr.length()) {
                        urlsArr.optString(j).takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
                if (urls.isEmpty()) continue
                val builder = PeerConnection.IceServer.builder(urls)
                val user = obj.optString("username")
                val cred = obj.optString("credential")
                if (user.isNotBlank() && cred.isNotBlank()) {
                    builder.setUsername(user).setPassword(cred)
                }
                list.add(builder.createIceServer())
            }
        }
        if (config.stunUrl.isNotBlank()) {
            list.add(PeerConnection.IceServer.builder(config.stunUrl).createIceServer())
        }
        if (config.turnUrl.isNotBlank()) {
            list.add(PeerConnection.IceServer.builder(config.turnUrl).createIceServer())
        }
        if (list.isEmpty()) {
            list.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        }
        return list
    }

    private fun wsUrlToHttpBase(wsUrl: String): String? {
        val trimmed = wsUrl.trim()
        return when {
            trimmed.startsWith("wss://") -> {
                val stripped = trimmed.removePrefix("wss://").substringBefore("/ws").trimEnd('/')
                "https://$stripped"
            }
            trimmed.startsWith("ws://") -> {
                val stripped = trimmed.removePrefix("ws://").substringBefore("/ws").trimEnd('/')
                "http://$stripped"
            }
            else -> null
        }
    }
}
