using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Extensions.Logging;
using MultideviceRemote.Model;

namespace MultideviceRemote.Network;

/// <summary>
/// WebSocket signaling client dengan full E2EE handshake (match Android protokol & server index.js).
/// 
/// Flow controller:
///   1. ConnectAsync() → register → OnRegistered(iceConfig)
///   2. App kirim e2ee_hello (targetId, sessionId, pubKey, nonce, alias)
///   3. Server relay e2ee_ack dari host → OnE2eeAck
///   4. App kirim webrtc_offer (targetId, sessionId, clientProof, payload=encryptedSdp)
///   5. Server relay webrtc_answer (fromId, sessionId, payload=encryptedSdp) → OnAnswer
///   6. webrtc_ice bolak-balik (targetId, sessionId, payload=encryptedIce)
/// 
/// Ping loop berjalan setiap 20 detik.
/// </summary>
public sealed class SignalingClient : IAsyncDisposable
{
    private readonly ILogger<SignalingClient> _logger;
    private readonly string _signalingUrl;
    private readonly string _apiToken;
    private readonly SemaphoreSlim _sendLock = new(1, 1);
    private ClientWebSocket? _ws;
    private CancellationTokenSource? _cts;
    private Task? _pingTask;

    // ── Events ────────────────────────────────────────────────────────────────
    public event Action<EphemeralIceConfig>? OnRegistered;
    public event Action<E2eeHelloPayload>? OnE2eeHello;
    public event Action<E2eeAckPayload>? OnE2eeAck;
    public event Action<string>? OnE2eeReject;         // reason string
    public event Action<string, string>? OnOffer;        // (fromId, sdpEncryptedB64)
    public event Action<WebRtcOfferPayload>? OnWebRtcOffer;
    public event Action<string, string>? OnAnswer;       // (fromId, sdpEncryptedB64)
    public event Action<string, string>? OnIceCandidate; // (fromId, candidateEncryptedB64)
    public event Action<WebRtcIcePayload>? OnWebRtcIce;
    public event Action<string>? OnSessionEnded;         // reason
    public event Action<string, string>? OnSessionEndedWithId; // (sessionId, reason)
    public event Action<string>? OnError;
    public event Action? OnDisconnected;

    private string? _myDeviceId;

