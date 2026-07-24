package com.gorunjinian.metrovault.crypto

import com.gorunjinian.metrovault.core.crypto.KeyDerivation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * Pins the key-derivation algorithms to their historical behavior.
 *
 * CRITICAL: the HKDF vectors below were computed with an independent
 * implementation of the exact algorithm the original SessionKeyManager used
 * (HKDF-SHA256, 32-byte zero salt extract, single expand round with
 * info || 0x01). Existing vaults are encrypted with keys produced by this
 * algorithm — if these vectors ever fail, wallet data on updated devices
 * would become undecryptable. Do NOT update the vectors to make a failing
 * test pass; fix the implementation instead.
 */
class KeyDerivationTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val ikm = ByteArray(32) { it.toByte() }

    // ==================== HKDF pinned vectors ====================

    @Test
    fun `wallet encryption key matches pinned vector`() {
        assertArrayEquals(
            hex("7ffa201434de84d82d3bfd200e5f27c60c6b276464a603e6b8a892144ddefab8"),
            KeyDerivation.deriveWalletEncryptionKey(ikm)
        )
    }

    @Test
    fun `password verifier matches pinned vector`() {
        assertArrayEquals(
            hex("a4b126551c4cb56c67dc6f3108bb372d695e1001c01dec1ff99c66d793ed61b3"),
            KeyDerivation.deriveVerifier(ikm)
        )
    }

    @Test
    fun `hkdf depends on input key material`() {
        assertArrayEquals(
            hex("b9496a95e7c010cb126f80c95d1df9af0966f3797ff4023d8d14c498fde6f008"),
            KeyDerivation.deriveWalletEncryptionKey(ByteArray(32) { 0xAA.toByte() })
        )
    }

    @Test
    fun `verifier and encryption key are domain separated`() {
        val verifier = KeyDerivation.deriveVerifier(ikm)
        val encKey = KeyDerivation.deriveWalletEncryptionKey(ikm)
        assertFalse(verifier.contentEquals(encKey))
    }

    // ==================== PBKDF2 ====================

    @Test
    fun `pbkdf2 is deterministic and iteration-sensitive`() {
        val salt = ByteArray(32) { 7 }
        val a = KeyDerivation.pbkdf2("correct horse", salt, 1000)
        val b = KeyDerivation.pbkdf2("correct horse", salt, 1000)
        val c = KeyDerivation.pbkdf2("correct horse", salt, 2000)
        val d = KeyDerivation.pbkdf2("correct horsf", salt, 1000)
        assertEquals(32, a.size)
        assertArrayEquals(a, b)
        assertFalse(a.contentEquals(c))
        assertFalse(a.contentEquals(d))
    }

    // ==================== AES-GCM ====================

    @Test
    fun `aes gcm roundtrip and layout`() {
        val key = KeyDerivation.deriveWalletEncryptionKey(ikm)
        val plaintext = "wallet key material".toByteArray()

        val encrypted = KeyDerivation.encryptAesGcm(plaintext, key)
        // Layout: IV (12) + ciphertext (== plaintext length) + GCM tag (16)
        assertEquals(KeyDerivation.GCM_IV_SIZE + plaintext.size + 16, encrypted.size)

        assertArrayEquals(plaintext, KeyDerivation.decryptAesGcm(encrypted, key))
    }

    @Test
    fun `aes gcm uses a fresh iv per encryption`() {
        val key = KeyDerivation.deriveWalletEncryptionKey(ikm)
        val plaintext = "same plaintext".toByteArray()
        val a = KeyDerivation.encryptAesGcm(plaintext, key)
        val b = KeyDerivation.encryptAesGcm(plaintext, key)
        assertFalse(a.copyOfRange(0, 12).contentEquals(b.copyOfRange(0, 12)))
    }

    @Test(expected = AEADBadTagException::class)
    fun `aes gcm rejects wrong key`() {
        val encrypted = KeyDerivation.encryptAesGcm(
            "secret".toByteArray(),
            KeyDerivation.deriveWalletEncryptionKey(ikm)
        )
        KeyDerivation.decryptAesGcm(encrypted, ByteArray(32) { 0x55 })
    }

    @Test
    fun `aes gcm rejects truncated ciphertext`() {
        val result = runCatching {
            KeyDerivation.decryptAesGcm(ByteArray(KeyDerivation.GCM_IV_SIZE), ByteArray(32))
        }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
