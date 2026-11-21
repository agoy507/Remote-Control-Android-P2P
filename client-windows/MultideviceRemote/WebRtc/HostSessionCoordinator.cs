using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Extensions.Logging;
using MultideviceRemote.Crypto;
using MultideviceRemote.Host;
using MultideviceRemote.Model;
using MultideviceRemote.Network;
using SIPSorcery.Net;

namespace MultideviceRemote.WebRtc;

/// <summary>
/// Koordinator sesi Mode Host (Target) pada PC Windows:
///  1. Terhubung ke signaling server & mempertahankan status online selama Host aktif
///  2. Menangani E2EE handshake masuk (e2ee_hello -> persetujuan/auto-trusted -> e2ee_ack)
///  3. Memverifikasi clientProof pada webrtc_offer sebelum memproses SDP
///  4. Membuka WebRTC RTCPeerConnection SendOnly VP8 (DesktopScreenCapturer)
///  5. Mendekripsi DataChannel control -> WindowsInputInjector (mouse/keyboard) & Ping/Pong & QualityChange
/// </summary>
public sealed class HostSessionCoordinator : IAsyncDisposable
{
    private readonly ILogger<HostSessionCoordinator> _logger;
    private readonly E2eeCryptoEngine _crypto = new();
    private readonly WindowsInputInjector _injector = new();
    private readonly object _sessionLock = new();

    private SignalingClient? _signaling;
    private CancellationTokenSource? _hostCts;
    private EphemeralIceConfig? _iceConfig;

    private string _signalingUrl = "";
    private string _apiToken = "";
    private string _myDeviceId = "";
    private string _myAlias = Environment.MachineName;
    private string _hostPassword = "";
    private bool _requireVisualApproval = true;
    private bool _allowInputControl = true;
    private string _qualityPreset = "BALANCED";
    private HashSet<string> _trustedDeviceIds = new(StringComparer.OrdinalIgnoreCase);

    private PendingHandshake? _pendingHandshake;
    private ActiveHostSession? _activeSession;
    private int _failedAuthAttempts;
    private volatile bool _stopping;

    public bool IsHostRunning { get; private set; }
    public bool HasActivePeer => _activeSession != null;

    // ── Events ────────────────────────────────────────────────────────────────
    public event Action<string>? OnStatusChanged;
    public event Action<bool>? OnSignalingStateChanged;
    public event Action<IncomingHostRequest>? OnIncomingRequest;
    public event Action<string, string, string>? OnPeerConnected;      // (peerId, peerAlias, fingerprint)
    public event Action<string>? OnPeerDisconnected;                 // (reason)
    public event Action<TrustedControllerEntry>? OnTrustedControllerRecorded;
    public event Action<string>? OnError;

    private sealed class PendingHandshake
    {
        public required string SessionId { get; init; }
        public required string FromId { get; init; }
        public required string FromAlias { get; init; }
        public required E2eeCryptoEngine.EphemeralHandshakeInit HostInit { get; init; }
        public required E2eeCryptoEngine.DerivedSessionKeys DerivedKeys { get; init; }
        public required string ExpectedClientProof { get; init; }
        public required string HostProof { get; init; }
    }

    private sealed class ActiveHostSession
    {
        public required string SessionId { get; init; }
        public required string PeerId { get; init; }
        public required string PeerAlias { get; init; }
        public required byte[] AesKey { get; init; }
        public required byte[] AuthKey { get; init; }
        public required string SafetyFingerprint { get; init; }
        public required RTCPeerConnection PeerConnection { get; init; }
        public required DesktopScreenCapturer Capturer { get; init; }
        public RTCDataChannel? DataChannel { get; set; }
        public long OutgoingSigSeq = 1L;
        public long ExpectedSigSeq = 1L;
        public long OutgoingDcSeq = 1L;
        public long ExpectedDcSeq = 1L;
    }

    public HostSessionCoordinator()
    {
        _logger = App.LoggerFactory.CreateLogger<HostSessionCoordinator>();
    }