    public SignalingClient(string signalingUrl, string apiToken = "")
    {
        _signalingUrl = signalingUrl;
        _apiToken = apiToken;
        _logger = App.LoggerFactory.CreateLogger<SignalingClient>();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public async Task ConnectAsync(string deviceId, string alias, CancellationToken ct = default)
    {
        _myDeviceId = deviceId;

        if (_cts is not null)
        {
            await _cts.CancelAsync();
            _cts.Dispose();
        }
        _ws?.Dispose();

        _cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _ws = new ClientWebSocket();

        var connectUrl = BuildUrlWithToken(_signalingUrl, _apiToken);
        _logger.LogInformation("Connecting to signaling: {Url}", connectUrl);
        await _ws.ConnectAsync(new Uri(connectUrl), _cts.Token);
        _logger.LogInformation("Signaling connected");

        // Register setelah connect
        await SendRawAsync(new { type = "register", deviceId, alias });

        // Start receive loop
        _ = Task.Run(() => ReceiveLoopAsync(_cts.Token), _cts.Token);

        // Start ping loop — setiap 20 detik
        _pingTask = Task.Run(() => PingLoopAsync(_cts.Token), _cts.Token);
    }

    // ── Signaling messages (exact matching server index.js & Android) ─────────

    /// <summary>Kirim e2ee_hello — awal handshake dari controller ke host.</summary>
    public async Task SendE2eeHelloAsync(string targetId, string sessionId, string pubKeyB64, string nonceB64, string alias) =>
        await SendRawAsync(new { type = "e2ee_hello", targetId, sessionId, pubKey = pubKeyB64, nonce = nonceB64, alias });

    /// <summary>Kirim e2ee_ack — balasan handshake dari host ke controller.</summary>
    public async Task SendE2eeAckAsync(string targetId, string sessionId, string pubKeyB64, string nonceB64, string hostProofB64, string alias) =>
        await SendRawAsync(new { type = "e2ee_ack", targetId, sessionId, pubKey = pubKeyB64, nonce = nonceB64, hostProof = hostProofB64, alias });

    /// <summary>Kirim e2ee_reject — tolak koneksi masuk.</summary>
    public async Task SendE2eeRejectAsync(string targetId, string sessionId, string reason, string message) =>
        await SendRawAsync(new { type = "e2ee_reject", targetId, sessionId, reason, message });

    /// <summary>Kirim WebRTC offer terenkripsi (type: webrtc_offer, payload: encryptedSdp).</summary>
    public async Task SendEncryptedOfferAsync(string targetId, string sessionId, string sdpEncryptedB64, string clientProofB64) =>
        await SendRawAsync(new { type = "webrtc_offer", targetId, payload = sdpEncryptedB64, sessionId, clientProof = clientProofB64 });

    /// <summary>Kirim WebRTC answer terenkripsi (type: webrtc_answer, payload: encryptedSdp).</summary>
    public async Task SendEncryptedAnswerAsync(string targetId, string sessionId, string sdpEncryptedB64) =>
        await SendRawAsync(new { type = "webrtc_answer", targetId, sessionId, payload = sdpEncryptedB64 });

    /// <summary>Kirim WebRTC ICE candidate terenkripsi (type: webrtc_ice, payload: encryptedCandidate).</summary>
    public async Task SendEncryptedIceCandidateAsync(string targetId, string candidateEncryptedB64, string sessionId) =>
        await SendRawAsync(new { type = "webrtc_ice", targetId, payload = candidateEncryptedB64, sessionId });

    /// <summary>Kirim session_end.</summary>
    public async Task SendSessionEndAsync(string targetId, string sessionId, string reason) =>
        await SendRawAsync(new { type = "session_end", targetId, sessionId, reason });

    public async Task SendPingAsync() =>
        await SendRawAsync(new { type = "ping", ts = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() });

    public async Task DisconnectAsync()
    {
        await (_cts?.CancelAsync() ?? Task.CompletedTask);
        try
        {
            if (_ws?.State == WebSocketState.Open)
                await _ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "bye", CancellationToken.None);
        }
        catch { /* ignore close errors */ }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private async Task ReceiveLoopAsync(CancellationToken ct)
    {
        var buffer = new byte[64 * 1024];
        try
        {
            while (_ws!.State == WebSocketState.Open && !ct.IsCancellationRequested)
            {
                var sb = new StringBuilder();
                WebSocketReceiveResult result;
                do
                {
                    result = await _ws.ReceiveAsync(buffer, ct);
                    if (result.MessageType == WebSocketMessageType.Close) return;
                    sb.Append(Encoding.UTF8.GetString(buffer, 0, result.Count));
                } while (!result.EndOfMessage);

                HandleMessage(sb.ToString());
            }
        }
        catch (OperationCanceledException) { /* normal shutdown */ }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Signaling receive error");
            OnError?.Invoke(ex.Message);
        }
        finally
        {
            _logger.LogInformation("Signaling disconnected");
            OnDisconnected?.Invoke();
        }
    }

