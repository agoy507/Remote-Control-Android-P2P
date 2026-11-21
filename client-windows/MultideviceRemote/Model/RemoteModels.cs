using System.Security.Cryptography;
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

/// <summary>Pengendali terpercaya yang pernah diizinkan.</summary>
public sealed record TrustedControllerEntry(
    string DeviceId,
    string Alias,
    long LastConnectedEpochMs,
    string LastFingerprint = ""
);

/// <summary>Permintaan koneksi masuk ke Mode Host.</summary>
public sealed record IncomingHostRequest(
    string SessionId,
    string FromDeviceId,
    string FromAlias,
    string SafetyFingerprint
);

public static class DeviceIdUtil
{
    public static string Normalize(string? raw)
    {
        if (string.IsNullOrEmpty(raw)) return string.Empty;
        Span<char> buf = stackalloc char[raw.Length];
        int n = 0;
        foreach (char c in raw)
        {
            if (char.IsAsciiLetterOrDigit(c))
            {
                buf[n++] = char.ToUpperInvariant(c);
            }
        }
        return new string(buf[..n]);
    }

    public static string Format(string? raw)
    {
        var clean = Normalize(raw);
        return clean.Length == 9
            ? $"{clean[..3]}-{clean[3..6]}-{clean[6..9]}"
            : clean;
    }

    public static bool IsLegacyPcHexId(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return false;
        var s = raw.Trim();
        if (s.Length != 11 || !s.StartsWith("pc-", StringComparison.OrdinalIgnoreCase))
            return false;
        for (int i = 3; i < s.Length; i++)
        {
            if (!Uri.IsHexDigit(s[i])) return false;
        }
        return true;
    }
}

/// <summary>App settings yang disimpan ke file.</summary>
public sealed class AppSettings
{
    private const string PasswordAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    public string SignalingUrl { get; set; } = "wss://remote.agoy507.my.id/ws";
    public string MyDeviceId { get; set; } = GenerateDeviceId();
    public string MyAlias { get; set; } = Environment.MachineName;
    public string ServerApiToken { get; set; } = "";
    public string LastTargetDeviceId { get; set; } = "";
    public string LastTargetPassword { get; set; } = "";
    public bool RememberTargetPassword { get; set; } = true;

    public string HostSessionPassword { get; set; } = GenerateHostPassword();
    public bool RequireVisualApproval { get; set; } = true;
    public bool AllowInputControl { get; set; } = true;
    public string HostQualityPreset { get; set; } = "BALANCED";
    public List<TrustedControllerEntry> TrustedControllers { get; set; } = new();

    public static string GenerateHostPassword()
    {
        return RandomNumberGenerator.GetString(PasswordAlphabet, 8);
    }

    public static string GenerateDeviceId()
    {
        // Format identik dengan Android: 9 digit angka (100000000..999999999)
        return RandomNumberGenerator.GetInt32(100_000_000, 1_000_000_000).ToString();
    }
}