    // ── Public Lifecycle ──────────────────────────────────────────────────────

    public async Task StartHostAsync(
        string signalingUrl,
        string apiToken,
        string myDeviceId,
        string myAlias,
        string hostPassword,
        bool requireVisualApproval,
        bool allowInputControl,
        string qualityPreset,
        IReadOnlyList<TrustedControllerEntry> trustedControllers)
    {
        await StopHostAsync();

        _stopping = false;
        _signalingUrl = signalingUrl;
        _apiToken = apiToken;
        _myDeviceId = DeviceIdUtil.Normalize(myDeviceId);
        _myAlias = string.IsNullOrWhiteSpace(myAlias) ? Environment.MachineName : myAlias.Trim();
        UpdateHostOptions(hostPassword, requireVisualApproval, allowInputControl, qualityPreset, trustedControllers);

        _hostCts = new CancellationTokenSource();
        IsHostRunning = true;

        OnStatusChanged?.Invoke("Menghubungkan ke server signaling…");
        await ConnectSignalingInternalAsync(_hostCts.Token);
    }

    public void UpdateHostOptions(
        string hostPassword,
        bool requireVisualApproval,
        bool allowInputControl,
        string qualityPreset,
        IReadOnlyList<TrustedControllerEntry> trustedControllers)
    {
        _hostPassword = hostPassword.Trim();
        _requireVisualApproval = requireVisualApproval;
        _allowInputControl = allowInputControl;
        _qualityPreset = string.IsNullOrWhiteSpace(qualityPreset) ? "BALANCED" : qualityPreset;
        _trustedDeviceIds = trustedControllers
            .Select(t => DeviceIdUtil.Normalize(t.DeviceId))
            .Where(id => id.Length > 0)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);