    private async Task PingLoopAsync(CancellationToken ct)
    {
        try
        {
            while (!ct.IsCancellationRequested)
            {
                await Task.Delay(TimeSpan.FromSeconds(20), ct);
                if (_ws?.State == WebSocketState.Open)
                    await SendPingAsync();
            }
        }
        catch (OperationCanceledException) { /* normal */ }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Ping loop error");
        }
    }

    private void HandleMessage(string json)
    {
        try
        {
            var node = JsonNode.Parse(json);
            var type = node?["type"]?.GetValue<string>() ?? "";
            _logger.LogDebug("← {Type}", type);

            switch (type)
            {
                case "registered":
                    var ice = ParseIceServers(node!);
                    OnRegistered?.Invoke(ice);
                    break;

                case "pong":
                    // keepalive ack
                    break;

                // ── E2EE Handshake ────────────────────────────────────────────
                case "e2ee_hello":
                    var helloPayload = new E2eeHelloPayload(
                        SessionId: node!["sessionId"]?.GetValue<string>() ?? "",
                        FromId: node["fromId"]?.GetValue<string>() ?? "",
                        ControllerPubKeyB64: node["pubKey"]?.GetValue<string>() ?? "",
                        ControllerNonceB64: node["nonce"]?.GetValue<string>() ?? "",
                        ControllerAlias: node["alias"]?.GetValue<string>() ?? "Pengendali"
                    );
                    OnE2eeHello?.Invoke(helloPayload);
                    break;

                case "e2ee_ack":
                    var ackPayload = new E2eeAckPayload(
                        SessionId: node!["sessionId"]?.GetValue<string>() ?? "",
                        FromId: node["fromId"]?.GetValue<string>() ?? "",
                        HostPubKeyB64: node["pubKey"]?.GetValue<string>() ?? "",
                        HostNonceB64: node["nonce"]?.GetValue<string>() ?? "",
                        HostProofB64: node["hostProof"]?.GetValue<string>() ?? "",
                        HostAlias: node["alias"]?.GetValue<string>() ?? "Host"
                    );
                    OnE2eeAck?.Invoke(ackPayload);
                    break;

                case "e2ee_reject":
                    var rejectReason = node?["reason"]?.GetValue<string>()
                        ?? node?["message"]?.GetValue<string>() ?? "Ditolak";
                    OnE2eeReject?.Invoke(rejectReason);
                    break;

                // ── WebRTC SDP & ICE ───────────────────────────────────────────
                case "webrtc_offer":
                    var offerSessionId = node!["sessionId"]?.GetValue<string>() ?? "";
                    var offerFromId = node["fromId"]?.GetValue<string>() ?? "";
                    var offerSdpPayload = node["payload"]?.GetValue<string>() ?? "";
                    var clientProof = node["clientProof"]?.GetValue<string>() ?? "";
                    OnOffer?.Invoke(offerFromId, offerSdpPayload);
                    OnWebRtcOffer?.Invoke(new WebRtcOfferPayload(offerSessionId, offerFromId, offerSdpPayload, clientProof));
                    break;

                case "webrtc_answer":
                    OnAnswer?.Invoke(
                        node!["fromId"]?.GetValue<string>() ?? "",
                        node["payload"]?.GetValue<string>() ?? ""
                    );
                    break;

                case "webrtc_ice":
                    var iceSessionId = node!["sessionId"]?.GetValue<string>() ?? "";
                    var iceFromId = node["fromId"]?.GetValue<string>() ?? "";
                    var iceCandidatePayload = node["payload"]?.GetValue<string>() ?? "";
                    OnIceCandidate?.Invoke(iceFromId, iceCandidatePayload);
                    OnWebRtcIce?.Invoke(new WebRtcIcePayload(iceSessionId, iceFromId, iceCandidatePayload));
                    break;

                // ── Session lifecycle ──────────────────────────────────────────
                case "session_end":
                    var endSessionId = node?["sessionId"]?.GetValue<string>() ?? "";
                    var endReason = node?["reason"]?.GetValue<string>() ?? "Sesi berakhir";
                    OnSessionEnded?.Invoke(endReason);
                    OnSessionEndedWithId?.Invoke(endSessionId, endReason);
                    break;

                case "peer_offline":
                    OnSessionEnded?.Invoke(node?["message"]?.GetValue<string>() ?? "Perangkat target sedang offline");
                    break;

                case "replaced":
                    OnSessionEnded?.Invoke("Sesi digantikan oleh koneksi baru.");
                    break;

                case "error":
                    var errMsg = node?["message"]?.GetValue<string>() ?? "Kesalahan signaling.";
                    _logger.LogWarning("Signaling error: {Msg}", errMsg);
                    OnError?.Invoke(errMsg);
                    break;

                default:
                    _logger.LogDebug("Unhandled signaling type: {Type}", type);
                    break;
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to parse signaling message: {Json}", json);
        }
    }

    private EphemeralIceConfig ParseIceServers(JsonNode node)
    {
        var servers = new List<IceServerEntry>();

        if (node["iceServers"] is JsonArray arr)
        {
            foreach (var item in arr)
            {
                if (item is null) continue;
                var urls = item["urls"] is JsonArray urlArr
                    ? urlArr.Select(u => u?.GetValue<string>() ?? "").Where(u => u.Length > 0).ToList()
                    : new List<string>();

                servers.Add(new IceServerEntry(
                    Urls: urls,
                    Username: item["username"]?.GetValue<string>(),
                    Credential: item["credential"]?.GetValue<string>()
                ));
            }
        }

        if (servers.Count == 0)
        {
            _logger.LogWarning("No iceServers from server, using public STUN fallback");
            servers.Add(new IceServerEntry(new List<string> { "stun:stun.l.google.com:19302" }));
        }

        long expiresMs = DateTimeOffset.UtcNow.AddHours(1).ToUnixTimeMilliseconds();
        var firstTurn = servers.FirstOrDefault(s => s.Username is not null);
        if (firstTurn?.Username?.Split(':') is [var tsStr, ..] && long.TryParse(tsStr, out var ts))
            expiresMs = ts * 1000;

        _logger.LogInformation("Registered. {Count} ICE servers, expires ~{Dt}",
            servers.Count, DateTimeOffset.FromUnixTimeMilliseconds(expiresMs).LocalDateTime);

        return new EphemeralIceConfig(servers, expiresMs);
    }

    private static string BuildUrlWithToken(string url, string token)
    {
        if (string.IsNullOrEmpty(token)) return url;
        if (url.Contains("token=")) return url;
        var sep = url.Contains('?') ? "&" : "?";
        return $"{url}{sep}token={Uri.EscapeDataString(token)}";
    }

    private async Task SendRawAsync(object message)
    {
        if (_ws?.State != WebSocketState.Open) return;
        var json = JsonSerializer.Serialize(message);
        _logger.LogDebug("→ {Json}", json);
        var bytes = Encoding.UTF8.GetBytes(json);

        var token = _cts?.Token ?? CancellationToken.None;
        try
        {
            await _sendLock.WaitAsync(token);
            try
            {
                if (_ws?.State != WebSocketState.Open || token.IsCancellationRequested) return;
                await _ws.SendAsync(bytes, WebSocketMessageType.Text, true, token);
            }
            finally
            {
                _sendLock.Release();
            }
        }
        catch (OperationCanceledException) { /* shutdown in progress */ }
        catch (ObjectDisposedException) { /* disposed during send */ }
    }

    public async ValueTask DisposeAsync()
    {
        await DisconnectAsync();
        _ws?.Dispose();
        _cts?.Dispose();
        _sendLock.Dispose();
    }
}

/// <summary>Payload dari e2ee_hello (permintaan masuk controller ke host).</summary>
public sealed record E2eeHelloPayload(
    string SessionId,
    string FromId,
    string ControllerPubKeyB64,
    string ControllerNonceB64,
    string ControllerAlias
);

/// <summary>Payload dari webrtc_offer terenkripsi.</summary>
public sealed record WebRtcOfferPayload(
    string SessionId,
    string FromId,
    string EncryptedSdpB64,
    string ClientProofB64
);

/// <summary>Payload dari webrtc_ice terenkripsi.</summary>
public sealed record WebRtcIcePayload(
    string SessionId,
    string FromId,
    string EncryptedCandidateB64
);

/// <summary>Payload dari e2ee_ack (host response ke controller hello).</summary>
public sealed record E2eeAckPayload(
    string SessionId,
    string FromId,
    string HostPubKeyB64,
    string HostNonceB64,
    string HostProofB64,
    string HostAlias
);
