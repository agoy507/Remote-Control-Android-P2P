package com.agoy507.mdremote.service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.agoy507.mdremote.crypto.E2eeCryptoEngine
import com.agoy507.mdremote.data.AppPreferencesRepository
import com.agoy507.mdremote.model.ConnectionTelemetry
import com.agoy507.mdremote.model.ControllerSessionState
import com.agoy507.mdremote.model.HostStatus
import com.agoy507.mdremote.model.IncomingConnectionRequest
import com.agoy507.mdremote.model.RemoteControlPacket
import com.agoy507.mdremote.model.RemoteMouseAction
import com.agoy507.mdremote.model.RemoteMouseButton
import com.agoy507.mdremote.model.RemoteSystemAction
import com.agoy507.mdremote.model.ServerEndpointConfig
import com.agoy507.mdremote.model.ServerHealthResult
import com.agoy507.mdremote.model.SignalingStatus
import com.agoy507.mdremote.model.StreamQualityPreset
import com.agoy507.mdremote.model.TrustedDeviceEntry
import com.agoy507.mdremote.model.formatDeviceId
import com.agoy507.mdremote.model.normalizeDeviceId
import com.agoy507.mdremote.network.SignalingClient
import com.agoy507.mdremote.network.SignalingEvent
import com.agoy507.mdremote.webrtc.WebRtcRemoteManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.SecretKey

