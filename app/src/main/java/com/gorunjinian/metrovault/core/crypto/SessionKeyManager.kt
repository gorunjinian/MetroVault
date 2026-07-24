package com.gorunjinian.metrovault.core.crypto

import com.gorunjinian.metrovault.core.logging.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Manages session-based encryption keys for the application.
 *
 * Architecture:
 * - At login: PBKDF2 derives the master key from the password (slow, ~200ms+)
 * - During session: HKDF-derived wallet encryption key is used for all
 *   encrypt/decrypt operations (instant)
 * - All key material lives only in RAM and is wiped on logout
 *
 * Thread safety: wallet saves run on background dispatchers while
 * [clearSession] can fire from lifecycle callbacks (emergency wipe). All key
 * state is guarded by [lock] so a concurrent clear can never hand a
 * partially-zeroed key to an encrypt operation.
 */
class SessionKeyManager private constructor() {

    companion object {
        private const val TAG = "SessionKeyManager"

        @Volatile
        private var instance: SessionKeyManager? = null

        fun getInstance(): SessionKeyManager {
            return instance ?: synchronized(this) {
                instance ?: SessionKeyManager().also { instance = it }
            }
        }
    }

    private val lock = Any()

    // Master key derived from password via PBKDF2 (set at login)
    private var masterKey: ByteArray? = null

    // Cached derived key for wallet data encryption
    private var walletEncryptionKey: ByteArray? = null

    // Session state - observable for UI to react to session changes
    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    /**
     * Initializes the session from an already-derived PBKDF2 master key.
     * Any previous session keys are wiped first. The caller retains ownership
     * of [newMasterKey] and must wipe its own copy.
     */
    fun initializeSessionWithMasterKey(newMasterKey: ByteArray) {
        synchronized(lock) {
            masterKey?.fill(0)
            walletEncryptionKey?.fill(0)
            masterKey = newMasterKey.copyOf()
            walletEncryptionKey = KeyDerivation.deriveWalletEncryptionKey(newMasterKey)
        }
        _isSessionActive.value = true
        AppLog.d(TAG) { "Session initialized" }
    }

    /**
     * Gets a copy of the wallet encryption key. Caller must wipe it after use.
     *
     * @throws IllegalStateException if session is not active
     */
    fun getWalletEncryptionKey(): ByteArray = synchronized(lock) {
        val key = walletEncryptionKey
        check(key != null) { "Session not active. Call initializeSessionWithMasterKey() first." }
        key.copyOf()
    }

    /**
     * Constant-time check whether [candidate] equals the active session's
     * wallet encryption key. Used by password change to decide whether the
     * changed vault is the one the current session belongs to.
     */
    fun isCurrentEncryptionKey(candidate: ByteArray): Boolean = synchronized(lock) {
        val key = walletEncryptionKey ?: return false
        MessageDigest.isEqual(key, candidate)
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
            KeyDerivation.encryptAesGcm(plaintext, key)
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
            KeyDerivation.decryptAesGcm(encrypted, key)
        } finally {
            key.fill(0)
        }
    }

    /**
     * Clears the session, wiping all keys from memory.
     * Call this on logout, lock, or app termination.
     */
    fun clearSession() {
        AppLog.d(TAG) { "Clearing session - wiping all keys" }
        _isSessionActive.value = false
        synchronized(lock) {
            masterKey?.fill(0)
            masterKey = null
            walletEncryptionKey?.fill(0)
            walletEncryptionKey = null
        }
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
