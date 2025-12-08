package com.gorunjinian.metrovault.core.crypto

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages session-based encryption keys for the application.
 *
 * Architecture:
 * - At login: PBKDF2 derives master key from password (slow, ~200ms)
 * - During session: HKDF derives purpose-specific keys instantly (<1ms)
 * - All wallet operations use the session key (no repeated PBKDF2)
 *
 * Security properties:
 * - PBKDF2 with 210,000 iterations (OWASP 2024 standard)
 * - HKDF-SHA256 for secure key derivation
 * - Keys stored only in RAM, wiped on logout
 * - Thread-safe singleton pattern
 */
class SessionKeyManager private constructor() {

    companion object {
        private const val TAG = "SessionKeyManager"
        private const val PBKDF2_ITERATIONS = 210000
        private const val KEY_SIZE_BITS = 256
        private const val KEY_SIZE_BYTES = 32
        private const val GCM_IV_SIZE = 12
        private const val GCM_TAG_BITS = 128

        @Volatile
        private var instance: SessionKeyManager? = null

        fun getInstance(): SessionKeyManager {
            return instance ?: synchronized(this) {
                instance ?: SessionKeyManager().also { instance = it }
            }
        }
    }

    // Master key derived from password via PBKDF2 (set at login)
    private var masterKey: ByteArray? = null

    // Cached derived keys for different purposes
    private var walletEncryptionKey: ByteArray? = null

    // Session state - observable for UI to react to session changes
    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    /**
     * Initializes the session by deriving master key from password.
     * This is the only slow operation (~200ms) - called once at login.
     *
     * @param password User's password
     * @param salt Salt from stored password hash
     * @return The derived master key (for password verification)
     */
    fun initializeSession(password: String, salt: ByteArray): ByteArray {
        Log.d(TAG, "Initializing session - deriving master key")
        val startTime = System.currentTimeMillis()

        // PBKDF2 key derivation (slow but secure)
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val derivedKey = factory.generateSecret(spec).encoded

        // Store master key for session
        masterKey = derivedKey.copyOf()

        // Pre-derive the wallet encryption key using HKDF
        walletEncryptionKey = hkdfDerive(derivedKey, "wallet-encryption".toByteArray())

        _isSessionActive.value = true

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "Session initialized in ${duration}ms")

        return derivedKey
    }

    /**
     * Gets the wallet encryption key for encrypting/decrypting wallet data.
     * This is instant (<1ms) as the key is pre-derived at login.
     *
     * @throws IllegalStateException if session is not active
     */
    fun getWalletEncryptionKey(): ByteArray {
        check(_isSessionActive.value && walletEncryptionKey != null) {
            "Session not active. Call initializeSession() first."
        }
        return walletEncryptionKey!!.copyOf()
    }

    /**
     * Encrypts data using AES-GCM with the session wallet key.
     *
     * @param plaintext Data to encrypt
     * @return IV (12 bytes) + ciphertext + auth tag
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val key = getWalletEncryptionKey()
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = SecretKeySpec(key, "AES")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)

            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)

            // Combine: IV + ciphertext
            iv + ciphertext
        } finally {
            key.fill(0)
        }
    }

    /**
     * Decrypts data using AES-GCM with the session wallet key.
     *
     * @param encrypted IV (12 bytes) + ciphertext + auth tag
     * @return Decrypted plaintext
     */
    fun decrypt(encrypted: ByteArray): ByteArray {
        val key = getWalletEncryptionKey()
        return try {
            val iv = encrypted.copyOfRange(0, GCM_IV_SIZE)
            val ciphertext = encrypted.copyOfRange(GCM_IV_SIZE, encrypted.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = SecretKeySpec(key, "AES")
            val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            cipher.doFinal(ciphertext)
        } finally {
            key.fill(0)
        }
    }

    /**
     * Clears the session, wiping all keys from memory.
     * Call this on logout, lock, or app termination.
     */
    fun clearSession() {
        Log.d(TAG, "Clearing session - wiping all keys")

        masterKey?.fill(0)
        masterKey = null

        walletEncryptionKey?.fill(0)
        walletEncryptionKey = null

        _isSessionActive.value = false
    }

    /**
     * HKDF-SHA256 key derivation.
     * Derives a new key from the master key for a specific purpose.
     *
     * This is a simplified HKDF implementation using HMAC-SHA256:
     * - Extract: HMAC(salt, input key material)
     * - Expand: HMAC(PRK, info || 0x01)
     *
     * @param inputKey Master key material
     * @param info Context/purpose string (e.g., "wallet-encryption")
     * @return Derived key (32 bytes)
     */
    private fun hkdfDerive(inputKey: ByteArray, info: ByteArray): ByteArray {
        // HKDF-Extract: Use a zero salt (acceptable when input is already strong)
        val salt = ByteArray(32) // Zero salt
        val prk = hmacSha256(salt, inputKey)

        // HKDF-Expand: Derive output key
        val okm = hmacSha256(prk, info + byteArrayOf(0x01))

        prk.fill(0) // Wipe intermediate key
        return okm
    }

    /**
     * HMAC-SHA256 computation
     */
    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /**
     * Generates a cryptographically secure random salt.
     */
    fun generateSalt(): ByteArray {
        val salt = ByteArray(32)
        SecureRandom().nextBytes(salt)
        return salt
    }
}
