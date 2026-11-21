using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Extensions.Logging;
using Microsoft.UI.Xaml.Controls;
using MultideviceRemote.Crypto;
using MultideviceRemote.Model;
using MultideviceRemote.Network;
using MultideviceRemote.Rendering;
using SIPSorcery.Net;
using SIPSorceryMedia.Encoders;
using SIPSorceryMedia.Windows;

namespace MultideviceRemote.WebRtc;

/// <summary>
/// Koordinator sesi remote Windows Controller end-to-end:
///  1. Signaling WebSocket -> Register & dapatkan ephemeral ICE servers
///  2. E2EE Handshake -> e2ee_hello (ECDH P-256 + Nonce) -> e2ee_ack -> verifikasi hostProof
///  3. WebRTC RTCPeerConnection -> MediaStreamTrack (RecvOnly) + WindowsVideoEndPoint
///  4. Enkripsi SDP Offer & Answer + ICE Candidates (AES-256-GCM envelope dengan sequence tracking)
///  5. WebRTC DataChannel "control" -> kirim Touch, Swipe, SystemKey binary envelope
///  6. Video Frame Sink -> VideoFrameRenderer -> SwapChainPanel (WriteableBitmap)
/// </summary>
public sealed class SessionCoordinator : IAsyncDisposable
{
    private readonly ILogger<SessionCoordinator> _logger;
    private readonly string _signalingUrl;
    private readonly string _myDeviceId;
    private readonly string _myAlias;
    private readonly string _targetDeviceId;
    private readonly string _targetPassword;
    private readonly SwapChainPanel _swapChainPanel;

    private SignalingClient? _signaling;
    private RTCPeerConnection? _peerConnection;
    private RTCDataChannel? _dataChannel;
    private WindowsVideoEndPoint? _videoEndPoint;
    private VideoFrameRenderer? _renderer;

    private readonly E2eeCryptoEngine _crypto = new();
    private E2eeCryptoEngine.EphemeralHandshakeInit? _myInit;
    private E2eeCryptoEngine.DerivedSessionKeys? _sessionKeys;
    private string _sessionId = Guid.NewGuid().ToString("N")[..8];

    // Sequence numbers (mirrors Android outgoingSigSeq & expectedSigSeq)
    private long _outgoingSigSeq = 1L;
    private long _expectedIncomingSigSeq = 1L;

    // DataChannel sequence numbers
    private long _outgoingDcSeq = 1L;
    private long _expectedIncomingDcSeq = 1L;

    private EphemeralIceConfig? _iceConfig;
    private CancellationTokenSource? _heartbeatCts;

    // ── Events ────────────────────────────────────────────────────────────────
    public event Action? OnConnected;
    public event Action<string>? OnDisconnected;
    public event Action<string>? OnError;
    public event Action<string>? OnStatusUpdate;
    public event Action<int, int>? OnFrameResolutionChanged;

