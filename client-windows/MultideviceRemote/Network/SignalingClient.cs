using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Extensions.Logging;
using MultideviceRemote.Model;

namespace MultideviceRemote.Network;

/// <summary>
/// WebSocket signaling client.
/// Mengelola koneksi ke signaling server, register, dan forward WebRTC SDP/ICE.
/// </summary>
public sealed class SignalingClient : IAsyncDisposable
{
    private readonly ILogger<SignalingClient> _logger;
    private readonly string _signalingUrl;
    private readonly string _apiToken;
    private ClientWebSocket? _ws;
    private CancellationTokenSource? _cts;

    // ── Events ────────────────────────────────────────────────────────────────
    public event Action<EphemeralIceConfig>? OnRegistered;
    public event Action<string, string>? OnOffer;        // (fromDeviceId, sdp)
    public event Action<string, string>? OnAnswer;       // (fromDeviceId, sdp)
    public event Action<string, string>? OnIceCandidate; // (fromDeviceId, candidateJson)
    public event Action<string>? OnIncomingRequest;      // targetDeviceId
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
        _cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _ws = new ClientWebSocket();

        // Bangun URL dengan token (sama seperti Android: append ?token=... ke URL)
        var connectUrl = BuildUrlWithToken(_signalingUrl, _apiToken);

        _logger.LogInformation("Connecting to signaling: {Url}", connectUrl);
        await _ws.ConnectAsync(new Uri(connectUrl), _cts.Token);
        _logger.LogInformation("Signaling connected");

        // Register setelah connect
        await SendAsync(new { type = "register", deviceId, alias });

        // Start receive loop
        _ = Task.Run(() => ReceiveLoopAsync(_cts.Token), _cts.Token);
    }

    public async Task SendOfferAsync(string targetDeviceId, string sdp, string sessionId) =>
        await SendAsync(new { type = "webrtc_offer", targetDeviceId, sdp, sessionId });

    public async Task SendAnswerAsync(string targetDeviceId, string sdp, string sessionId) =>
        await SendAsync(new { type = "webrtc_answer", targetDeviceId, sdp, sessionId });

    public async Task SendIceCandidateAsync(string targetDeviceId, string candidateJson, string sessionId) =>
        await SendAsync(new { type = "ice_candidate", targetDeviceId, candidate = candidateJson, sessionId });

    public async Task SendConnectRequestAsync(string targetDeviceId, string sessionId) =>
        await SendAsync(new { type = "connect_request", targetDeviceId, sessionId });

    public async Task SendPingAsync() =>
        await SendAsync(new { type = "ping" });

    public async Task DisconnectAsync()
    {
        await (_cts?.CancelAsync() ?? Task.CompletedTask);
        if (_ws?.State == WebSocketState.Open)
            await _ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "bye", CancellationToken.None);
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
                    // keepalive
                    break;

                case "webrtc_offer":
                    OnOffer?.Invoke(
                        node!["fromDeviceId"]!.GetValue<string>(),
                        node["sdp"]!.GetValue<string>()
                    );
                    break;

                case "webrtc_answer":
                    OnAnswer?.Invoke(
                        node!["fromDeviceId"]!.GetValue<string>(),
                        node["sdp"]!.GetValue<string>()
                    );
                    break;

                case "ice_candidate":
                    OnIceCandidate?.Invoke(
                        node!["fromDeviceId"]!.GetValue<string>(),
                        node["candidate"]!.ToJsonString()
                    );
                    break;

                case "incoming_request":
                    OnIncomingRequest?.Invoke(node!["fromDeviceId"]!.GetValue<string>());
                    break;

                case "error":
                    var msg = node?["message"]?.GetValue<string>() ?? "Unknown error";
                    _logger.LogWarning("Signaling error: {Msg}", msg);
                    OnError?.Invoke(msg);
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

        // Fallback STUN jika server tidak kirim iceServers
        if (servers.Count == 0)
        {
            _logger.LogWarning("No iceServers from server, using public STUN fallback");
            servers.Add(new IceServerEntry(new List<string> { "stun:stun.l.google.com:19302" }));
        }

        // TTL: ambil dari username format "<expires>:<deviceId>" atau default 1 jam
        long expiresMs = DateTimeOffset.UtcNow.AddHours(1).ToUnixTimeMilliseconds();
        var firstTurn = servers.FirstOrDefault(s => s.Username is not null);
        if (firstTurn?.Username?.Split(':') is [var tsStr, ..] && long.TryParse(tsStr, out var ts))
            expiresMs = ts * 1000; // server kirim epoch seconds

        _logger.LogInformation("Registered. {Count} ICE servers, expires ~{Dt}",
            servers.Count, DateTimeOffset.FromUnixTimeMilliseconds(expiresMs).LocalDateTime);

        return new EphemeralIceConfig(servers, expiresMs);
    }

    /// <summary>
    /// Append ?token= ke URL jika token tidak kosong dan belum ada di URL.
    /// Sama persis dengan logika Android SignalingClient.
    /// </summary>
    private static string BuildUrlWithToken(string url, string token)
    {
        if (string.IsNullOrEmpty(token)) return url;
        if (url.Contains("token=")) return url;
        var sep = url.Contains('?') ? "&" : "?";
        return $"{url}{sep}token={Uri.EscapeDataString(token)}";
    }

    private async Task SendAsync(object message)
    {
        if (_ws?.State != WebSocketState.Open) return;
        var json = JsonSerializer.Serialize(message);
        _logger.LogDebug("→ {Json}", json);
        var bytes = Encoding.UTF8.GetBytes(json);
        await _ws.SendAsync(bytes, WebSocketMessageType.Text, true, _cts?.Token ?? CancellationToken.None);
    }

    public async ValueTask DisposeAsync()
    {
        await DisconnectAsync();
        _ws?.Dispose();
        _cts?.Dispose();
    }
}
