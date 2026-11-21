using System.Text.Json;
using System.Text.Json.Serialization;

namespace MultideviceRemote.Model;

public enum RemoteSystemAction
{
    BACK,
    HOME,
    RECENTS,
    NOTIFICATIONS,
    LOCK_SCREEN
}

/// <summary>
/// Paket kontrol input P2P — 100% kompatibel dengan Kotlinx Serialization Android (ControlPacketCodec).
/// Polimorfik dengan discriminator "kind".
/// </summary>
[JsonPolymorphic(TypeDiscriminatorPropertyName = "kind")]
[JsonDerivedType(typeof(TouchTapPacket), typeDiscriminator: "tap")]
[JsonDerivedType(typeof(TouchSwipePacket), typeDiscriminator: "swipe")]
[JsonDerivedType(typeof(SystemKeyPacket), typeDiscriminator: "sys")]
[JsonDerivedType(typeof(TextInputPacket), typeDiscriminator: "text")]
[JsonDerivedType(typeof(QualityChangePacket), typeDiscriminator: "quality")]
[JsonDerivedType(typeof(HeartbeatPingPacket), typeDiscriminator: "ping")]
[JsonDerivedType(typeof(HeartbeatPongPacket), typeDiscriminator: "pong")]
public abstract record RemoteControlPacket
{
    public static RemoteControlPacket Tap(float xNorm, float yNorm, bool isLongPress = false) =>
        new TouchTapPacket(xNorm, yNorm, isLongPress);

    public static RemoteControlPacket Swipe(float startX, float startY, float endX, float endY, long durationMs = 220L) =>
        new TouchSwipePacket(startX, startY, endX, endY, durationMs);

    public static RemoteControlPacket SystemKey(RemoteSystemAction action) =>
        new SystemKeyPacket(action.ToString());

    public static RemoteControlPacket TextInput(string text) =>
        new TextInputPacket(text);

    public static RemoteControlPacket QualityChange(string preset) =>
        new QualityChangePacket(preset);

    public static RemoteControlPacket Ping() =>
        new HeartbeatPingPacket(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());

    public static RemoteControlPacket Pong(long sentEpochMs) =>
        new HeartbeatPongPacket(sentEpochMs);
}

public sealed record TouchTapPacket(
    [property: JsonPropertyName("xNorm")] float XNorm,
    [property: JsonPropertyName("yNorm")] float YNorm,
    [property: JsonPropertyName("isLongPress")] bool IsLongPress = false
) : RemoteControlPacket;

public sealed record TouchSwipePacket(
    [property: JsonPropertyName("startX")] float StartX,
    [property: JsonPropertyName("startY")] float StartY,
    [property: JsonPropertyName("endX")] float EndX,
    [property: JsonPropertyName("endY")] float EndY,
    [property: JsonPropertyName("durationMs")] long DurationMs = 220L
) : RemoteControlPacket;

public sealed record SystemKeyPacket(
    [property: JsonPropertyName("action")] string Action
) : RemoteControlPacket;

public sealed record TextInputPacket(
    [property: JsonPropertyName("text")] string Text
) : RemoteControlPacket;

public sealed record QualityChangePacket(
    [property: JsonPropertyName("preset")] string Preset
) : RemoteControlPacket;

public sealed record HeartbeatPingPacket(
    [property: JsonPropertyName("sentEpochMs")] long SentEpochMs
) : RemoteControlPacket;

public sealed record HeartbeatPongPacket(
    [property: JsonPropertyName("echoedEpochMs")] long EchoedEpochMs
) : RemoteControlPacket;

/// <summary>Codec helper untuk serialisasi / deserialisasi paket kontrol.</summary>
public static class ControlPacketCodec
{
    private static readonly JsonSerializerOptions Options = new()
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    public static byte[] Encode(RemoteControlPacket packet) =>
        JsonSerializer.SerializeToUtf8Bytes(packet, Options);

    public static RemoteControlPacket? Decode(byte[] bytes) =>
        JsonSerializer.Deserialize<RemoteControlPacket>(bytes, Options);
}
