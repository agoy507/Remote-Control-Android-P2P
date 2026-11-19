using System.Text.Json.Serialization;

namespace MultideviceRemote.Model;

/// <summary>Config koneksi ke signaling server. Credential TURN ambil dari respons 'registered'.</summary>
public sealed record ServerEndpointConfig(
    string SignalingUrl = "wss://remote.agoy507.my.id/ws",
    string ServerApiToken = ""
);

/// <summary>ICE server config ephemeral dari signaling server (registered response).</summary>
public sealed record EphemeralIceConfig(
    List<IceServerEntry> IceServers,
    long ExpiresAtMs  // Unix epoch ms
)
{
    /// <summary>True jika kredensial kurang dari 60 detik sebelum kadaluarsa.</summary>
    public bool IsExpired()
    {
        var nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        return nowMs >= ExpiresAtMs - 60_000;
    }
}

public sealed record IceServerEntry(
    [property: JsonPropertyName("urls")] List<string> Urls,
    [property: JsonPropertyName("username")] string? Username = null,
    [property: JsonPropertyName("credential")] string? Credential = null
);

/// <summary>State sesi remote aktif.</summary>
public sealed record ActiveSession(
    string SessionId,
    string HostDeviceId,
    string ControllerDeviceId,
    DateTimeOffset ConnectedAt
)
{
    public TimeSpan Duration => DateTimeOffset.UtcNow - ConnectedAt;
}

/// <summary>State device yang terdaftar di signaling.</summary>
public sealed record DeviceInfo(
    string DeviceId,
    string Alias,
    bool IsOnline
);

/// <summary>App settings yang disimpan ke file.</summary>
public sealed class AppSettings
{
    public string SignalingUrl { get; set; } = "wss://remote.agoy507.my.id/ws";
    public string MyDeviceId { get; set; } = GenerateDeviceId();
    public string MyAlias { get; set; } = Environment.MachineName;
    public string ServerApiToken { get; set; } = "";
    public string LastTargetDeviceId { get; set; } = "";

    private static string GenerateDeviceId()
    {
        // Format: "pc-" + 8 hex random bytes
        return "pc-" + Convert.ToHexString(Guid.NewGuid().ToByteArray())[..8].ToLower();
    }
}
