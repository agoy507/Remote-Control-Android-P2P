package com.agoy507.mdremote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class RemoteSystemAction {
    BACK,
    HOME,
    RECENTS,
    NOTIFICATIONS,
    LOCK_SCREEN,
}

@Serializable
sealed interface RemoteControlPacket {

    @Serializable
    @SerialName("tap")
    data class TouchTap(
        val xNorm: Float,
        val yNorm: Float,
        val isLongPress: Boolean = false,
    ) : RemoteControlPacket

    @Serializable
    @SerialName("swipe")
    data class TouchSwipe(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val durationMs: Long = 220L,
    ) : RemoteControlPacket

    @Serializable
    @SerialName("sys")
    data class SystemKey(
        val action: RemoteSystemAction,
    ) : RemoteControlPacket

    @Serializable
    @SerialName("text")
    data class TextInput(
        val text: String,
    ) : RemoteControlPacket

    @Serializable
    @SerialName("quality")
    data class QualityChange(
        val preset: StreamQualityPreset,
    ) : RemoteControlPacket

    @Serializable
    @SerialName("ping")
    data class HeartbeatPing(
        val sentEpochMs: Long,
    ) : RemoteControlPacket

    @Serializable
    @SerialName("pong")
    data class HeartbeatPong(
        val echoedEpochMs: Long,
    ) : RemoteControlPacket
}

object ControlPacketCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "kind"
    }

    fun encode(packet: RemoteControlPacket): ByteArray =
        json.encodeToString(RemoteControlPacket.serializer(), packet).encodeToByteArray()

    fun decode(bytes: ByteArray): RemoteControlPacket =
        json.decodeFromString(RemoteControlPacket.serializer(), bytes.decodeToString())
}
