using Microsoft.Extensions.Logging;
using Microsoft.UI.Xaml.Controls;
using MultideviceRemote.Model;
using MultideviceRemote.Network;
using MultideviceRemote.Rendering;
using SIPSorcery.Net;
using System.Text;
using System.Text.Json;

namespace MultideviceRemote.WebRtc;

/// <summary>
/// Koordinator utama sesi remote:
/// - Mengelola SignalingClient
/// - Membuat RTCPeerConnection via SIPSorcery
/// - Menghubungkan DataChannel untuk input packets
/// - Menghubungkan VideoTrack ke VideoFrameRenderer
/// </summary>
public sealed class SessionCoordinator : IAsyncDisposable
{
    private readonly ILogger<SessionCoordinator> _logger;
    private readonly string _signalingUrl;
    private readonly string _myDeviceId;
    private readonly string _targetDeviceId;
    private readonly string _targetPassword;
    private readonly SwapChainPanel _swapChainPanel;

    private SignalingClient? _signaling;
    private RTCPeerConnection? _peerConnection;
    private RTCDataChannel? _dataChannel;
    private VideoFrameRenderer? _renderer;
    private EphemeralIceConfig? _iceConfig;
    private string _sessionId = Guid.NewGuid().ToString("N")[..8];

    // ── Events ────────────────────────────────────────────────────────────────
    public event Action? OnConnected;
    public event Action<string>? OnDisconnected;
    public event Action<string>? OnError;

    public SessionCoordinator(
        string signalingUrl,
        string myDeviceId,
        string targetDeviceId,
        string targetPassword,
        SwapChainPanel swapChainPanel)
    {
        _signalingUrl = signalingUrl;
        _myDeviceId = myDeviceId;
        _targetDeviceId = targetDeviceId;
        _targetPassword = targetPassword;
        _swapChainPanel = swapChainPanel;
        _logger = App.LoggerFactory.CreateLogger<SessionCoordinator>();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public async Task StartAsync()
    {
        _signaling = new SignalingClient(_signalingUrl);

        _signaling.OnRegistered += OnSignalingRegistered;
        _signaling.OnAnswer += OnSignalingAnswer;
        _signaling.OnIceCandidate += OnSignalingIceCandidate;
        _signaling.OnDisconnected += () => OnDisconnected?.Invoke("Signaling disconnected");
        _signaling.OnError += err => OnError?.Invoke(err);

        await _signaling.ConnectAsync(_myDeviceId, Environment.MachineName);
        // Lanjut di OnSignalingRegistered setelah server balas registered + iceServers
    }

    public async Task SendInputAsync(RemoteControlPacket packet)
    {
        if (_dataChannel?.readyState != RTCDataChannelState.open) return;
        var json = JsonSerializer.Serialize(packet);
        _dataChannel.send(json);
        await Task.CompletedTask;
    }

    public async Task DisconnectAsync()
    {
        _peerConnection?.close();
        if (_signaling is not null)
            await _signaling.DisconnectAsync();
    }

    // ── Signaling callbacks ───────────────────────────────────────────────────

    private async void OnSignalingRegistered(EphemeralIceConfig iceConfig)
    {
        _iceConfig = iceConfig;
        _logger.LogInformation("Registered. Creating peer connection.");

        await CreatePeerConnectionAsync(iceConfig);

        // Kirim connect request ke Android host
        await _signaling!.SendConnectRequestAsync(_targetDeviceId, _sessionId);
        _logger.LogInformation("Connect request sent to {Target}", _targetDeviceId);
    }

    private async void OnSignalingAnswer(string fromDeviceId, string sdp)
    {
        if (_peerConnection is null) return;
        _logger.LogInformation("Answer received from {From}", fromDeviceId);

        var desc = new RTCSessionDescriptionInit
        {
            type = RTCSdpType.answer,
            sdp = sdp
        };
        await _peerConnection.setRemoteDescription(desc);
    }

    private void OnSignalingIceCandidate(string fromDeviceId, string candidateJson)
    {
        if (_peerConnection is null) return;
        try
        {
            var init = JsonSerializer.Deserialize<RTCIceCandidateInit>(candidateJson);
            if (init is not null)
                _peerConnection.addIceCandidate(init);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to add ICE candidate");
        }
    }

    // ── PeerConnection ────────────────────────────────────────────────────────

    private async Task CreatePeerConnectionAsync(EphemeralIceConfig iceConfig)
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

        // DataChannel untuk kontrol input
        var dcInit = new RTCDataChannelInit { ordered = true };
        _dataChannel = await _peerConnection.createDataChannel("control", dcInit);

        _dataChannel.onopen += () =>
        {
            _logger.LogInformation("DataChannel open");
            OnConnected?.Invoke();
        };

        _dataChannel.onmessage += (dc, proto, data) =>
        {
            // Android bisa kirim pong / event status
            var msg = Encoding.UTF8.GetString(data);
            _logger.LogDebug("DataChannel ← {Msg}", msg);
        };

        // Video track (menerima dari Android host)
        _renderer = new VideoFrameRenderer(_swapChainPanel);
        // SIPSorcery video sink akan dihubungkan setelah negotiasi (Phase 2)

        // ICE candidate → signaling
        _peerConnection.onicecandidate += (candidate) =>
        {
            if (candidate is null) return;
            var json = JsonSerializer.Serialize(new
            {
                candidate = candidate.candidate,
                sdpMid = candidate.sdpMid,
                sdpMLineIndex = candidate.sdpMLineIndex
            });
            _ = _signaling?.SendIceCandidateAsync(_targetDeviceId, json, _sessionId);
        };

        _peerConnection.onconnectionstatechange += state =>
        {
            _logger.LogInformation("PeerConnection state: {State}", state);
            if (state == RTCPeerConnectionState.failed || state == RTCPeerConnectionState.disconnected)
                OnDisconnected?.Invoke($"WebRTC {state}");
        };

        // Buat offer
        var offer = _peerConnection.createOffer();
        await _peerConnection.setLocalDescription(offer);
        await _signaling!.SendOfferAsync(_targetDeviceId, offer.sdp, _sessionId);
        _logger.LogInformation("Offer sent");
    }

    public async ValueTask DisposeAsync()
    {
        await DisconnectAsync();
        _peerConnection?.Dispose();
        _renderer?.Dispose();
        if (_signaling is not null)
            await _signaling.DisposeAsync();
    }
}
