package com.agoy507.mdremote

import com.agoy507.mdremote.crypto.E2eeCryptoEngine
import com.agoy507.mdremote.model.ControlPacketCodec
import com.agoy507.mdremote.model.RemoteControlPacket
import com.agoy507.mdremote.model.RemoteSystemAction
import com.agoy507.mdremote.model.StreamQualityPreset
import com.agoy507.mdremote.model.formatDeviceId
import com.agoy507.mdremote.model.normalizeDeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class E2eeCryptoAndProtocolTest {

    private val crypto = E2eeCryptoEngine()

    @Test
    fun `ecdh plus password key derivation and aes gcm roundtrip succeeds`() {
        val controllerInit = crypto.createEphemeralInit()
        val hostInit = crypto.createEphemeralInit()
        val sessionId = "sess-12345"
        val password = "KENDALI-99"

        val controllerKeys = crypto.deriveSessionKeys(
            localKeyPair = controllerInit.keyPair,
            remotePublicKeyB64 = hostInit.publicKeyB64,
            controllerNonceB64 = controllerInit.nonceB64,
            hostNonceB64 = hostInit.nonceB64,
            sessionPassword = password,
            isController = true,
        )
        val hostKeys = crypto.deriveSessionKeys(
            localKeyPair = hostInit.keyPair,
            remotePublicKeyB64 = controllerInit.publicKeyB64,
            controllerNonceB64 = controllerInit.nonceB64,
            hostNonceB64 = hostInit.nonceB64,
            sessionPassword = password,
            isController = false,
        )

        assertEquals(controllerKeys.safetyFingerprint, hostKeys.safetyFingerprint)

        val clientProof = crypto.computeRoleProof(controllerKeys.authConfirmKey, "CLIENT", sessionId)
        val expectedClientProof = crypto.computeRoleProof(hostKeys.authConfirmKey, "CLIENT", sessionId)
        assertTrue(crypto.verifyProofConstantTime(expectedClientProof, clientProof))

        val packet = RemoteControlPacket.TouchTap(xNorm = 0.42f, yNorm = 0.78f, isLongPress = true)
        val encoded = ControlPacketCodec.encode(packet)
        val encrypted = crypto.encryptEnvelope(
            aesKey = controllerKeys.aesSessionKey,
            plaintext = encoded,
            sequenceNumber = 1L,
            direction = E2eeCryptoEngine.DIR_CONTROLLER_TO_HOST,
        )

        val (seq, decrypted) = crypto.decryptEnvelope(
            aesKey = hostKeys.aesSessionKey,
            envelope = encrypted,
            expectedDirection = E2eeCryptoEngine.DIR_CONTROLLER_TO_HOST,
            minSequenceNumber = 1L,
        )
        assertEquals(1L, seq)
        assertEquals(packet, ControlPacketCodec.decode(decrypted))
    }

    @Test
    fun `mismatched session password fails mutual hmac proof`() {
        val controllerInit = crypto.createEphemeralInit()
        val hostInit = crypto.createEphemeralInit()
        val sessionId = "sess-bad"

        val controllerKeys = crypto.deriveSessionKeys(
            localKeyPair = controllerInit.keyPair,
            remotePublicKeyB64 = hostInit.publicKeyB64,
            controllerNonceB64 = controllerInit.nonceB64,
            hostNonceB64 = hostInit.nonceB64,
            sessionPassword = "WRONG-PASSWORD",
            isController = true,
        )
        val hostKeys = crypto.deriveSessionKeys(
            localKeyPair = hostInit.keyPair,
            remotePublicKeyB64 = controllerInit.publicKeyB64,
            controllerNonceB64 = controllerInit.nonceB64,
            hostNonceB64 = hostInit.nonceB64,
            sessionPassword = "TRUE-PASSWORD",
            isController = false,
        )

        val clientProof = crypto.computeRoleProof(controllerKeys.authConfirmKey, "CLIENT", sessionId)
        val expectedClientProof = crypto.computeRoleProof(hostKeys.authConfirmKey, "CLIENT", sessionId)
        assertFalse(crypto.verifyProofConstantTime(expectedClientProof, clientProof))
    }

    @Test(expected = SecurityException::class)
    fun `replay sequence number is rejected by decryptEnvelope`() {
        val controllerInit = crypto.createEphemeralInit()
        val hostInit = crypto.createEphemeralInit()
        val keys = crypto.deriveSessionKeys(
            localKeyPair = controllerInit.keyPair,
            remotePublicKeyB64 = hostInit.publicKeyB64,
            controllerNonceB64 = controllerInit.nonceB64,
            hostNonceB64 = hostInit.nonceB64,
            sessionPassword = "PASS-123",
            isController = true,
        )
        val encrypted = crypto.encryptEnvelope(
            aesKey = keys.aesSessionKey,
            plaintext = "cmd".toByteArray(),
            sequenceNumber = 4L,
            direction = E2eeCryptoEngine.DIR_CONTROLLER_TO_HOST,
        )
        crypto.decryptEnvelope(
            aesKey = keys.aesSessionKey,
            envelope = encrypted,
            expectedDirection = E2eeCryptoEngine.DIR_CONTROLLER_TO_HOST,
            minSequenceNumber = 5L,
        )
    }

    @Test
    fun `control packet codec and device id helpers work accurately`() {
        assertEquals("482-910-374", formatDeviceId("482 910 374"))
        assertEquals("482910374", normalizeDeviceId("482-910-374"))

        val packets = listOf(
            RemoteControlPacket.TouchSwipe(0.1f, 0.8f, 0.1f, 0.2f, 240L),
            RemoteControlPacket.SystemKey(RemoteSystemAction.HOME),
            RemoteControlPacket.TextInput("Halo KendaliP2P"),
            RemoteControlPacket.QualityChange(StreamQualityPreset.BALANCED),
            RemoteControlPacket.HeartbeatPing(1700000000L),
            RemoteControlPacket.HeartbeatPong(1700000000L),
        )
        for (pkt in packets) {
            val roundtrip = ControlPacketCodec.decode(ControlPacketCodec.encode(pkt))
            assertEquals(pkt, roundtrip)
        }
    }
}
