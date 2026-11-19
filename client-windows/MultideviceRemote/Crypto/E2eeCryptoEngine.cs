using System.Security.Cryptography;

namespace MultideviceRemote.Crypto;

/// <summary>
/// AES-256-GCM encryption/decryption engine.
/// Format wire: nonce(12 bytes) | tag(16 bytes) | ciphertext
/// Identik dengan implementasi Android (AesGcm Kotlin/JVM).
/// </summary>
public sealed class E2eeCryptoEngine
{
    private const int NonceSize = 12; // AES-GCM standard nonce
    private const int TagSize = 16;   // AES-GCM 128-bit tag

    /// <summary>Encrypt plaintext dengan key 256-bit. Returns nonce+tag+ciphertext.</summary>
    public byte[] Encrypt(byte[] plaintext, byte[] key)
    {
        ArgumentNullException.ThrowIfNull(plaintext);
        ArgumentNullException.ThrowIfNull(key);
        if (key.Length != 32) throw new ArgumentException("Key harus 256-bit (32 bytes)", nameof(key));

        using var aes = new AesGcm(key, TagSize);

        var nonce = new byte[NonceSize];
        RandomNumberGenerator.Fill(nonce);

        var ciphertext = new byte[plaintext.Length];
        var tag = new byte[TagSize];

        aes.Encrypt(nonce, plaintext, ciphertext, tag);

        // Gabungkan: nonce(12) + tag(16) + ciphertext
        var result = new byte[NonceSize + TagSize + ciphertext.Length];
        nonce.CopyTo(result, 0);
        tag.CopyTo(result, NonceSize);
        ciphertext.CopyTo(result, NonceSize + TagSize);
        return result;
    }

    /// <summary>Decrypt data format nonce+tag+ciphertext dengan key 256-bit.</summary>
    public byte[] Decrypt(byte[] data, byte[] key)
    {
        ArgumentNullException.ThrowIfNull(data);
        ArgumentNullException.ThrowIfNull(key);
        if (key.Length != 32) throw new ArgumentException("Key harus 256-bit (32 bytes)", nameof(key));
        if (data.Length < NonceSize + TagSize) throw new ArgumentException("Data terlalu pendek", nameof(data));

        using var aes = new AesGcm(key, TagSize);

        var nonce = data[..NonceSize];
        var tag = data[NonceSize..(NonceSize + TagSize)];
        var ciphertext = data[(NonceSize + TagSize)..];

        var plaintext = new byte[ciphertext.Length];
        aes.Decrypt(nonce, ciphertext, tag, plaintext);
        return plaintext;
    }

    /// <summary>Derive 256-bit session key dari shared ECDH secret menggunakan SHA-256.</summary>
    public static byte[] DeriveSessionKey(byte[] ecdhSharedSecret)
    {
        return SHA256.HashData(ecdhSharedSecret);
    }
}