        _activeSession?.Capturer.UpdateQualityPreset(_qualityPreset);
    }

    public async Task StopHostAsync()
    {
        _stopping = true;
        IsHostRunning = false;

        _hostCts?.Cancel();
        _hostCts?.Dispose();
        _hostCts = null;

        await DisconnectActivePeerAsync("Host menonaktifkan Mode Host.");
        ClearPendingHandshake(disposeAll: true);

        if (_signaling != null)
        {
            await _signaling.DisposeAsync();
            _signaling = null;
        }

        OnSignalingStateChanged?.Invoke(false);
        OnStatusChanged?.Invoke("Mode Host nonaktif.");
    }

    /// <summary>Hentikan seluruh resource Host secara sinkron saat aplikasi ditutup.</summary>
    public void ForceStopForExit()
    {
        _stopping = true;
        IsHostRunning = false;

        try { _hostCts?.Cancel(); } catch { /* ignore */ }
        try { _hostCts?.Dispose(); } catch { /* ignore */ }
        _hostCts = null;

        ActiveHostSession? session;
        lock (_sessionLock)
        {
            session = _activeSession;
            _activeSession = null;
        }
        if (session != null)
        {
            DisposeActiveSessionResources(session);
        }

        ClearPendingHandshake(disposeAll: true);
        var sig = _signaling;
        _signaling = null;
        if (sig != null)
        {
            _ = Task.Run(async () => await sig.DisposeAsync());
        }
    }

    public async Task DisconnectActivePeerAsync(string reason = "Host memutus koneksi pengendali.")
    {
        ActiveHostSession? session;
        lock (_sessionLock)
        {
            session = _activeSession;
            _activeSession = null;
        }

        if (session == null) return;

        if (_signaling != null)
        {
            try
            {
                await _signaling.SendSessionEndAsync(session.PeerId, session.SessionId, reason);
            }
            catch { /* ignore */ }
        }

        DisposeActiveSessionResources(session);
        OnPeerDisconnected?.Invoke(reason);
        if (IsHostRunning)
        {
            OnStatusChanged?.Invoke("Menunggu koneksi terenkripsi E2EE…");
        }
    }

    public async Task ApprovePendingAsync()
    {
        var pending = _pendingHandshake;
        if (pending == null || _signaling == null) return;

        RecordTrustedController(pending.FromId, pending.FromAlias, pending.DerivedKeys.SafetyFingerprint);
        await SendAckForPendingAsync(pending);
    }

    public async Task RejectPendingAsync()
    {
        var pending = _pendingHandshake;
        if (pending == null) return;

        ClearPendingHandshake(disposeAll: true);

        if (_signaling != null)
        {
            await _signaling.SendE2eeRejectAsync(
                targetId: pending.FromId,
                sessionId: pending.SessionId,
                reason: "USER_DECLINED",
                message: "Pemilik perangkat menolak permintaan akses."
            );
        }

        if (IsHostRunning)
        {
            OnStatusChanged?.Invoke("Permintaan ditolak. Menunggu koneksi masuk…");
        }
    }

    // ── Signaling Connection & Reconnect ──────────────────────────────────────

    private async Task ConnectSignalingInternalAsync(CancellationToken ct)
    {
        var sig = new SignalingClient(_signalingUrl, _apiToken);
        _signaling = sig;

        sig.OnRegistered += ice =>
        {
            _iceConfig = ice;
            OnSignalingStateChanged?.Invoke(true);
            if (_activeSession == null)
            {
                OnStatusChanged?.Invoke($"Standby • Siap dikendalikan (ID: {DeviceIdUtil.Format(_myDeviceId)})");
            }
        };

        sig.OnE2eeHello += hello => _ = HandleIncomingHelloAsync(hello);
        sig.OnWebRtcOffer += offer => _ = HandleIncomingOfferAsync(offer);
        sig.OnWebRtcIce += ice => HandleIncomingIce(ice);

        sig.OnSessionEndedWithId += (sessionId, reason) =>
        {
            if (_pendingHandshake != null && (string.IsNullOrEmpty(sessionId) || _pendingHandshake.SessionId == sessionId))
            {
                ClearPendingHandshake(disposeAll: true);
                OnStatusChanged?.Invoke("Permintaan dibatalkan pengendali. Menunggu koneksi masuk…");
                return;
            }
            if (_activeSession != null && (string.IsNullOrEmpty(sessionId) || _activeSession.SessionId == sessionId))
            {
                CleanupActivePeerFromEvent(reason);
            }
        };

        sig.OnError += err =>
        {
            _logger.LogWarning("Host signaling error: {Err}", err);
            OnError?.Invoke(err);
        };

        sig.OnDisconnected += () =>
        {
            OnSignalingStateChanged?.Invoke(false);
            if (!_stopping && IsHostRunning && !ct.IsCancellationRequested)
            {
                OnStatusChanged?.Invoke("Signaling terputus. Menghubungkan ulang…");
                _ = ReconnectAfterDelayAsync(ct);
            }
        };

        try
        {
            await sig.ConnectAsync(_myDeviceId, _myAlias, ct);
        }
        catch (OperationCanceledException) { /* stopped */ }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Gagal menghubungkan Host ke signaling server");
            OnSignalingStateChanged?.Invoke(false);
            OnError?.Invoke($"Gagal terhubung ke signaling: {ex.Message}");
            if (!_stopping && IsHostRunning && !ct.IsCancellationRequested)
            {
                _ = ReconnectAfterDelayAsync(ct);
            }
        }
    }

    private async Task ReconnectAfterDelayAsync(CancellationToken ct)
    {
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(3), ct);
            if (_stopping || !IsHostRunning || ct.IsCancellationRequested) return;

            if (_signaling != null)
            {
                await _signaling.DisposeAsync();
                _signaling = null;
            }
            await ConnectSignalingInternalAsync(ct);
        }
        catch (OperationCanceledException) { /* stopped */ }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Host reconnect gagal");
        }
    }

    // ── E2EE Handshake (Host Side) ────────────────────────────────────────────

    private async Task HandleIncomingHelloAsync(E2eeHelloPayload hello)
    {
        if (!IsHostRunning || _signaling == null) return;

        if (_activeSession != null || _pendingHandshake != null)
        {
            await _signaling.SendE2eeRejectAsync(
                targetId: hello.FromId,
                sessionId: hello.SessionId,
                reason: "HOST_NOT_READY",
                message: "PC Host sedang dalam sesi atau menunggu konfirmasi lain."
            );
            return;
        }

        E2eeCryptoEngine.EphemeralHandshakeInit? hostInit = null;
        E2eeCryptoEngine.DerivedSessionKeys derived;
        try
        {
            hostInit = _crypto.CreateEphemeralInit();
            derived = _crypto.DeriveSessionKeys(
                localKeyPair: hostInit.KeyPair,
                remotePublicKeyB64: hello.ControllerPubKeyB64,
                controllerNonceB64: hello.ControllerNonceB64,
                hostNonceB64: hostInit.NonceB64,
                sessionPassword: _hostPassword,
                isController: false
            );
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Gagal memproses e2ee_hello dari {FromId}", hello.FromId);
            hostInit?.KeyPair.Dispose();
            return;
        }

        var expectedClientProof = _crypto.ComputeRoleProof(derived.AuthConfirmKey, "CLIENT", hello.SessionId);
        var hostProof = _crypto.ComputeRoleProof(derived.AuthConfirmKey, "HOST", hello.SessionId);

        var pending = new PendingHandshake
        {
            SessionId = hello.SessionId,
            FromId = hello.FromId,
            FromAlias = hello.ControllerAlias,
            HostInit = hostInit,
            DerivedKeys = derived,
            ExpectedClientProof = expectedClientProof,
            HostProof = hostProof
        };
        _pendingHandshake = pending;

        bool isTrusted = _trustedDeviceIds.Contains(DeviceIdUtil.Normalize(hello.FromId));
        if (_requireVisualApproval && !isTrusted)
        {
            _logger.LogInformation("Meminta persetujuan masuk dari {Alias} ({Id})", hello.ControllerAlias, hello.FromId);
            OnStatusChanged?.Invoke($"Permintaan koneksi dari {hello.ControllerAlias}…");
            OnIncomingRequest?.Invoke(new IncomingHostRequest(
                SessionId: hello.SessionId,
                FromDeviceId: hello.FromId,
                FromAlias: hello.ControllerAlias,
                SafetyFingerprint: derived.SafetyFingerprint
            ));
        }
        else
        {
            _logger.LogInformation("Auto-approve pengendali terpercaya: {Alias} ({Id})", hello.ControllerAlias, hello.FromId);
            RecordTrustedController(hello.FromId, hello.ControllerAlias, derived.SafetyFingerprint);
            await SendAckForPendingAsync(pending);
        }
    }

    private async Task SendAckForPendingAsync(PendingHandshake pending)
    {
        if (_signaling == null) return;
        OnStatusChanged?.Invoke($"Handshake E2EE dengan {pending.FromAlias}…");
        await _signaling.SendE2eeAckAsync(
            targetId: pending.FromId,
            sessionId: pending.SessionId,
            pubKeyB64: pending.HostInit.PublicKeyB64,
            nonceB64: pending.HostInit.NonceB64,
            hostProofB64: pending.HostProof,
            alias: _myAlias
        );
    }

    // ── WebRTC Offer / Answer / ICE ───────────────────────────────────────────

    private async Task HandleIncomingOfferAsync(WebRtcOfferPayload offer)
    {
        var pending = _pendingHandshake;
        if (pending == null || pending.SessionId != offer.SessionId || _signaling == null)
            return;

        if (!_crypto.VerifyProofConstantTime(pending.ExpectedClientProof, offer.ClientProofB64))
        {
            _failedAuthAttempts++;
            int backoffMs = Math.Min(_failedAuthAttempts * 500, 4000);
            _logger.LogWarning("ClientProof tidak cocok dari {FromId} (attempt={Attempt})", offer.FromId, _failedAuthAttempts);
            await Task.Delay(backoffMs);

            await _signaling.SendE2eeRejectAsync(
                targetId: offer.FromId,
                sessionId: offer.SessionId,
                reason: "AUTH_FAILED",
                message: "Kata sandi sesi tidak cocok."
            );
            ClearPendingHandshake(disposeAll: true);
            if (IsHostRunning)
            {
                OnStatusChanged?.Invoke("Otentikasi pengendali gagal. Menunggu koneksi masuk…");
            }
            return;
        }
        _failedAuthAttempts = 0;

        string sdpString;
        long incomingSeq;
        try
        {
            var (seq, plainJson) = _crypto.DecryptBase64ToText(
                aesKey: pending.DerivedKeys.AesSessionKey,
                payloadB64: offer.EncryptedSdpB64,
                expectedDirection: E2eeCryptoEngine.DirControllerToHost,
                minSequenceNumber: 1L
            );
            incomingSeq = seq;
            var node = JsonNode.Parse(plainJson);
            sdpString = node?["sdp"]?.GetValue<string>() ?? "";
            if (string.IsNullOrEmpty(sdpString))
                throw new InvalidOperationException("SDP offer kosong.");
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Gagal mendekripsi WebRTC offer dari {FromId}", offer.FromId);
            ClearPendingHandshake(disposeAll: true);
            return;
        }

        // Promote pending -> active (dispose ECDH keypair, pindahkan kepemilikan keys ke ActiveHostSession)
        _pendingHandshake = null;
        pending.HostInit.KeyPair.Dispose();

        RecordTrustedController(pending.FromId, pending.FromAlias, pending.DerivedKeys.SafetyFingerprint);

        var iceServers = BuildRtcIceServers();
        var pc = new RTCPeerConnection(new RTCConfiguration { iceServers = iceServers });
        var capturer = new DesktopScreenCapturer(_qualityPreset);

        var activeSession = new ActiveHostSession
        {
            SessionId = pending.SessionId,
            PeerId = pending.FromId,
            PeerAlias = pending.FromAlias,
            AesKey = pending.DerivedKeys.AesSessionKey,
            AuthKey = pending.DerivedKeys.AuthConfirmKey,
            SafetyFingerprint = pending.DerivedKeys.SafetyFingerprint,
            PeerConnection = pc,
            Capturer = capturer,
            ExpectedSigSeq = incomingSeq + 1L
        };

        lock (_sessionLock)
        {
            _activeSession = activeSession;
        }

        // Track Video SendOnly (VP8 via VpxVideoEncoder)
        var videoTrack = new MediaStreamTrack(
            capturer.GetVideoSourceFormats(),
            MediaStreamStatusEnum.SendOnly
        );
        pc.addTrack(videoTrack);

        capturer.OnEncodedVideoFrame += (duration, encoded) =>
        {
            try
            {
                if (_activeSession == activeSession && pc.connectionState == RTCPeerConnectionState.connected)
                {
                    pc.SendVideo(duration, encoded);
                }
            }
            catch (Exception ex)
            {
                _logger.LogDebug(ex, "SendVideo skip saat sesi menutup");
            }
        };

        pc.ondatachannel += dc => BindDataChannel(dc, activeSession);

        pc.onicecandidate += candidate =>
        {
            if (candidate == null || _activeSession != activeSession || _signaling == null) return;

            var iceJson = JsonSerializer.Serialize(new
            {
                sdpMid = candidate.sdpMid,
                sdpMLineIndex = candidate.sdpMLineIndex,
                sdp = candidate.candidate
            });

            try
            {
                long seq = Interlocked.Increment(ref activeSession.OutgoingSigSeq) - 1L;
                var encrypted = _crypto.EncryptTextToBase64(
                    aesKey: activeSession.AesKey,
                    text: iceJson,
                    sequenceNumber: seq,
                    direction: E2eeCryptoEngine.DirHostToController
                );
                _ = _signaling.SendEncryptedIceCandidateAsync(activeSession.PeerId, encrypted, activeSession.SessionId);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Gagal mengenkripsi ICE candidate Host");
            }
        };

        pc.onconnectionstatechange += state =>
        {
            _logger.LogInformation("Host PeerConnection state: {State}", state);
            if (_activeSession != activeSession) return;

            if (state == RTCPeerConnectionState.connected)
            {
                capturer.Start();
                OnStatusChanged?.Invoke($"🟢 Tersambung dengan {activeSession.PeerAlias}");
                OnPeerConnected?.Invoke(activeSession.PeerId, activeSession.PeerAlias, activeSession.SafetyFingerprint);
            }
            else if (state == RTCPeerConnectionState.disconnected ||
                     state == RTCPeerConnectionState.failed ||
                     state == RTCPeerConnectionState.closed)
            {
                CleanupActivePeerFromEvent($"Koneksi WebRTC {state}");
            }
        };

        try
        {
            var setResult = pc.setRemoteDescription(new RTCSessionDescriptionInit
            {
                type = RTCSdpType.offer,
                sdp = sdpString
            });
            if (setResult != SetDescriptionResultEnum.OK)
            {
                throw new InvalidOperationException($"setRemoteDescription gagal: {setResult}");
            }

            var answer = pc.createAnswer();
            await pc.setLocalDescription(answer);

            var answerJson = JsonSerializer.Serialize(new
            {
                sdpType = "answer",
                sdp = answer.sdp
            });

            long answerSeq = Interlocked.Increment(ref activeSession.OutgoingSigSeq) - 1L;
            var encryptedAnswer = _crypto.EncryptTextToBase64(
                aesKey: activeSession.AesKey,
                text: answerJson,
                sequenceNumber: answerSeq,
                direction: E2eeCryptoEngine.DirHostToController
            );

            await _signaling.SendEncryptedAnswerAsync(activeSession.PeerId, activeSession.SessionId, encryptedAnswer);
            OnStatusChanged?.Invoke($"Mengirim WebRTC answer ke {activeSession.PeerAlias}…");
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Gagal membuat atau mengirim WebRTC answer");
            CleanupActivePeerFromEvent($"Gagal menegosiasikan WebRTC: {ex.Message}");
        }
    }

    private void HandleIncomingIce(WebRtcIcePayload ice)
    {
        var session = _activeSession;
        if (session == null || session.SessionId != ice.SessionId) return;

        try
        {
            long expected = Interlocked.Read(ref session.ExpectedSigSeq);
            var (seq, plainJson) = _crypto.DecryptBase64ToText(
                aesKey: session.AesKey,
                payloadB64: ice.EncryptedCandidateB64,
                expectedDirection: E2eeCryptoEngine.DirControllerToHost,
                minSequenceNumber: expected
            );
            Interlocked.Exchange(ref session.ExpectedSigSeq, seq + 1L);

            var node = JsonNode.Parse(plainJson);
            var sdp = node?["sdp"]?.GetValue<string>() ?? "";
            var sdpMid = node?["sdpMid"]?.GetValue<string>() ?? "";
            var sdpMLineIndex = node?["sdpMLineIndex"]?.GetValue<ushort>() ?? 0;

            session.PeerConnection.addIceCandidate(new RTCIceCandidateInit
            {
                candidate = sdp,
                sdpMid = sdpMid,
                sdpMLineIndex = sdpMLineIndex
            });
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Gagal memproses ICE candidate dari controller");
        }
    }

    private void BindDataChannel(RTCDataChannel dc, ActiveHostSession session)
    {
        session.DataChannel = dc;

        dc.onopen += () =>
        {
            _logger.LogInformation("Host DataChannel '{Label}' terbuka.", dc.label);
            session.Capturer.ForceKeyFrame();
        };

        dc.onmessage += (_, _, data) =>
        {
            if (_activeSession != session) return;

            try
            {
                long expected = Interlocked.Read(ref session.ExpectedDcSeq);
                var (seq, plain) = _crypto.DecryptEnvelope(
                    aesKey: session.AesKey,
                    envelope: data,
                    expectedDirection: E2eeCryptoEngine.DirControllerToHost,
                    minSequenceNumber: expected
                );
                Interlocked.Exchange(ref session.ExpectedDcSeq, seq + 1L);

                var packet = ControlPacketCodec.Decode(plain);
                if (packet == null) return;

                if (packet is HeartbeatPingPacket ping)
                {
                    SendPong(dc, session, ping.SentEpochMs);
                }
                else if (packet is QualityChangePacket quality)
                {
                    _qualityPreset = quality.Preset;
                    session.Capturer.UpdateQualityPreset(quality.Preset);
                }
                else if (_allowInputControl)
                {
                    _injector.Execute(packet);
                }
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Gagal memproses paket kontrol masuk DataChannel");
            }
        };
    }

    private void SendPong(RTCDataChannel dc, ActiveHostSession session, long sentEpochMs)
    {
        if (dc.readyState != RTCDataChannelState.open) return;

        try
        {
            byte[] plain = ControlPacketCodec.Encode(RemoteControlPacket.Pong(sentEpochMs));
            long seq = Interlocked.Increment(ref session.OutgoingDcSeq) - 1L;
            byte[] encrypted = _crypto.EncryptEnvelope(
                aesKey: session.AesKey,
                plaintext: plain,
                sequenceNumber: seq,
                direction: E2eeCryptoEngine.DirHostToController
            );
            dc.send(encrypted);
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "Gagal mengirim pong via DataChannel");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<RTCIceServer> BuildRtcIceServers()
    {
        if (_iceConfig != null && _iceConfig.IceServers.Count > 0)
        {
            return _iceConfig.IceServers.Select(s => new RTCIceServer
            {
                urls = string.Join(",", s.Urls),
                username = s.Username,
                credential = s.Credential
            }).ToList();
        }

        return new List<RTCIceServer>
        {
            new RTCIceServer { urls = "stun:stun.l.google.com:19302" }
        };
    }

    private void RecordTrustedController(string deviceId, string alias, string fingerprint)
    {
        var normalized = DeviceIdUtil.Normalize(deviceId);
        if (normalized.Length == 0) return;

        _trustedDeviceIds.Add(normalized);
        OnTrustedControllerRecorded?.Invoke(new TrustedControllerEntry(
            DeviceId: deviceId,
            Alias: alias,
            LastConnectedEpochMs: DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            LastFingerprint: fingerprint
        ));
    }

    private void CleanupActivePeerFromEvent(string reason)
    {
        ActiveHostSession? session;
        lock (_sessionLock)
        {
            session = _activeSession;
            _activeSession = null;
        }

        if (session == null) return;

        DisposeActiveSessionResources(session);
        OnPeerDisconnected?.Invoke(reason);
        if (IsHostRunning)
        {
            OnStatusChanged?.Invoke($"Standby • Siap dikendalikan (ID: {DeviceIdUtil.Format(_myDeviceId)})");
        }
    }

    private static void DisposeActiveSessionResources(ActiveHostSession session)
    {
        try { session.Capturer.Dispose(); } catch { /* ignore */ }
        try { session.PeerConnection.close(); } catch { /* ignore */ }
        try { session.PeerConnection.Dispose(); } catch { /* ignore */ }
        CryptoUtils.SecureWipe(session.AesKey);
        CryptoUtils.SecureWipe(session.AuthKey);
    }

    private void ClearPendingHandshake(bool disposeAll)
    {
        var pending = _pendingHandshake;
        _pendingHandshake = null;
        if (pending == null || !disposeAll) return;

        try { pending.HostInit.KeyPair.Dispose(); } catch { /* ignore */ }
        CryptoUtils.SecureWipe(pending.DerivedKeys.AesSessionKey);
        CryptoUtils.SecureWipe(pending.DerivedKeys.AuthConfirmKey);
    }

    public async ValueTask DisposeAsync()
    {
        await StopHostAsync();
    }
}
