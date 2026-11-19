using System.Security.Cryptography;
using System.Text;

namespace MultideviceRemote.Crypto;

/// <summary>
/// End-to-End Encryption Engine — identik dengan Android E2eeCryptoEngine.
/// 
/// Protokol:
///   1. createEphemeralInit()   → ECDH P-256 key pair + 16-byte nonce
///   2. deriveSessionKeys()     → ECDH shared secret + PBKDF2(password) → HKDF → AES-256-GCM key + authConfirmKey
///   3. computeRoleProof()      → HMAC-SHA256(authConfirmKey, role|sessionId)
///   4. encryptEnvelope()       → [1B direction][8B seq][12B IV][ciphertext+tag]
///   5. decryptEnvelope()       → verify direction, replay-protection, AES-GCM decrypt
/// </summary>
public sealed class E2eeCryptoEngine
{
    // ── Direction constants (match Android) ──────────────────────────────────
    public const byte DirControllerToHost = 0x01;
    public const byte DirHostToController = 0x02;

    private const int GcmIvLength = 12;
    private const int GcmTagBits = 128;

    // ── Handshake init ───────────────────────────────────────────────────────

    public sealed record EphemeralHandshakeInit(
        ECDiffieHellman KeyPair,
        string PublicKeyB64,
        string NonceB64
    );

    public sealed record DerivedSessionKeys(
        byte[] AuthConfirmKey,
        byte[] AesSessionKey,     // raw 32 bytes AES-256
        string SafetyFingerprint
    );