    public SessionCoordinator(
        string signalingUrl,
        string myDeviceId,
        string myAlias,
        string targetDeviceId,
        string targetPassword,
        SwapChainPanel swapChainPanel)
    {
        _signalingUrl = signalingUrl;
        _myDeviceId = DeviceIdUtil.Normalize(myDeviceId);
        _myAlias = myAlias;
        _targetDeviceId = DeviceIdUtil.Normalize(targetDeviceId);
        _targetPassword = targetPassword;
        _swapChainPanel = swapChainPanel;
        _logger = App.LoggerFactory.CreateLogger<SessionCoordinator>();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public async Task StartAsync()
    {
        _signaling = new SignalingClient(_signalingUrl);

        _signaling.OnRegistered    += OnSignalingRegistered;
        _signaling.OnE2eeAck       += OnE2eeAck;
        _signaling.OnE2eeReject    += OnE2eeReject;
        _signaling.OnAnswer        += OnSignalingAnswer;
        _signaling.OnIceCandidate  += OnSignalingIceCandidate;
        _signaling.OnSessionEnded  += reason => OnDisconnected?.Invoke(reason);
        _signaling.OnDisconnected  += () => OnDisconnected?.Invoke("Signaling terputus.");
        _signaling.OnError         += err => OnError?.Invoke(err);

        OnStatusUpdate?.Invoke("Menghubungkan ke signaling server…");
        await _signaling.ConnectAsync(_myDeviceId, _myAlias);
    }

    /// <summary>
    /// Kirim RemoteControlPacket (Tap, Swipe, SystemKey, Text)
    /// terenkripsi AES-256-GCM binary envelope via WebRTC DataChannel.
    /// Format wire identik dengan WebRtcRemoteManager.kt di Android.
    /// </summary>
    public async Task SendInputAsync(RemoteControlPacket packet)
    {
        if (_dataChannel?.readyState != RTCDataChannelState.open) return;
        if (_sessionKeys == null) return;

        try
        {
            byte[] plain = ControlPacketCodec.Encode(packet);
            long seq = Interlocked.Increment(ref _outgoingDcSeq);

            byte[] encrypted = _crypto.EncryptEnvelope(
                aesKey: _sessionKeys.AesSessionKey,
                plaintext: plain,
                sequenceNumber: seq,
                direction: E2eeCryptoEngine.DirControllerToHost
            );

            _dataChannel.send(encrypted);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Gagal mengirim input packet via DataChannel");
        }

        await Task.CompletedTask;
    }

    public async Task DisconnectAsync()
    {
        _heartbeatCts?.Cancel();
        _peerConnection?.close();
        if (_signaling != null)
        {
            try
            {
                await _signaling.SendSessionEndAsync(_targetDeviceId, _sessionId, "Pengendali mengakhiri sesi.");
                await _signaling.DisconnectAsync();
            }
            catch { /* ignore cleanup errors */ }
        }
    }

    // ── Signaling Callbacks ───────────────────────────────────────────────────

    private async void OnSignalingRegistered(EphemeralIceConfig iceConfig)
    {
        _iceConfig = iceConfig;
        _logger.LogInformation("Signaling registered. Mengirim e2ee_hello ke {Target}...", _targetDeviceId);
        OnStatusUpdate?.Invoke("Terhubung ke signaling. Handshake enkripsi E2EE…");

        _myInit = _crypto.CreateEphemeralInit();
        _sessionId = Guid.NewGuid().ToString("N")[..8];

        await _signaling!.SendE2eeHelloAsync(
            targetId: _targetDeviceId,
            sessionId: _sessionId,
            pubKeyB64: _myInit.PublicKeyB64,
            nonceB64: _myInit.NonceB64,
            alias: _myAlias
        );
    }

    private async void OnE2eeAck(E2eeAckPayload ack)
    {
        if (ack.SessionId != _sessionId)
        {
            _logger.LogWarning("E2EE ack sessionId berbeda: {Ack} vs {Cur}", ack.SessionId, _sessionId);
            return;
        }
        if (_myInit == null || _iceConfig == null)
        {
            OnError?.Invoke("State error: Session init tidak valid.");
            return;
        }

        _logger.LogInformation("e2ee_ack diterima dari {Host} ({Alias})", ack.FromId, ack.HostAlias);
        OnStatusUpdate?.Invoke($"Memverifikasi kunci sesi dengan {ack.HostAlias}…");

        E2eeCryptoEngine.DerivedSessionKeys derived;
        try
        {
            derived = _crypto.DeriveSessionKeys(
                localKeyPair: _myInit.KeyPair,
                remotePublicKeyB64: ack.HostPubKeyB64,
                controllerNonceB64: _myInit.NonceB64,
                hostNonceB64: ack.HostNonceB64,
                sessionPassword: _targetPassword,
                isController: true
            );
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Derivasi kunci ECDH gagal");
            OnError?.Invoke("Gagal melakukan pertukaran kunci ECDH.");
            return;
        }

        // Verifikasi host proof
        var expectedHostProof = _crypto.ComputeRoleProof(derived.AuthConfirmKey, "HOST", _sessionId);
        if (!_crypto.VerifyProofConstantTime(expectedHostProof, ack.HostProofB64))
        {
            _logger.LogError("Host proof tidak cocok! Kata sandi salah.");
            OnError?.Invoke("Kata sandi sesi salah atau verifikasi kunci E2EE gagal.");
            CryptoUtils.SecureWipe(derived.AesSessionKey);
            CryptoUtils.SecureWipe(derived.AuthConfirmKey);
            return;
        }

        _sessionKeys = derived;
        var clientProofB64 = _crypto.ComputeRoleProof(derived.AuthConfirmKey, "CLIENT", _sessionId);

        _logger.LogInformation("E2EE terverifikasi. Fingerprint: {FP}", derived.SafetyFingerprint);
        OnStatusUpdate?.Invoke($"🔒 E2EE OK • {derived.SafetyFingerprint[..9]}… • Membuka WebRTC…");

        await CreatePeerConnectionAndOfferAsync(_iceConfig, clientProofB64);
    }

    private void OnE2eeReject(string reason)
    {
        _logger.LogWarning("E2EE reject diterima: {Reason}", reason);
        string message = reason switch
        {
            "HOST_NOT_READY" => "Mode Host belum aktif di perangkat target.",
            "USER_DECLINED"  => "Pemilik perangkat menolak permintaan koneksi.",
            "RATE_LIMITED"   => "Terlalu banyak percobaan gagal. Tunggu 1 menit.",
            _                => $"Koneksi ditolak: {reason}"
        };
        OnError?.Invoke(message);
    }

    private async void OnSignalingAnswer(string fromId, string payloadEncryptedB64)
    {
        if (_peerConnection == null || _sessionKeys == null) return;
        _logger.LogInformation("WebRTC answer diterima dari {From}", fromId);

        try
        {
            var (seq, plainJson) = _crypto.DecryptBase64ToText(
                aesKey: _sessionKeys.AesSessionKey,
                payloadB64: payloadEncryptedB64,
                expectedDirection: E2eeCryptoEngine.DirHostToController,
                minSequenceNumber: _expectedIncomingSigSeq
            );
            _expectedIncomingSigSeq = seq + 1L;

            var node = JsonNode.Parse(plainJson);
            var sdp = node?["sdp"]?.GetValue<string>() ?? "";

            var desc = new RTCSessionDescriptionInit
            {
                type = RTCSdpType.answer,
                sdp = sdp
            };
            _peerConnection.setRemoteDescription(desc);
            OnStatusUpdate?.Invoke("WebRTC answer terkonfigurasi. Memulai streaming…");
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Gagal mendekripsi atau memproses SDP Answer");
            OnError?.Invoke("Gagal memproses SDP answer dari host.");
        }
    }

    private void OnSignalingIceCandidate(string fromId, string payloadEncryptedB64)
    {
        if (_peerConnection == null || _sessionKeys == null) return;

        try
        {
            var (seq, plainJson) = _crypto.DecryptBase64ToText(
                aesKey: _sessionKeys.AesSessionKey,
                payloadB64: payloadEncryptedB64,
                expectedDirection: E2eeCryptoEngine.DirHostToController,
                minSequenceNumber: _expectedIncomingSigSeq
            );
            _expectedIncomingSigSeq = seq + 1L;

            var node = JsonNode.Parse(plainJson);
            var sdp = node?["sdp"]?.GetValue<string>() ?? "";
            var sdpMid = node?["sdpMid"]?.GetValue<string>() ?? "";
            var sdpMLineIndex = node?["sdpMLineIndex"]?.GetValue<ushort>() ?? 0;

            _peerConnection.addIceCandidate(new RTCIceCandidateInit
            {
                candidate = sdp,
                sdpMid = sdpMid,
                sdpMLineIndex = sdpMLineIndex
            });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Gagal mendekripsi atau menambahkan ICE candidate");
        }
    }

    // ── PeerConnection Setup ──────────────────────────────────────────────────

    private async Task CreatePeerConnectionAndOfferAsync(EphemeralIceConfig iceConfig, string clientProofB64)
    {
        var config = new RTCConfiguration
        {
            iceServers = iceConfig.IceServers.Select(s => new RTCIceServer
            {
                urls = string.Join(",", s.Urls),
                username = s.Username,
                credential = s.Credential
            }).ToList()
        };

        _peerConnection = new RTCPeerConnection(config);

        // 1. DataChannel untuk kontrol input
        var dcInit = new RTCDataChannelInit { ordered = true };
        _dataChannel = await _peerConnection.createDataChannel("control", dcInit);

        _dataChannel.onopen += () =>
        {
            _logger.LogInformation("DataChannel 'control' terbuka.");
            OnStatusUpdate?.Invoke("✅ Terhubung! Kontrol layar aktif.");
            OnConnected?.Invoke();
            StartHeartbeatLoop();
        };

        _dataChannel.onmessage += (dc, proto, data) =>
        {
            if (_sessionKeys == null) return;
            try
            {
                var (seq, plain) = _crypto.DecryptEnvelope(
                    aesKey: _sessionKeys.AesSessionKey,
                    envelope: data,
                    expectedDirection: E2eeCryptoEngine.DirHostToController,
                    minSequenceNumber: _expectedIncomingDcSeq
                );
                _expectedIncomingDcSeq = seq + 1L;

                var packet = ControlPacketCodec.Decode(plain);
                if (packet is HeartbeatPongPacket pong)
                {
                    long rtt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - pong.EchoedEpochMs;
                    _logger.LogDebug("DataChannel RTT: {Rtt}ms", rtt);
                }
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Gagal memproses pesan masuk dari DataChannel");
            }
        };

        // 2. Video Track & Endpoint (RecvOnly dengan VP8 decoder)
        _renderer = new VideoFrameRenderer(_swapChainPanel);
        _renderer.OnFrameResolutionChanged += (w, h) => OnFrameResolutionChanged?.Invoke(w, h);
        _videoEndPoint = new WindowsVideoEndPoint(new VpxVideoEncoder());

        var videoTrack = new MediaStreamTrack(
            _videoEndPoint.GetVideoSinkFormats(),
            MediaStreamStatusEnum.RecvOnly
        );
        _peerConnection.addTrack(videoTrack);

        _peerConnection.OnVideoFrameReceived += _videoEndPoint.GotVideoFrame;
        _peerConnection.OnVideoFormatsNegotiated += formats =>
        {
            if (formats.Count > 0)
            {
                var first = formats[0];
                _logger.LogInformation("Video format dinegosiasikan: {Codec}", first.Codec);
                _videoEndPoint.SetVideoSinkFormat(first);
            }
        };

        _videoEndPoint.OnVideoSinkDecodedSample += (sample, width, height, stride, pixelFormat) =>
        {
            _renderer?.RenderDecodedSample(sample, (int)width, (int)height, stride, pixelFormat);
        };

        // 3. ICE Candidates -> Signaling
        _peerConnection.onicecandidate += candidate =>
        {
            if (candidate == null || _sessionKeys == null) return;

            var iceJson = JsonSerializer.Serialize(new
            {
                sdpMid = candidate.sdpMid,
                sdpMLineIndex = candidate.sdpMLineIndex,
                sdp = candidate.candidate
            });

            try
            {
                long seq = Interlocked.Increment(ref _outgoingSigSeq);
                var encrypted = _crypto.EncryptTextToBase64(
                    aesKey: _sessionKeys.AesSessionKey,
                    text: iceJson,
                    sequenceNumber: seq,
                    direction: E2eeCryptoEngine.DirControllerToHost
                );
                _ = _signaling?.SendEncryptedIceCandidateAsync(_targetDeviceId, encrypted, _sessionId);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Gagal mengenkripsi dan mengirim ICE candidate");
            }
        };

        // 4. Connection State Tracking
        _peerConnection.onconnectionstatechange += state =>
        {
            _logger.LogInformation("PeerConnection state: {State}", state);
            if (state == RTCPeerConnectionState.connected)
            {
                OnStatusUpdate?.Invoke("WebRTC P2P tersambung!");
            }
            else if (state == RTCPeerConnectionState.failed || state == RTCPeerConnectionState.disconnected)
            {
                OnDisconnected?.Invoke($"Koneksi WebRTC {state}");
            }
        };

        // 5. Buat Offer
        var offer = _peerConnection.createOffer();
        await _peerConnection.setLocalDescription(offer);

        var sdpJson = JsonSerializer.Serialize(new
        {
            sdpType = "offer",
            sdp = offer.sdp
        });

        long offerSeq = Interlocked.Increment(ref _outgoingSigSeq);
        var encryptedSdp = _crypto.EncryptTextToBase64(
            aesKey: _sessionKeys!.AesSessionKey,
            text: sdpJson,
            sequenceNumber: offerSeq,
            direction: E2eeCryptoEngine.DirControllerToHost
        );

        await _signaling!.SendEncryptedOfferAsync(_targetDeviceId, _sessionId, encryptedSdp, clientProofB64);
        _logger.LogInformation("Offer terenkripsi berhasil dikirim ke {Target}", _targetDeviceId);
        OnStatusUpdate?.Invoke("WebRTC offer dikirim. Menunggu respons host…");
    }

    private void StartHeartbeatLoop()
    {
        _heartbeatCts?.Cancel();
        _heartbeatCts = new CancellationTokenSource();
        var token = _heartbeatCts.Token;

        _ = Task.Run(async () =>
        {
            while (!token.IsCancellationRequested)
            {
                await Task.Delay(2500, token);
                if (_dataChannel?.readyState == RTCDataChannelState.open)
                {
                    await SendInputAsync(RemoteControlPacket.Ping());
                }
            }
        }, token);
    }

    // ── Dispose ───────────────────────────────────────────────────────────────

    public async ValueTask DisposeAsync()
    {
        await DisconnectAsync();
        _peerConnection?.Dispose();
        _renderer?.Dispose();
        _videoEndPoint?.Dispose();
        _myInit?.KeyPair?.Dispose();

        if (_sessionKeys != null)
        {
            CryptoUtils.SecureWipe(_sessionKeys.AesSessionKey);
            CryptoUtils.SecureWipe(_sessionKeys.AuthConfirmKey);
        }

        if (_signaling != null)
            await _signaling.DisposeAsync();
    }
}
