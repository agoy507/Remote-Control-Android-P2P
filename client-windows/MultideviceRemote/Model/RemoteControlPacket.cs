using System.Text.Json.Serialization;

namespace MultideviceRemote.Model;

/// <summary>
/// Paket kontrol input P2P — identik dengan Android RemoteControlPacket.
/// Dikirim via WebRTC DataChannel, optionally dienkripsi AES-256-GCM.
/// </summary>
public sealed record RemoteControlPacket(
    [property: JsonPropertyName("type")] string Type,
    [property: JsonPropertyName("timestamp")] long Timestamp,
    [property: JsonPropertyName("x")] float? X = null,
    [property: JsonPropertyName("y")] float? Y = null,
    [property: JsonPropertyName("action")] int? Action = null,
    [property: JsonPropertyName("keyCode")] int? KeyCode = null,
    [property: JsonPropertyName("scrollDx")] float? ScrollDx = null,
    [property: JsonPropertyName("scrollDy")] float? ScrollDy = null,
    [property: JsonPropertyName("sessionId")] string? SessionId = null
)
{
    // ── Packet type constants (mirror Android) ───────────────────────────────
    public const string TypeTouch  = "touch";
    public const string TypeKey    = "key";
    public const string TypeScroll = "scroll";
    public const string TypePing   = "ping";
    public const string TypePong   = "pong";

    // ── Android MotionEvent action constants ─────────────────────────────────
    public const int ActionDown   = 0;
    public const int ActionUp     = 1;
    public const int ActionMove   = 2;
    public const int ActionCancel = 3;

    // ── Android KeyEvent keycodes (sering dipakai) ───────────────────────────
    public const int KeycodeBack    = 4;
    public const int KeycodeHome    = 3;
    public const int KeycodeRecents = 187;
    public const int KeycodeEnter   = 66;
    public const int KeycodeDel     = 67;  // Backspace

    public static RemoteControlPacket Ping(string? sessionId = null) =>
        new(TypePing, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), SessionId: sessionId);

    public static RemoteControlPacket Pong(string? sessionId = null) =>
        new(TypePong, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), SessionId: sessionId);

    public static RemoteControlPacket Touch(float x, float y, int action, string? sessionId = null) =>
        new(TypeTouch, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), x, y, action, SessionId: sessionId);

    public static RemoteControlPacket Key(int keyCode, string? sessionId = null) =>
        new(TypeKey, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), KeyCode: keyCode, SessionId: sessionId);

    public static RemoteControlPacket Scroll(float dx, float dy, string? sessionId = null) =>
        new(TypeScroll, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), ScrollDx: dx, ScrollDy: dy, SessionId: sessionId);
}