    /// <summary>Buat ECDH P-256 key pair + random 16-byte nonce.</summary>
    public EphemeralHandshakeInit CreateEphemeralInit()
    {
        var ecdh = ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);
        var pubKeyBytes = ecdh.PublicKey.ExportSubjectPublicKeyInfo(); // X.509/SubjectPublicKeyInfo (sama dengan Android)
        var nonce = RandomNumberGenerator.GetBytes(16);
        return new EphemeralHandshakeInit(
            KeyPair: ecdh,
            PublicKeyB64: Convert.ToBase64String(pubKeyBytes),
            NonceB64: Convert.ToBase64String(nonce)
        );
    }

    /// <summary>
    /// Derive session keys dari ECDH shared secret + password.
    /// Match Android: ECDH(P-256) + PBKDF2(65536) + HKDF-SHA256 → authConfirmKey + aesSessionKey.
    /// </summary>
    public DerivedSessionKeys DeriveSessionKeys(
        ECDiffieHellman localKeyPair,
        string remotePublicKeyB64,
        string controllerNonceB64,
        string hostNonceB64,
        string sessionPassword,
        bool isController)
    {
        // 1. Import remote public key (SubjectPublicKeyInfo format)
        var remotePubBytes = Convert.FromBase64String(remotePublicKeyB64);
        using var remoteEcdh = ECDiffieHellman.Create();
        remoteEcdh.ImportSubjectPublicKeyInfo(remotePubBytes, out _);

        // 2. ECDH shared secret
        var ecdhSecret = localKeyPair.DeriveRawSecretAgreement(remoteEcdh.PublicKey);

        // 3. Combined salt = controllerNonce + hostNonce (match Android)
        var controllerNonce = Convert.FromBase64String(controllerNonceB64);
        var hostNonce = Convert.FromBase64String(hostNonceB64);
        var combinedSalt = Concat(controllerNonce, hostNonce);

        // 4. PBKDF2-HMAC-SHA256(password, salt=combinedSalt, iter=65536, keyLen=32)
        var passwordKey = Rfc2898DeriveBytes.Pbkdf2(
            password: Encoding.UTF8.GetBytes(sessionPassword),
            salt: combinedSalt,
            iterations: 65_536,
            hashAlgorithm: HashAlgorithmName.SHA256,
            outputLength: 32
        );

        // 5. IKM = ecdhSecret || passwordKey
        var ikm = Concat(ecdhSecret, passwordKey);

        // 6. HKDF-Extract: PRK = HMAC-SHA256(key=salt, data=ikm)
        var prk = HkdfExtract(salt: combinedSalt, ikm: ikm);

        // 7. HKDF-Expand → authConfirmKey + aesKeyBytes
        var authConfirmKey = HkdfExpand(prk, "KendaliP2P-AuthConfirm-v1"u8.ToArray(), 32);
        var aesKeyBytes = HkdfExpand(prk, "KendaliP2P-AES256GCM-v1"u8.ToArray(), 32);

        // 8. Safety fingerprint (SHA-256 of controllerPub || hostPub || authKey)
        var localPubBytes = localKeyPair.PublicKey.ExportSubjectPublicKeyInfo();
        var controllerPub = isController ? localPubBytes : remotePubBytes;
        var hostPub = isController ? remotePubBytes : localPubBytes;
        var fingerprint = ComputeSafetyFingerprint(controllerPub, hostPub, authConfirmKey);

        // Wipe sensitive intermediates
        CryptoUtils.SecureWipe(ecdhSecret);
        CryptoUtils.SecureWipe(passwordKey);
        CryptoUtils.SecureWipe(ikm);
        CryptoUtils.SecureWipe(prk);

        return new DerivedSessionKeys(
            AuthConfirmKey: authConfirmKey,
            AesSessionKey: aesKeyBytes,
            SafetyFingerprint: fingerprint
        );
    }

    /// <summary>HMAC-SHA256(authConfirmKey, role||sessionId) — match Android computeRoleProof.</summary>
    public string ComputeRoleProof(byte[] authConfirmKey, string roleLabel, string sessionId)
    {
        using var hmac = new HMACSHA256(authConfirmKey);
        hmac.TransformBlock(Encoding.UTF8.GetBytes(roleLabel), 0, Encoding.UTF8.GetByteCount(roleLabel), null, 0);
        var sessionIdBytes = Encoding.UTF8.GetBytes(sessionId);
        hmac.TransformFinalBlock(sessionIdBytes, 0, sessionIdBytes.Length);
        return Convert.ToBase64String(hmac.Hash!);
    }

    /// <summary>Constant-time comparison untuk proof verification.</summary>
    public bool VerifyProofConstantTime(string expectedB64, string actualB64)
    {
        try
        {
            var a = Convert.FromBase64String(expectedB64);
            var b = Convert.FromBase64String(actualB64);
            return CryptographicOperations.FixedTimeEquals(a, b);
        }
        catch { return false; }
    }

    // ── Envelope encrypt/decrypt ─────────────────────────────────────────────

    /// <summary>
    /// Wire format: [1B direction][8B seq big-endian][12B IV][ciphertext+tag(16B)]
    /// Match Android encryptEnvelope.
    /// </summary>
    public byte[] EncryptEnvelope(byte[] aesKey, byte[] plaintext, long sequenceNumber, byte direction)
    {
        var iv = RandomNumberGenerator.GetBytes(GcmIvLength);
        var aad = BuildAad(sequenceNumber, direction);

        using var aesGcm = new AesGcm(aesKey, GcmTagBits / 8);
        var ciphertext = new byte[plaintext.Length];
        var tag = new byte[GcmTagBits / 8];
        aesGcm.Encrypt(iv, plaintext, ciphertext, tag, aad);

        // Assemble: direction(1) + seq(8) + iv(12) + ciphertext+tag
        var result = new byte[1 + 8 + GcmIvLength + ciphertext.Length + tag.Length];
        var span = result.AsSpan();
        span[0] = direction;
        WriteBigEndianInt64(span[1..9], sequenceNumber);
        iv.CopyTo(span[9..]);
        ciphertext.CopyTo(span[(9 + GcmIvLength)..]);
        tag.CopyTo(span[(9 + GcmIvLength + ciphertext.Length)..]);
        return result;
    }

    /// <summary>Decrypt envelope dan return (seq, plaintext). Throws SecurityException on failure.</summary>
    public (long Seq, byte[] Plaintext) DecryptEnvelope(
        byte[] aesKey, byte[] envelope, byte expectedDirection, long minSequenceNumber)
    {
        const int headerSize = 1 + 8 + GcmIvLength;
        const int tagSize = GcmTagBits / 8;
        if (envelope.Length < headerSize + tagSize)
            throw new CryptographicException("Paket terenkripsi tidak lengkap.");

        var span = envelope.AsSpan();
        var dir = span[0];
        if (dir != expectedDirection)
            throw new CryptographicException($"Arah paket E2EE tidak sesuai: {dir} != {expectedDirection}");

        var seq = ReadBigEndianInt64(span[1..9]);
        if (seq < minSequenceNumber)
            throw new CryptographicException($"Paket replay ditolak (seq={seq} < {minSequenceNumber})");

        var iv = span[9..(9 + GcmIvLength)].ToArray();
        var ciphertextWithTag = span[(9 + GcmIvLength)..].ToArray();
        var ciphertext = ciphertextWithTag[..^tagSize];
        var tag = ciphertextWithTag[^tagSize..];
        var aad = BuildAad(seq, dir);

        using var aesGcm = new AesGcm(aesKey, tagSize);
        var plaintext = new byte[ciphertext.Length];
        aesGcm.Decrypt(iv, ciphertext, tag, plaintext, aad);
        return (seq, plaintext);
    }

    /// <summary>Encrypt string to base64 envelope.</summary>
    public string EncryptTextToBase64(byte[] aesKey, string text, long sequenceNumber, byte direction)
    {
        var encrypted = EncryptEnvelope(aesKey, Encoding.UTF8.GetBytes(text), sequenceNumber, direction);
        return Convert.ToBase64String(encrypted);
    }

    /// <summary>Decrypt base64 envelope to string.</summary>
    public (long Seq, string Text) DecryptBase64ToText(
        byte[] aesKey, string payloadB64, byte expectedDirection, long minSequenceNumber)
    {
        var raw = Convert.FromBase64String(payloadB64);
        var (seq, plain) = DecryptEnvelope(aesKey, raw, expectedDirection, minSequenceNumber);
        return (seq, Encoding.UTF8.GetString(plain));
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private static byte[] BuildAad(long seq, byte direction)
    {
        // Match Android buildAad: [1B direction][8B seq]
        var buf = new byte[9];
        buf[0] = direction;
        WriteBigEndianInt64(buf.AsSpan(1), seq);
        return buf;
    }

    /// <summary>HKDF-Extract: PRK = HMAC-SHA256(key=salt, data=ikm)</summary>
    private static byte[] HkdfExtract(byte[] salt, byte[] ikm)
    {
        using var hmac = new HMACSHA256(salt);
        return hmac.ComputeHash(ikm);
    }

    /// <summary>HKDF-Expand: match Android step-by-step (counter=1, T=empty init)</summary>
    private static byte[] HkdfExpand(byte[] prk, byte[] info, int length)
    {
        var result = new byte[length];
        byte[] t = [];
        int offset = 0, counter = 1;

        using var hmac = new HMACSHA256(prk);
        while (offset < length)
        {
            hmac.Initialize();
            // T(n) = HMAC-SHA256(PRK, T(n-1) || info || counter)
            var input = Concat(t, info, [(byte)counter]);
            t = hmac.ComputeHash(input);
            int copyLen = Math.Min(t.Length, length - offset);
            t.AsSpan(0, copyLen).CopyTo(result.AsSpan(offset));
            offset += copyLen;
            counter++;
        }
        return result;
    }

    private static string ComputeSafetyFingerprint(byte[] controllerPub, byte[] hostPub, byte[] authKey)
    {
        var hash = SHA256.HashData(Concat(controllerPub, hostPub, authKey));
        // Format: 6 readable 4-hex blocks (match Android format)
        var sb = new System.Text.StringBuilder();
        for (int i = 0; i < 12; i += 2)
        {
            if (i > 0) sb.Append(' ');
            sb.Append($"{hash[i]:X2}{hash[i + 1]:X2}");
        }
        return sb.ToString();
    }

    private static byte[] Concat(params byte[][] arrays)
    {
        int totalLen = arrays.Sum(a => a.Length);
        var result = new byte[totalLen];
        int offset = 0;
        foreach (var a in arrays)
        {
            a.CopyTo(result, offset);
            offset += a.Length;
        }
        return result;
    }

    private static void WriteBigEndianInt64(Span<byte> dest, long value)
    {
        dest[0] = (byte)(value >> 56);
        dest[1] = (byte)(value >> 48);
        dest[2] = (byte)(value >> 40);
        dest[3] = (byte)(value >> 32);
        dest[4] = (byte)(value >> 24);
        dest[5] = (byte)(value >> 16);
        dest[6] = (byte)(value >> 8);
        dest[7] = (byte)value;
    }

    private static long ReadBigEndianInt64(ReadOnlySpan<byte> src)
    {
        return ((long)src[0] << 56) | ((long)src[1] << 48) | ((long)src[2] << 40) | ((long)src[3] << 32)
             | ((long)src[4] << 24) | ((long)src[5] << 16) | ((long)src[6] << 8) | src[7];
    }
}

/// <summary>Crypto utility helpers.</summary>
public static class CryptoUtils
{
    public static void SecureWipe(byte[] data)
    {
        if (data is null) return;
        CryptographicOperations.ZeroMemory(data);
    }
}
