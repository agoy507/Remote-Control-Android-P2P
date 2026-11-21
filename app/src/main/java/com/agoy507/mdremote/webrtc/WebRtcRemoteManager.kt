package com.agoy507.mdremote.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import com.agoy507.mdremote.crypto.E2eeCryptoEngine
import com.agoy507.mdremote.model.ConnectionTelemetry
import com.agoy507.mdremote.model.ControlPacketCodec
import com.agoy507.mdremote.model.RemoteControlPacket
import com.agoy507.mdremote.model.RemoteRouteType
import com.agoy507.mdremote.model.StreamQualityPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.SecretKey

class WebRtcRemoteManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val cryptoEngine: E2eeCryptoEngine,
) {
    interface Callbacks {
        fun onLocalSdpCreated(sdp: SessionDescription)
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onPeerConnectionStateChanged(state: PeerConnection.PeerConnectionState)
        fun onControlPacketReceived(packet: RemoteControlPacket)
        fun onScreenCaptureStopped()
        fun onError(message: String)
    }

    val eglBase: EglBase by lazy { EglBase.create() }
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localVideoSender: RtpSender? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var attachedRenderer: VideoSink? = null

    private var aesKey: SecretKey? = null
    private var isControllerRole: Boolean = false
    private var callbacks: Callbacks? = null
    private var statsJob: Job? = null
    private var lastBytesCount: Long = 0L
    private var lastBytesTimestampMs: Long = 0L

    private val outgoingSeq = AtomicLong(1L)
    private val expectedIncomingSeq = AtomicLong(1L)

    private val _telemetry = MutableStateFlow(ConnectionTelemetry())
    val telemetry: StateFlow<ConnectionTelemetry> = _telemetry.asStateFlow()

    @Synchronized
    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        val created = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        factory = created
        return created
    }

    fun startSession(
        iceServers: List<PeerConnection.IceServer>,
        aesSessionKey: SecretKey,
        safetyFingerprint: String,
        isController: Boolean,
        mediaProjectionIntent: Intent?,
        qualityPreset: StreamQualityPreset,
        sessionCallbacks: Callbacks,
    ) {
        stopSession()
        this.callbacks = sessionCallbacks
        this.aesKey = aesSessionKey
        this.isControllerRole = isController
        outgoingSeq.set(1L)
        expectedIncomingSeq.set(1L)
        lastBytesCount = 0L
        lastBytesTimestampMs = System.currentTimeMillis()
        _telemetry.value = ConnectionTelemetry(
            e2eeVerified = true,
            safetyFingerprint = safetyFingerprint,
            routeType = RemoteRouteType.NEGOTIATING,
        )

        val pcFactory = ensureFactory()
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        }

        val pc = pcFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) = Unit
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit

                override fun onIceCandidate(candidate: IceCandidate) {
                    callbacks?.onLocalIceCandidate(candidate)
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                override fun onAddStream(stream: MediaStream?) = Unit
                override fun onRemoveStream(stream: MediaStream?) = Unit

                override fun onDataChannel(channel: DataChannel) {
                    bindDataChannel(channel)
                }

                override fun onRenegotiationNeeded() = Unit

                override fun onTrack(transceiver: RtpTransceiver?) {
                    val track = transceiver?.receiver?.track()
                    if (track is VideoTrack) {
                        remoteVideoTrack = track
                        track.setEnabled(true)
                        attachedRenderer?.let { renderer ->
                            track.addSink(renderer)
                        }
                    }
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    callbacks?.onPeerConnectionStateChanged(newState)
                }
            },
        ) ?: run {
            callbacks?.onError("Gagal menginisialisasi PeerConnection WebRTC.")
            return
        }

        peerConnection = pc

        if (isController) {
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
            )
            val dc = pc.createDataChannel(
                "kendali-e2ee-ctrl",
                DataChannel.Init().apply { ordered = true },
            )
            bindDataChannel(dc)
        } else {
            if (mediaProjectionIntent == null) {
                callbacks?.onError("Izin MediaProjection tidak tersedia untuk mode Host.")
                return
            }
            setupHostScreenCapture(pcFactory, pc, mediaProjectionIntent, qualityPreset)
        }

        startTelemetryLoop()
    }

    private fun setupHostScreenCapture(
        pcFactory: PeerConnectionFactory,
        pc: PeerConnection,
        projectionIntent: Intent,
        preset: StreamQualityPreset,
    ) {
        val capturer = ScreenCapturerAndroid(
            projectionIntent,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    callbacks?.onScreenCaptureStopped()
                }
            },
        )
        videoCapturer = capturer
        val helper = SurfaceTextureHelper.create("KendaliScreenCapture", eglBase.eglBaseContext)
        surfaceTextureHelper = helper
        val source = pcFactory.createVideoSource(true)
        videoSource = source
        capturer.initialize(helper, context.applicationContext, source.capturerObserver)
        runCatching {
            capturer.startCapture(preset.maxWidth, preset.maxHeight, preset.fps)
        }.onFailure { err ->
            callbacks?.onError("Gagal memulai tangkapan layar: ${err.message}")
            return
        }

        val track = pcFactory.createVideoTrack("KENDALI_SCREEN_V0", source)
        track.setEnabled(true)
        localVideoTrack = track
        localVideoSender = pc.addTrack(track, listOf("kendali-stream"))
        applyBitrateLimit(preset)
    }

    fun updateQualityPreset(preset: StreamQualityPreset) {
        if (!isControllerRole) {
            runCatching {
                videoCapturer?.changeCaptureFormat(preset.maxWidth, preset.maxHeight, preset.fps)
            }
            applyBitrateLimit(preset)
        } else {
            sendControlPacket(RemoteControlPacket.QualityChange(preset))
        }
    }

    private fun applyBitrateLimit(preset: StreamQualityPreset) {
        val sender = localVideoSender ?: return
        val params = sender.parameters ?: return
        params.degradationPreference = when (preset) {
            StreamQualityPreset.ULTRA_LOW_LATENCY -> RtpParameters.DegradationPreference.BALANCED
            else -> RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        }
        for (encoding in params.encodings) {
            encoding.maxBitrateBps = preset.maxBitrateKbps * 1000
            encoding.minBitrateBps = (preset.maxBitrateKbps * 350).coerceAtLeast(600_000)
            encoding.maxFramerate = preset.fps
            encoding.scaleResolutionDownBy = 1.0
        }
        sender.parameters = params
    }

    fun createOffer() {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isControllerRole) "true" else "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        pc.createOffer(
            object : SimpleSdpObserver("createOffer") {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    pc.setLocalDescription(SimpleSdpObserver("setLocalOffer"), sdp)
                    callbacks?.onLocalSdpCreated(sdp)
                }
            },
            constraints,
        )
    }

    fun setRemoteDescriptionAndMaybeAnswer(sdp: SessionDescription) {
        val pc = peerConnection ?: return
        pc.setRemoteDescription(
            object : SimpleSdpObserver("setRemoteDescription") {
                override fun onSetSuccess() {
                    if (sdp.type == SessionDescription.Type.OFFER) {
                        val constraints = MediaConstraints().apply {
                            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                        }
                        pc.createAnswer(
                            object : SimpleSdpObserver("createAnswer") {
                                override fun onCreateSuccess(answer: SessionDescription) {
                                    pc.setLocalDescription(SimpleSdpObserver("setLocalAnswer"), answer)
                                    callbacks?.onLocalSdpCreated(answer)
                                }
                            },
                            constraints,
                        )
                    }
                }
            },
            sdp,
        )
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun attachVideoRenderer(sink: VideoSink) {
        attachedRenderer = sink
        remoteVideoTrack?.addSink(sink)
    }

    fun detachVideoRenderer(sink: VideoSink) {
        if (attachedRenderer === sink) {
            remoteVideoTrack?.removeSink(sink)
            attachedRenderer = null
        }
    }

    fun sendControlPacket(packet: RemoteControlPacket): Boolean {
        val dc = dataChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        val key = aesKey ?: return false
        val direction = if (isControllerRole) {
            E2eeCryptoEngine.DIR_CONTROLLER_TO_HOST
        } else {
            E2eeCryptoEngine.DIR_HOST_TO_CONTROLLER
        }
        val plain = ControlPacketCodec.encode(packet)
        val encrypted = cryptoEngine.encryptEnvelope(
            aesKey = key,
            plaintext = plain,
            sequenceNumber = outgoingSeq.getAndIncrement(),
            direction = direction,
        )
        return dc.send(DataChannel.Buffer(ByteBuffer.wrap(encrypted), true))
    }

    fun updateMeasuredRtt(rttMs: Int) {
        _telemetry.value = _telemetry.value.copy(rttMs = rttMs.coerceAtLeast(1))
    }

    private fun bindDataChannel(channel: DataChannel) {
        dataChannel = channel
        channel.registerObserver(
            object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit
                override fun onStateChange() = Unit

                override fun onMessage(buffer: DataChannel.Buffer) {
                    val key = aesKey ?: return
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    val expectedDir = if (isControllerRole) {
                        E2eeCryptoEngine.DIR_HOST_TO_CONTROLLER
                    } else {
                        E2eeCryptoEngine.DIR_CONTROLLER_TO_HOST
                    }
                    runCatching {
                        val (seq, plain) = cryptoEngine.decryptEnvelope(
                            aesKey = key,
                            envelope = bytes,
                            expectedDirection = expectedDir,
                            minSequenceNumber = expectedIncomingSeq.get(),
                        )
                        expectedIncomingSeq.set(seq + 1L)
                        val packet = ControlPacketCodec.decode(plain)
                        callbacks?.onControlPacketReceived(packet)
                    }
                }
            },
        )
    }

    private fun startTelemetryLoop() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                delay(1_200L)
                val pc = peerConnection ?: continue
                pc.getStats { report ->
                    parseStatsReport(report)
                }
            }
        }
    }

    private fun parseStatsReport(report: RTCStatsReport) {
        var rttMs = _telemetry.value.rttMs
        var fps = _telemetry.value.fps
        var bitrateKbps = _telemetry.value.bitrateKbps
        var routeType = _telemetry.value.routeType
        var bytesNow = 0L

        val statsMap = report.statsMap
        for (stat in statsMap.values) {
            when (stat.type) {
                "candidate-pair" -> {
                    val state = stat.members["state"] as? String
                    val nominated = stat.members["nominated"] as? Boolean ?: false
                    if (state == "succeeded" || nominated) {
                        val rttSec = (stat.members["currentRoundTripTime"] as? Number)?.toDouble()
                        if (rttSec != null && rttSec > 0.0) {
                            rttMs = (rttSec * 1000.0).toInt().coerceAtLeast(1)
                        }
                        val localCandId = stat.members["localCandidateId"] as? String
                        val localCand = localCandId?.let { statsMap[it] }
                        val candType = localCand?.members?.get("candidateType") as? String
                        routeType = if (candType == "relay") {
                            RemoteRouteType.TURN_RELAY
                        } else {
                            RemoteRouteType.DIRECT_P2P
                        }
                    }
                }
                "inbound-rtp", "outbound-rtp" -> {
                    val kind = stat.members["kind"] as? String ?: stat.members["mediaType"] as? String
                    if (kind == "video") {
                        val framesPerSec = (stat.members["framesPerSecond"] as? Number)?.toInt()
                        if (framesPerSec != null && framesPerSec >= 0) {
                            fps = framesPerSec
                        }
                        val b = (stat.members["bytesReceived"] as? Number)?.toLong()
                            ?: (stat.members["bytesSent"] as? Number)?.toLong()
                        if (b != null) bytesNow += b
                    }
                }
            }
        }

        val now = System.currentTimeMillis()
        val elapsedMs = (now - lastBytesTimestampMs).coerceAtLeast(1L)
        if (bytesNow > 0L && lastBytesCount > 0L && bytesNow >= lastBytesCount) {
            val deltaBytes = bytesNow - lastBytesCount
            bitrateKbps = ((deltaBytes * 8L) / elapsedMs).toInt()
        }
        if (bytesNow > 0L) {
            lastBytesCount = bytesNow
            lastBytesTimestampMs = now
        }

        _telemetry.value = _telemetry.value.copy(
            rttMs = rttMs,
            fps = fps,
            bitrateKbps = bitrateKbps,
            routeType = routeType,
        )
    }

    fun stopSession() {
        statsJob?.cancel()
        statsJob = null

        runCatching { dataChannel?.unregisterObserver() }
        runCatching { dataChannel?.close() }
        dataChannel = null

        attachedRenderer?.let { renderer ->
            runCatching { remoteVideoTrack?.removeSink(renderer) }
        }
        remoteVideoTrack = null

        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null

        runCatching { localVideoTrack?.dispose() }
        localVideoTrack = null
        localVideoSender = null

        runCatching { videoSource?.dispose() }
        videoSource = null

        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null

        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null
        aesKey = null
        callbacks = null
    }

    private open class SimpleSdpObserver(private val tag: String) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }
}
