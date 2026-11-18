package com.agoy507.mdremote.crypto

import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-End Encryption Engine implementing:
 * - Ephemeral ECDH (secp256r1 / P-256) key exchange
 * - Password-authenticated key derivation via PBKDF2-HMAC-SHA256 + HKDF-SHA256
 * - Mutual HMAC-SHA256 proof verification (zero plaintext password transmission)
 * - AES-256-GCM authenticated encryption with 96-bit CSPRNG IV, AAD sequence binding, and anti-replay protection
 */
class E2eeCryptoEngine {

    data class EphemeralHandshakeInit(
        val keyPair: KeyPair,
        val publicKeyB64: String,
        val nonceB64: String,
    )

    data class DerivedSessionKeys(
        val authConfirmKey: ByteArray,
        val aesSessionKey: SecretKey,
        val safetyFingerprint: String,
    )

    private val secureRandom = SecureRandom()

    fun createEphemeralInit(): EphemeralHandshakeInit {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        val keyPair = generator.generateKeyPair()
        val nonce = ByteArray(16).also { secureRandom.nextBytes(it) }
        return EphemeralHandshakeInit(
            keyPair = keyPair,
            publicKeyB64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP),
            nonceB64 = Base64.encodeToString(nonce, Base64.NO_WRAP),
        )
    }

    fun deriveSessionKeys(
        localKeyPair: KeyPair,
        remotePublicKeyB64: String,
        controllerNonceB64: String,
        hostNonceB64: String,
        sessionPassword: String,
        isController: Boolean,
    ): DerivedSessionKeys {
        val remotePubBytes = Base64.decode(remotePublicKeyB64, Base64.NO_WRAP)
        val remotePubKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(remotePubBytes))

        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(localKeyPair.private)
        agreement.doPhase(remotePubKey, true)
        val ecdhSecret = agreement.generateSecret()

        val controllerNonce = Base64.decode(controllerNonceB64, Base64.NO_WRAP)
        val hostNonce = Base64.decode(hostNonceB64, Base64.NO_WRAP)
        val combinedSalt = controllerNonce + hostNonce

        // Stretch session password with PBKDF2-HMAC-SHA256 bound to both ephemeral nonces
        val pbkdf2Spec = PBEKeySpec(sessionPassword.toCharArray(), combinedSalt, 65_536, 256)
        val passwordKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(pbkdf2Spec)
            .encoded

        val ikm = ecdhSecret + passwordKey
        val prk = hkdfExtract(salt = combinedSalt, ikm = ikm)

        val authConfirmKey = hkdfExpand(prk, "KendaliP2P-AuthConfirm-v1".toByteArray(), 32)
        val aesKeyBytes = hkdfExpand(prk, "KendaliP2P-AES256GCM-v1".toByteArray(), 32)

        val controllerPub = if (isController) localKeyPair.public.encoded else remotePubBytes
        val hostPub = if (isController) remotePubBytes else localKeyPair.public.encoded
        val safetyFingerprint = computeSafetyFingerprint(controllerPub, hostPub, authConfirmKey)

        // Wipe intermediate secret arrays from memory
        ecdhSecret.fill(0)
        passwordKey.fill(0)
        ikm.fill(0)
        prk.fill(0)

        return DerivedSessionKeys(
            authConfirmKey = authConfirmKey,
            aesSessionKey = SecretKeySpec(aesKeyBytes, "AES"),
            safetyFingerprint = safetyFingerprint,
        )
    }

    fun computeRoleProof(
        authConfirmKey: ByteArray,
        roleLabel: String,
        sessionId: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(authConfirmKey, "HmacSHA256"))
        mac.update(roleLabel.toByteArray())
        mac.update(sessionId.toByteArray())
        return Base64.encodeToString(mac.doFinal(), Base64.NO_WRAP)
    }

    fun verifyProofConstantTime(expectedB64: String, actualB64: String): Boolean {
        val a = Base64.decode(expectedB64, Base64.NO_WRAP)
        val b = Base64.decode(actualB64, Base64.NO_WRAP)
        return MessageDigest.isEqual(a, b)
    }

    fun encryptEnvelope(
        aesKey: SecretKey,
        plaintext: ByteArray,
        sequenceNumber: Long,
        direction: Byte,
    ): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }
        val aad = buildAad(sequenceNumber, direction)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)

        // Wire format: [1B direction][8B seq][12B IV][ciphertext + 16B tag]
        return ByteBuffer.allocate(1 + 8 + GCM_IV_LENGTH + ciphertext.size)
            .put(direction)
            .putLong(sequenceNumber)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    fun decryptEnvelope(
        aesKey: SecretKey,
        envelope: ByteArray,
        expectedDirection: Byte,
        minSequenceNumber: Long,
    ): Pair<Long, ByteArray> {
        if (envelope.size < 1 + 8 + GCM_IV_LENGTH + 16) {
            throw SecurityException("Paket terenkripsi tidak lengkap.")
        }
        val buffer = ByteBuffer.wrap(envelope)
        val direction = buffer.get()
        if (direction != expectedDirection) {
            throw SecurityException("Arah paket E2EE tidak sesuai.")
        }
        val seq = buffer.long
        if (seq < minSequenceNumber) {
            throw SecurityException("Paket replay ditolak (seq=$seq < $minSequenceNumber).")
        }
        val iv = ByteArray(GCM_IV_LENGTH).also { buffer.get(it) }
        val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val aad = buildAad(seq, direction)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(ciphertext)
        return seq to plaintext
    }

    fun encryptTextToBase64(
        aesKey: SecretKey,
        text: String,
        sequenceNumber: Long,
        direction: Byte,
    ): String {
        val encrypted = encryptEnvelope(aesKey, text.toByteArray(Charsets.UTF_8), sequenceNumber, direction)
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun decryptBase64ToText(
        aesKey: SecretKey,
        payloadB64: String,
        expectedDirection: Byte,
        minSequenceNumber: Long,
    ): Pair<Long, String> {
        val raw = Base64.decode(payloadB64, Base64.NO_WRAP)
        val (seq, plain) = decryptEnvelope(aesKey, raw, expectedDirection, minSequenceNumber)
        return seq to plain.toString(Charsets.UTF_8)
    }

    private fun buildAad(seq: Long, direction: Byte): ByteArray =
        ByteBuffer.allocate(9).put(direction).putLong(seq).array()

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, copyLen)
            offset += copyLen
            counter++
        }
        return result
    }

    private fun computeSafetyFingerprint(
        controllerPub: ByteArray,
        hostPub: ByteArray,
        authKey: ByteArray,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(controllerPub)
        digest.update(hostPub)
        digest.update(authKey)
        val hash = digest.digest()
        // Format as 6 readable 4-hex blocks (e.g. A4F2 9C01 7E34 11B8 5D20 8F4A)
        return (0 until 12 step 2).joinToString(" ") { idx ->
            val hi = hash[idx].toInt() and 0xFF
            val lo = hash[idx + 1].toInt() and 0xFF
            "%02X%02X".format(hi, lo)
        }
    }

    companion object {
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128

        const val DIR_CONTROLLER_TO_HOST: Byte = 0x01
        const val DIR_HOST_TO_CONTROLLER: Byte = 0x02
    }
}