class RemoteSessionCoordinator private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val preferences = AppPreferencesRepository(appContext)
    private val crypto = E2eeCryptoEngine()
    private val signaling = SignalingClient(scope)
    val webRtc = WebRtcRemoteManager(appContext, scope, crypto)

    val signalingStatus: StateFlow<SignalingStatus> = signaling.status

    private val _hostStatus = MutableStateFlow<HostStatus>(HostStatus.Idle)
    val hostStatus: StateFlow<HostStatus> = _hostStatus.asStateFlow()

    private val _incomingRequest = MutableStateFlow<IncomingConnectionRequest?>(null)
    val incomingRequest: StateFlow<IncomingConnectionRequest?> = _incomingRequest.asStateFlow()

    private val _controllerState = MutableStateFlow<ControllerSessionState>(ControllerSessionState.Idle)
    val controllerState: StateFlow<ControllerSessionState> = _controllerState.asStateFlow()

    // Active Host projection & session state
    private var projectionIntent: Intent? = null
    private var pendingHostHandshake: PendingHostSession? = null
    private var activeHostSession: ActivePeerSession? = null
    private var androidDragStart: Pair<Float, Float>? = null
    private var failedAuthAttempts = 0

    // Active Controller session state
    private var activeControllerSession: ActiveControllerSession? = null
    private var pingLoopJob: Job? = null

    private data class PendingHostSession(
        val sessionId: String,
        val fromId: String,
        val fromAlias: String,
        val derivedKeys: E2eeCryptoEngine.DerivedSessionKeys,
        val clientProofExpected: String,
        val hostProof: String,
        val hostInit: E2eeCryptoEngine.EphemeralHandshakeInit,
        val controllerNonceB64: String,
    )

    private data class ActivePeerSession(
        val sessionId: String,
        val peerId: String,
        val peerAlias: String,
        val aesKey: SecretKey,
        val safetyFingerprint: String,
        val outgoingSigSeq: AtomicLong = AtomicLong(1L),
        val expectedSigSeq: Long = 1L,
    )

    private data class ActiveControllerSession(
        val sessionId: String,
        val targetId: String,
        val targetPassword: String,
        val init: E2eeCryptoEngine.EphemeralHandshakeInit,
        val rememberPassword: Boolean = true,
        var targetAlias: String = "Perangkat Target",
        var derivedKeys: E2eeCryptoEngine.DerivedSessionKeys? = null,
        var clientProofB64: String = "",
        val outgoingSigSeq: AtomicLong = AtomicLong(1L),
        var expectedSigSeq: Long = 1L,
    )

    init {
        scope.launch {
            val identity = preferences.ensureInitialized()
            val config = preferences.serverConfigFlow.first()
            signaling.connect(config, identity.deviceId, identity.deviceAlias)
        }
        scope.launch {
            signaling.events.collect { event -> handleSignalingEvent(event) }
        }
        scope.launch {
            webRtc.telemetry.collect { tel ->
                when (val ctrl = _controllerState.value) {
                    is ControllerSessionState.Connected -> {
                        _controllerState.value = ctrl.copy(telemetry = tel)
                    }
                    else -> Unit
                }
                when (val host = _hostStatus.value) {
                    is HostStatus.Connected -> {
                        _hostStatus.value = host.copy(telemetry = tel)
                    }
                    else -> Unit
                }
            }
        }
        registerNetworkCallback()
        scope.launch {
            RemoteStandbyService.start(appContext)
        }
        scope.launch {
            signaling.status.collect { status ->
                val identity = preferences.identityFlow.first()
                val idFormatted = formatDeviceId(identity.deviceId)
                val text = when (status) {
                    is SignalingStatus.Registered -> "Terhubung • ID: $idFormatted"
                    is SignalingStatus.Connecting -> "Menghubungkan ke signaling…"
                    is SignalingStatus.Error -> "Terputus • Menghubungkan ulang…"
                    is SignalingStatus.Disconnected -> "Standby • Offline"
                }
                RemoteStandbyService.updateStatus(appContext, text)
            }
        }
    }

    fun onAppForegrounded() {
        scope.launch {
            val status = signaling.status.value
            if (status !is SignalingStatus.Registered && status !is SignalingStatus.Connecting) {
                Log.d("MDRemote-Coord", "App foregrounded, status=$status. Triggering reconnect...")
                reconnectSignaling()
            }
        }
    }

    private fun registerNetworkCallback() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val status = signaling.status.value
                    if (status !is SignalingStatus.Registered && status !is SignalingStatus.Connecting) {
                        Log.d("MDRemote-Coord", "Network available, status=$status. Triggering reconnect...")
                        reconnectSignaling()
                    }
                }
            })
        } catch (ex: Exception) {
            Log.w("MDRemote-Coord", "Failed to register NetworkCallback: ${ex.message}")
        }
    }

    fun reconnectSignaling() {
        scope.launch {
            val identity = preferences.ensureInitialized()
            val config = preferences.serverConfigFlow.first()
            signaling.connect(config, identity.deviceId, identity.deviceAlias)
        }
    }

    suspend fun testServerConnection(config: ServerEndpointConfig): ServerHealthResult {
        val id = preferences.ensureInitialized().deviceId
        return signaling.checkServerHealth(config, id)
    }

    fun startHostMode(mediaProjectionData: Intent) {
        _hostStatus.value = HostStatus.Starting
        RemoteHostService.start(appContext, mediaProjectionData)
    }

    internal fun onHostServiceReady(mediaProjectionData: Intent) {
        projectionIntent = mediaProjectionData
        _hostStatus.value = HostStatus.WaitingForPeer()
        val currentStatus = signaling.status.value
        if (currentStatus !is SignalingStatus.Registered && currentStatus !is SignalingStatus.Connecting) {
            Log.d("MDRemote-Coord", "Host service ready but signaling not connected ($currentStatus). Reconnecting...")
            reconnectSignaling()
        }
    }

    fun stopHostMode() {
        activeHostSession?.let { active ->
            sendSessionEnd(active.peerId, active.sessionId, "Host menghentikan berbagi layar.")
        }
        activeHostSession = null
        pendingHostHandshake = null
        _incomingRequest.value = null
        projectionIntent = null
        webRtc.stopSession()
        _hostStatus.value = HostStatus.Idle
        RemoteHostService.stop(appContext)
    }

    fun approveIncomingConnection() {
        val req = _incomingRequest.value ?: return
        val pending = pendingHostHandshake ?: return
        if (pending.sessionId != req.sessionId) return
        _incomingRequest.value = null
        scope.launch {
            preferences.recordTrustedController(
                TrustedDeviceEntry(
                    deviceId = req.fromDeviceId,
                    alias = req.fromAlias,
                    lastConnectedEpochMs = System.currentTimeMillis(),
                    lastFingerprint = req.safetyFingerprint,
                )
            )
            finalizeHostHandshakeAck(pending)
        }
    }

    fun rejectIncomingConnection() {
        val req = _incomingRequest.value ?: return
        _incomingRequest.value = null
        pendingHostHandshake = null
        signaling.sendPayload(
            JSONObject()
                .put("type", "e2ee_reject")
                .put("targetId", req.fromDeviceId)
                .put("sessionId", req.sessionId)
                .put("reason", "USER_DECLINED")
                .put("message", "Pemilik perangkat menolak permintaan akses."),
        )
    }

    fun connectToTarget(rawTargetId: String, sessionPassword: String, rememberPassword: Boolean = true) {
        val targetId = normalizeDeviceId(rawTargetId)
        if (targetId.length < 6) {
            _controllerState.value = ControllerSessionState.Error("ID Perangkat minimal 6 karakter.")
            return
        }
        if (sessionPassword.isBlank()) {
            _controllerState.value = ControllerSessionState.Error("Kata sandi sesi wajib diisi.")
            return
        }

        scope.launch {
            val identity = preferences.ensureInitialized()
            if (normalizeDeviceId(identity.deviceId) == targetId) {
                _controllerState.value = ControllerSessionState.Error("Tidak dapat menghubungkan ke ID perangkat sendiri.")
                return@launch
            }
            if (signaling.status.value !is SignalingStatus.Registered) {
                val cfg = preferences.serverConfigFlow.first()
                signaling.connect(cfg, identity.deviceId, identity.deviceAlias)
            }

            val sessionId = UUID.randomUUID().toString()
            val init = crypto.createEphemeralInit()
            activeControllerSession = ActiveControllerSession(
                sessionId = sessionId,
                targetId = targetId,
                targetPassword = sessionPassword.trim(),
                init = init,
                rememberPassword = rememberPassword,
            )
            _controllerState.value = ControllerSessionState.ConnectingSignaling(targetId)

            val sent = signaling.sendPayload(
                JSONObject()
                    .put("type", "e2ee_hello")
                    .put("targetId", targetId)
                    .put("sessionId", sessionId)
                    .put("pubKey", init.publicKeyB64)
                    .put("nonce", init.nonceB64)
                    .put("alias", identity.deviceAlias),
            )
            if (!sent) {
                _controllerState.value = ControllerSessionState.Error(
                    "Belum terhubung ke server signaling. Periksa URL server di tab Server.",
                )
                activeControllerSession = null
            }
        }
    }

    fun disconnectControllerSession() {
        pingLoopJob?.cancel()
        pingLoopJob = null
        activeControllerSession?.let { active ->
            sendSessionEnd(active.targetId, active.sessionId, "Pengendali mengakhiri sesi.")
        }
        activeControllerSession = null
        webRtc.stopSession()
        _controllerState.value = ControllerSessionState.Idle
    }

    fun sendTouchTap(xNorm: Float, yNorm: Float, isLongPress: Boolean = false) {
        webRtc.sendControlPacket(RemoteControlPacket.TouchTap(xNorm, yNorm, isLongPress))
    }

    fun sendTouchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 200L) {
        webRtc.sendControlPacket(RemoteControlPacket.TouchSwipe(startX, startY, endX, endY, durationMs))
    }

    fun sendMouseMove(xNorm: Float, yNorm: Float) {
        webRtc.sendControlPacket(RemoteControlPacket.MouseMove(xNorm, yNorm))
    }

    fun sendMouseButton(
        xNorm: Float,
        yNorm: Float,
        button: RemoteMouseButton,
        action: RemoteMouseAction,
    ) {
        webRtc.sendControlPacket(RemoteControlPacket.MouseButton(xNorm, yNorm, button, action))
    }

    fun sendMouseScroll(xNorm: Float, yNorm: Float, deltaX: Float, deltaY: Float) {
        webRtc.sendControlPacket(RemoteControlPacket.MouseScroll(xNorm, yNorm, deltaX, deltaY))
    }

    fun sendKeyEvent(
        key: String,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
    ) {
        if (key.isNotBlank()) {
            webRtc.sendControlPacket(RemoteControlPacket.KeyEvent(key, ctrl, alt, shift, meta))
        }
    }

    fun sendSystemAction(action: RemoteSystemAction) {
        webRtc.sendControlPacket(RemoteControlPacket.SystemKey(action))
    }

    fun sendTextInput(text: String) {
        if (text.isNotEmpty()) {
            webRtc.sendControlPacket(RemoteControlPacket.TextInput(text))
        }
    }

    fun changeStreamQuality(preset: StreamQualityPreset) {
        scope.launch {
            preferences.updateQualityPreset(preset)
            webRtc.updateQualityPreset(preset)
        }
    }

    private suspend fun handleSignalingEvent(event: SignalingEvent) {
        when (event) {
            is SignalingEvent.E2eeHello -> handleHostIncomingHello(event)
            is SignalingEvent.E2eeAck -> handleControllerAck(event)
            is SignalingEvent.E2eeReject -> handlePeerReject(event)
            is SignalingEvent.EncryptedWebRtcOffer -> handleHostOffer(event)
            is SignalingEvent.EncryptedWebRtcAnswer -> handleControllerAnswer(event)
            is SignalingEvent.EncryptedIceCandidate -> handleIncomingIceCandidate(event)
            is SignalingEvent.SessionEnded -> handleRemoteSessionEnded(event)
            is SignalingEvent.PeerOffline -> {
                if (activeControllerSession?.sessionId == event.sessionId) {
                    activeControllerSession = null
                    _controllerState.value = ControllerSessionState.Error(event.message)
                }
            }
            is SignalingEvent.ServerError -> {
                if (_controllerState.value is ControllerSessionState.ConnectingSignaling) {
                    _controllerState.value = ControllerSessionState.Error(event.message)
                }
            }
            is SignalingEvent.Registered -> Unit
        }
    }

    private suspend fun handleHostIncomingHello(hello: SignalingEvent.E2eeHello) {
        if (_hostStatus.value !is HostStatus.WaitingForPeer) {
            signaling.sendPayload(
                JSONObject()
                    .put("type", "e2ee_reject")
                    .put("targetId", hello.fromId)
                    .put("sessionId", hello.sessionId)
                    .put("reason", "HOST_NOT_READY")
                    .put("message", "Mode Host sedang tidak aktif atau sibuk."),
            )
            return
        }

        val identity = preferences.ensureInitialized()
        val hostInit = crypto.createEphemeralInit()
        val derived = runCatching {
            crypto.deriveSessionKeys(
                localKeyPair = hostInit.keyPair,
                remotePublicKeyB64 = hello.controllerPubKeyB64,
                controllerNonceB64 = hello.controllerNonceB64,
                hostNonceB64 = hostInit.nonceB64,
                sessionPassword = identity.sessionPassword,
                isController = false,
            )
        }.getOrNull() ?: return

        val expectedClientProof = crypto.computeRoleProof(derived.authConfirmKey, "CLIENT", hello.sessionId)
        val hostProof = crypto.computeRoleProof(derived.authConfirmKey, "HOST", hello.sessionId)

        val pending = PendingHostSession(
            sessionId = hello.sessionId,
            fromId = hello.fromId,
            fromAlias = hello.alias,
            derivedKeys = derived,
            clientProofExpected = expectedClientProof,
            hostProof = hostProof,
            hostInit = hostInit,
            controllerNonceB64 = hello.controllerNonceB64,
        )
        pendingHostHandshake = pending

        val isTrusted = preferences.isTrustedController(hello.fromId)
        val requireVisualApproval = preferences.requireVisualApprovalFlow.first()
        if (requireVisualApproval && !isTrusted) {
            _incomingRequest.value = IncomingConnectionRequest(
                sessionId = hello.sessionId,
                fromDeviceId = hello.fromId,
                fromAlias = hello.alias,
                safetyFingerprint = derived.safetyFingerprint,
            )
        } else {
            scope.launch {
                preferences.recordTrustedController(
                    TrustedDeviceEntry(
                        deviceId = hello.fromId,
                        alias = hello.alias,
                        lastConnectedEpochMs = System.currentTimeMillis(),
                        lastFingerprint = derived.safetyFingerprint,
                    )
                )
            }
            finalizeHostHandshakeAck(pending)
        }
    }

    private suspend fun finalizeHostHandshakeAck(pending: PendingHostSession) {
        val identity = preferences.ensureInitialized()
        signaling.sendPayload(
            JSONObject()
                .put("type", "e2ee_ack")
                .put("targetId", pending.fromId)
                .put("sessionId", pending.sessionId)
                .put("pubKey", pending.hostInit.publicKeyB64)
                .put("nonce", pending.hostInit.nonceB64)
                .put("hostProof", pending.hostProof)
                .put("alias", identity.deviceAlias),
        )
    }

    private suspend fun handleControllerAck(ack: SignalingEvent.E2eeAck) {
        val ctrl = activeControllerSession ?: return
        if (ctrl.sessionId != ack.sessionId) return

        _controllerState.value = ControllerSessionState.HandshakingE2ee(ctrl.targetId)

        val derived = runCatching {
            crypto.deriveSessionKeys(
                localKeyPair = ctrl.init.keyPair,
                remotePublicKeyB64 = ack.hostPubKeyB64,
                controllerNonceB64 = ctrl.init.nonceB64,
                hostNonceB64 = ack.hostNonceB64,
                sessionPassword = ctrl.targetPassword,
                isController = true,
            )
        }.getOrElse {
            _controllerState.value = ControllerSessionState.Error("Gagal melakukan pertukaran kunci ECDH.")
            activeControllerSession = null
            return
        }

        val expectedHostProof = crypto.computeRoleProof(derived.authConfirmKey, "HOST", ctrl.sessionId)
        if (!crypto.verifyProofConstantTime(expectedHostProof, ack.hostProofB64)) {
            _controllerState.value = ControllerSessionState.Error(
                "Kata sandi sesi salah atau verifikasi kunci E2EE gagal.",
            )
            activeControllerSession = null
            return
        }

        ctrl.derivedKeys = derived
        ctrl.targetAlias = ack.hostAlias
        ctrl.clientProofB64 = crypto.computeRoleProof(derived.authConfirmKey, "CLIENT", ctrl.sessionId)
        _controllerState.value = ControllerSessionState.NegotiatingIce(ctrl.targetId, derived.safetyFingerprint)

        preferences.recordRecentDevice(
            TrustedDeviceEntry(
                deviceId = ctrl.targetId,
                alias = ack.hostAlias,
                lastConnectedEpochMs = System.currentTimeMillis(),
                lastFingerprint = derived.safetyFingerprint,
                savedPassword = if (ctrl.rememberPassword) ctrl.targetPassword else "",
            ),
        )

        val preset = preferences.qualityPresetFlow.first()
        webRtc.startSession(
            iceServers = signaling.getPeerConnectionIceServers(),
            aesSessionKey = derived.aesSessionKey,
            safetyFingerprint = derived.safetyFingerprint,
            isController = true,
            mediaProjectionIntent = null,
            qualityPreset = preset,
            sessionCallbacks = object : WebRtcRemoteManager.Callbacks {
                override fun onLocalSdpCreated(sdp: SessionDescription) {
                    val sdpJson = JSONObject()
                        .put("sdpType", sdp.type.canonicalForm())
                        .put("sdp", sdp.description)
                        .toString()
                    val enc = crypto.encryptTextToBase64(
                        aesKey = derived.aesSessionKey,
                        text = sdpJson,
                        sequenceNumber = ctrl.outgoingSigSeq.getAndIncrement(),
                        direction = E2eeCryptoEngine.DIR_CONTROLLER_TO_HOST,
                    )
                    signaling.sendPayload(
                        JSONObject()
                            .put("type", "webrtc_offer")
                            .put("targetId", ctrl.targetId)
                            .put("sessionId", ctrl.sessionId)
                            .put("clientProof", ctrl.clientProofB64)
                            .put("payload", enc),
                    )
                }

                override fun onLocalIceCandidate(candidate: IceCandidate) {
                    val iceJson = JSONObject()
                        .put("sdpMid", candidate.sdpMid)
                        .put("sdpMLineIndex", candidate.sdpMLineIndex)
                        .put("sdp", candidate.sdp)
                        .toString()
                    val enc = crypto.encryptTextToBase64(
                        aesKey = derived.aesSessionKey,
                        text = iceJson,
                        sequenceNumber = ctrl.outgoingSigSeq.getAndIncrement(),
                        direction = E2eeCryptoEngine.DIR_CONTROLLER_TO_HOST,
                    )
                    signaling.sendPayload(
                        JSONObject()
                            .put("type", "webrtc_ice")
                            .put("targetId", ctrl.targetId)
                            .put("sessionId", ctrl.sessionId)
                            .put("payload", enc),
                    )
                }

                override fun onPeerConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
                    scope.launch {
                        when (state) {
                            PeerConnection.PeerConnectionState.CONNECTED -> {
                                _controllerState.value = ControllerSessionState.Connected(
                                    targetId = ctrl.targetId,
                                    targetAlias = ctrl.targetAlias,
                                    telemetry = webRtc.telemetry.value.copy(
                                        e2eeVerified = true,
                                        safetyFingerprint = derived.safetyFingerprint,
                                    ),
                                )
                                startControllerHeartbeatLoop()
                            }
                            PeerConnection.PeerConnectionState.FAILED,
                            PeerConnection.PeerConnectionState.DISCONNECTED,
                            PeerConnection.PeerConnectionState.CLOSED -> {
                                if (_controllerState.value is ControllerSessionState.Connected ||
                                    _controllerState.value is ControllerSessionState.NegotiatingIce
                                ) {
                                    disconnectControllerSession()
                                }
                            }
                            else -> Unit
                        }
                    }
                }

                override fun onControlPacketReceived(packet: RemoteControlPacket) {
                    if (packet is RemoteControlPacket.HeartbeatPong) {
                        val rtt = (System.currentTimeMillis() - packet.echoedEpochMs).toInt()
                        if (rtt in 1..15_000) {
                            webRtc.updateMeasuredRtt(rtt)
                        }
                    }
                }

                override fun onScreenCaptureStopped() = Unit

                override fun onError(message: String) {
                    scope.launch {
                        _controllerState.value = ControllerSessionState.Error(message)
                    }
                }
            },
        )
        webRtc.createOffer()
    }

    private suspend fun handleHostOffer(offer: SignalingEvent.EncryptedWebRtcOffer) {
        val pending = pendingHostHandshake ?: return
        if (pending.sessionId != offer.sessionId) return

        // Mutual password proof check before touching SDP
        if (!crypto.verifyProofConstantTime(pending.clientProofExpected, offer.clientProofB64)) {
            failedAuthAttempts++
            val backoffMs = (failedAuthAttempts * 500L).coerceAtMost(4_000L)
            delay(backoffMs)
            signaling.sendPayload(
                JSONObject()
                    .put("type", "e2ee_reject")
                    .put("targetId", offer.fromId)
                    .put("sessionId", offer.sessionId)
                    .put("reason", "AUTH_FAILED")
                    .put("message", "Kata sandi sesi tidak cocok."),
            )
            pendingHostHandshake = null
            return
        }
        failedAuthAttempts = 0

        val decrypted = runCatching {
            crypto.decryptBase64ToText(
                aesKey = pending.derivedKeys.aesSessionKey,
                payloadB64 = offer.encryptedSdpB64,
                expectedDirection = E2eeCryptoEngine.DIR_CONTROLLER_TO_HOST,
                minSequenceNumber = 1L,
            )
        }.getOrNull() ?: return

        val sdpObj = JSONObject(decrypted.second)
        val remoteSdp = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(sdpObj.getString("sdpType")),
            sdpObj.getString("sdp"),
        )

        val hostSession = ActivePeerSession(
            sessionId = pending.sessionId,
            peerId = pending.fromId,
            peerAlias = pending.fromAlias,
            aesKey = pending.derivedKeys.aesSessionKey,
            safetyFingerprint = pending.derivedKeys.safetyFingerprint,
            expectedSigSeq = decrypted.first + 1L,
        )
        activeHostSession = hostSession
        pendingHostHandshake = null
        scope.launch {
            preferences.recordTrustedController(
                TrustedDeviceEntry(
                    deviceId = hostSession.peerId,
                    alias = hostSession.peerAlias,
                    lastConnectedEpochMs = System.currentTimeMillis(),
                    lastFingerprint = hostSession.safetyFingerprint,
                )
            )
        }

        val preset = preferences.qualityPresetFlow.first()
        webRtc.startSession(
            iceServers = signaling.getPeerConnectionIceServers(),
            aesSessionKey = hostSession.aesKey,
            safetyFingerprint = hostSession.safetyFingerprint,
            isController = false,
            mediaProjectionIntent = projectionIntent,
            qualityPreset = preset,
            sessionCallbacks = object : WebRtcRemoteManager.Callbacks {
                override fun onLocalSdpCreated(sdp: SessionDescription) {
                    val sdpJson = JSONObject()
                        .put("sdpType", sdp.type.canonicalForm())
                        .put("sdp", sdp.description)
                        .toString()
                    val enc = crypto.encryptTextToBase64(
                        aesKey = hostSession.aesKey,
                        text = sdpJson,
                        sequenceNumber = hostSession.outgoingSigSeq.getAndIncrement(),
                        direction = E2eeCryptoEngine.DIR_HOST_TO_CONTROLLER,
                    )
                    signaling.sendPayload(
                        JSONObject()
                            .put("type", "webrtc_answer")
                            .put("targetId", hostSession.peerId)
                            .put("sessionId", hostSession.sessionId)
                            .put("payload", enc),
                    )
                }

                override fun onLocalIceCandidate(candidate: IceCandidate) {
                    val iceJson = JSONObject()
                        .put("sdpMid", candidate.sdpMid)
                        .put("sdpMLineIndex", candidate.sdpMLineIndex)
                        .put("sdp", candidate.sdp)
                        .toString()
                    val enc = crypto.encryptTextToBase64(
                        aesKey = hostSession.aesKey,
                        text = iceJson,
                        sequenceNumber = hostSession.outgoingSigSeq.getAndIncrement(),
                        direction = E2eeCryptoEngine.DIR_HOST_TO_CONTROLLER,
                    )
                    signaling.sendPayload(
                        JSONObject()
                            .put("type", "webrtc_ice")
                            .put("targetId", hostSession.peerId)
                            .put("sessionId", hostSession.sessionId)
                            .put("payload", enc),
                    )
                }

                override fun onPeerConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
                    scope.launch {
                        when (state) {
                            PeerConnection.PeerConnectionState.CONNECTED -> {
                                _hostStatus.value = HostStatus.Connected(
                                    controllerId = hostSession.peerId,
                                    controllerAlias = hostSession.peerAlias,
                                    telemetry = ConnectionTelemetry(
                                        e2eeVerified = true,
                                        safetyFingerprint = hostSession.safetyFingerprint,
                                    ),
                                )
                                RemoteHostService.updateStatus(
                                    appContext,
                                    "Terhubung E2EE dengan ${hostSession.peerAlias}",
                                )
                            }
                            PeerConnection.PeerConnectionState.DISCONNECTED,
                            PeerConnection.PeerConnectionState.FAILED,
                            PeerConnection.PeerConnectionState.CLOSED -> {
                                activeHostSession = null
                                androidDragStart = null
                                if (_hostStatus.value is HostStatus.Connected) {
                                    _hostStatus.value = HostStatus.WaitingForPeer()
                                    RemoteHostService.updateStatus(appContext, "Menunggu koneksi terenkripsi E2EE…")
                                }
                            }
                            else -> Unit
                        }
                    }
                }

                override fun onControlPacketReceived(packet: RemoteControlPacket) {
                    scope.launch { executeHostControlPacket(packet) }
                }

                override fun onScreenCaptureStopped() {
                    scope.launch { stopHostMode() }
                }

                override fun onError(message: String) {
                    scope.launch { _hostStatus.value = HostStatus.Error(message) }
                }
            },
        )
        webRtc.setRemoteDescriptionAndMaybeAnswer(remoteSdp)
    }

    private fun handleControllerAnswer(answer: SignalingEvent.EncryptedWebRtcAnswer) {
        val ctrl = activeControllerSession ?: return
        if (ctrl.sessionId != answer.sessionId) return
        val key = ctrl.derivedKeys?.aesSessionKey ?: return

        val decrypted = runCatching {
            crypto.decryptBase64ToText(
                aesKey = key,
                payloadB64 = answer.encryptedSdpB64,
                expectedDirection = E2eeCryptoEngine.DIR_HOST_TO_CONTROLLER,
                minSequenceNumber = ctrl.expectedSigSeq,
            )
        }.getOrNull() ?: return
        ctrl.expectedSigSeq = decrypted.first + 1L

        val sdpObj = JSONObject(decrypted.second)
        val remoteSdp = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(sdpObj.getString("sdpType")),
            sdpObj.getString("sdp"),
        )
        webRtc.setRemoteDescriptionAndMaybeAnswer(remoteSdp)
    }

    private fun handleIncomingIceCandidate(ice: SignalingEvent.EncryptedIceCandidate) {
        val ctrl = activeControllerSession
        if (ctrl != null && ctrl.sessionId == ice.sessionId) {
            val key = ctrl.derivedKeys?.aesSessionKey ?: return
            val decrypted = runCatching {
                crypto.decryptBase64ToText(
                    aesKey = key,
                    payloadB64 = ice.encryptedCandidateB64,
                    expectedDirection = E2eeCryptoEngine.DIR_HOST_TO_CONTROLLER,
                    minSequenceNumber = ctrl.expectedSigSeq,
                )
            }.getOrNull() ?: return
            ctrl.expectedSigSeq = decrypted.first + 1L
            val obj = JSONObject(decrypted.second)
            webRtc.addRemoteIceCandidate(
                IceCandidate(obj.getString("sdpMid"), obj.getInt("sdpMLineIndex"), obj.getString("sdp")),
            )
            return
        }

        val host = activeHostSession
        if (host != null && host.sessionId == ice.sessionId) {
            val decrypted = runCatching {
                crypto.decryptBase64ToText(
                    aesKey = host.aesKey,
                    payloadB64 = ice.encryptedCandidateB64,
                    expectedDirection = E2eeCryptoEngine.DIR_CONTROLLER_TO_HOST,
                    minSequenceNumber = host.expectedSigSeq,
                )
            }.getOrNull() ?: return
            activeHostSession = host.copy(expectedSigSeq = decrypted.first + 1L)
            val obj = JSONObject(decrypted.second)
            webRtc.addRemoteIceCandidate(
                IceCandidate(obj.getString("sdpMid"), obj.getInt("sdpMLineIndex"), obj.getString("sdp")),
            )
        }
    }

    private suspend fun executeHostControlPacket(packet: RemoteControlPacket) {
        if (packet is RemoteControlPacket.HeartbeatPing) {
            webRtc.sendControlPacket(RemoteControlPacket.HeartbeatPong(packet.sentEpochMs))
            return
        }
        if (packet is RemoteControlPacket.QualityChange) {
            webRtc.updateQualityPreset(packet.preset)
            return
        }
        val allowTouch = preferences.allowTouchControlFlow.first()
        if (!allowTouch) return

        when (packet) {
            is RemoteControlPacket.TouchTap -> {
                AccessibilityBridge.dispatchTap(packet.xNorm, packet.yNorm, packet.isLongPress)
            }
            is RemoteControlPacket.TouchSwipe -> {
                AccessibilityBridge.dispatchSwipe(
                    packet.startX,
                    packet.startY,
                    packet.endX,
                    packet.endY,
                    packet.durationMs,
                )
            }
            is RemoteControlPacket.MouseMove -> Unit
            is RemoteControlPacket.MouseButton -> {
                when (packet.button) {
                    RemoteMouseButton.LEFT -> when (packet.action) {
                        RemoteMouseAction.CLICK -> {
                            AccessibilityBridge.dispatchTap(packet.xNorm, packet.yNorm, false)
                        }
                        RemoteMouseAction.DOUBLE_CLICK -> {
                            AccessibilityBridge.dispatchTap(packet.xNorm, packet.yNorm, false)
                            delay(80L)
                            AccessibilityBridge.dispatchTap(packet.xNorm, packet.yNorm, false)
                        }
                        RemoteMouseAction.DOWN -> {
                            androidDragStart = packet.xNorm to packet.yNorm
                        }
                        RemoteMouseAction.UP -> {
                            val start = androidDragStart
                            androidDragStart = null
                            if (start != null) {
                                AccessibilityBridge.dispatchSwipe(
                                    start.first,
                                    start.second,
                                    packet.xNorm,
                                    packet.yNorm,
                                    200L,
                                )
                            }
                        }
                    }
                    RemoteMouseButton.RIGHT -> if (packet.action == RemoteMouseAction.CLICK) {
                        AccessibilityBridge.dispatchTap(packet.xNorm, packet.yNorm, true)
                    }
                    RemoteMouseButton.MIDDLE -> if (packet.action == RemoteMouseAction.CLICK) {
                        AccessibilityBridge.dispatchSystemAction(RemoteSystemAction.HOME)
                    }
                }
            }
            is RemoteControlPacket.MouseScroll -> {
                val endX = (packet.xNorm + packet.deltaX * 0.12f).coerceIn(0.05f, 0.95f)
                val endY = (packet.yNorm + packet.deltaY * 0.16f).coerceIn(0.05f, 0.95f)
                AccessibilityBridge.dispatchSwipe(packet.xNorm, packet.yNorm, endX, endY, 180L)
            }
            is RemoteControlPacket.KeyEvent -> {
                when (packet.key.trim().uppercase()) {
                    "BACKSPACE" -> AccessibilityBridge.dispatchBackspace()
                    "ENTER" -> AccessibilityBridge.dispatchTextInput("\n")
                    "ESCAPE" -> AccessibilityBridge.dispatchSystemAction(RemoteSystemAction.BACK)
                    "WIN", "HOME" -> AccessibilityBridge.dispatchSystemAction(RemoteSystemAction.HOME)
                }
            }
            is RemoteControlPacket.SystemKey -> {
                AccessibilityBridge.dispatchSystemAction(packet.action)
            }
            is RemoteControlPacket.TextInput -> {
                AccessibilityBridge.dispatchTextInput(packet.text)
            }
            else -> Unit
        }
    }

    private fun handlePeerReject(reject: SignalingEvent.E2eeReject) {
        if (activeControllerSession?.sessionId == reject.sessionId) {
            activeControllerSession = null
            _controllerState.value = ControllerSessionState.Error(reject.message)
        }
    }

    private fun handleRemoteSessionEnded(ended: SignalingEvent.SessionEnded) {
        if (activeControllerSession?.sessionId == ended.sessionId) {
            pingLoopJob?.cancel()
            activeControllerSession = null
            webRtc.stopSession()
            _controllerState.value = ControllerSessionState.Error(ended.reason)
        } else if (activeHostSession?.sessionId == ended.sessionId) {
            activeHostSession = null
            webRtc.stopSession()
            if (_hostStatus.value is HostStatus.Connected) {
                _hostStatus.value = HostStatus.WaitingForPeer()
                RemoteHostService.updateStatus(appContext, "Menunggu koneksi terenkripsi E2EE…")
            }
        }
    }

    private fun startControllerHeartbeatLoop() {
        pingLoopJob?.cancel()
        pingLoopJob = scope.launch {
            while (isActive && _controllerState.value is ControllerSessionState.Connected) {
                webRtc.sendControlPacket(RemoteControlPacket.HeartbeatPing(System.currentTimeMillis()))
                delay(2_000L)
            }
        }
    }

    private fun sendSessionEnd(targetId: String, sessionId: String, reason: String) {
        signaling.sendPayload(
            JSONObject()
                .put("type", "session_end")
                .put("targetId", targetId)
                .put("sessionId", sessionId)
                .put("reason", reason),
        )
    }

    companion object {
        @Volatile
        private var instance: RemoteSessionCoordinator? = null

        fun getInstance(context: Context): RemoteSessionCoordinator {
            return instance ?: synchronized(this) {
                instance ?: RemoteSessionCoordinator(context.applicationContext).also { instance = it }
            }
        }
    }
}
