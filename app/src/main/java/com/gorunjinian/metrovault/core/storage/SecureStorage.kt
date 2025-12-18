package com.gorunjinian.metrovault.core.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import androidx.core.content.edit
import com.gorunjinian.metrovault.core.crypto.LoginAttemptManager
import com.gorunjinian.metrovault.core.crypto.SessionKeyManager
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.data.model.WalletSecrets
import org.json.JSONArray

/**
 * Secure password hash with per-user salt.
 * Stored format: salt:hash:iterations (Base64 encoded)
 */
data class PasswordHash(
    val hash: ByteArray,
    val salt: ByteArray,
    val iterations: Int = 210000
) {
    fun toStorageString(): String {
        val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val hashB64 = Base64.encodeToString(hash, Base64.NO_WRAP)
        return "$saltB64:$hashB64:$iterations"
    }

    companion object {
        fun fromStorageString(str: String): PasswordHash {
            val parts = str.split(":")
            require(parts.size == 3) { "Invalid password hash format" }
            return PasswordHash(
                salt = Base64.decode(parts[0], Base64.NO_WRAP),
                hash = Base64.decode(parts[1], Base64.NO_WRAP),
                iterations = parts[2].toInt()
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PasswordHash) return false
        return hash.contentEquals(other.hash) &&
                salt.contentEquals(other.salt) &&
                iterations == other.iterations
    }

    override fun hashCode(): Int {
        var result = hash.contentHashCode()
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + iterations
        return result
    }
}

/**
 * Simplified secure storage for wallet data.
 *
 * Architecture:
 * - Password verification: PBKDF2 with 210k iterations (at login only)
 * - Wallet data: Encrypted with session key (derived via HKDF at login)
 * - Storage: EncryptedSharedPreferences (Android Keystore backed)
 *
 * Security:
 * - Two encryption layers: Session key (AES-GCM) + EncryptedSharedPreferences
 * - No repeated PBKDF2 - session key used for all operations
 * - Metadata stored as plain JSON (within EncryptedSharedPreferences)
 * - Secrets encrypted with session key before storage
 *
 * Performance:
 * - Login: ~200ms (PBKDF2 key derivation)
 * - All other operations: <5ms (just AES-GCM)
 * 
 * Marked as @Stable for Compose: identity doesn't change, prevents unnecessary recomposition.
 * 
 * Note: EncryptedSharedPreferences is deprecated but still functional and secure.
 * The recommended migration path is DataStore + Tink, but there's no urgent security risk
 * as the seed data is additionally encrypted with PBKDF2-derived session keys.
 * 
 * TODO: Migrate to DataStore + Tink when Google provides clearer guidance.
 */
@Stable
@Suppress("DEPRECATION")
class SecureStorage(private val context: Context) {

    private val sessionKeyManager = SessionKeyManager.getInstance()
    private val loginAttemptManager = LoginAttemptManager(context)

    companion object {
        private const val TAG = "SecureStorage"
        private const val PREFS_NAME = "metrovault_secure_prefs"
        private const val DECOY_PREFS_NAME = "metrovault_decoy_prefs"
        private const val KEY_PASSWORD_HASH = "password_hash"
        private const val KEY_DECOY_PASSWORD_HASH = "decoy_password_hash"
        private const val KEY_WALLET_IDS = "wallet_ids"
        private const val KEY_WALLET_ORDER = "wallet_order"
        // Migration keys for crash recovery during password change
        private const val KEY_PASSWORD_MIGRATION_PENDING = "password_migration_pending"
        private const val KEY_PASSWORD_MIGRATION_OLD_SALT = "password_migration_old_salt"
    }

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val mainPrefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val decoyPrefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            DECOY_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ============================================================================
    // PASSWORD MANAGEMENT
    // ============================================================================

    /**
     * Sets the main password. Called on first launch.
     */
    fun setMainPassword(password: String): Boolean {
        if (hasMainPassword()) return false
        return try {
            val salt = sessionKeyManager.generateSalt()
            val hash = derivePasswordHash(password, salt)
            val passwordHash = PasswordHash(hash, salt)
            mainPrefs.edit { putString(KEY_PASSWORD_HASH, passwordHash.toStorageString()) }
            Log.d(TAG, "Main password set")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set main password: ${e.message}", e)
            false
        }
    }

    /**
     * Sets the decoy password.
     */
    fun setDecoyPassword(password: String): Boolean {
        if (hasDecoyPassword()) return false

        // Ensure it's different from main password
        if (verifyMainPassword(password)) {
            Log.w(TAG, "Decoy password cannot match main password")
            return false
        }

        return try {
            val salt = sessionKeyManager.generateSalt()
            val hash = derivePasswordHash(password, salt)
            val passwordHash = PasswordHash(hash, salt)
            decoyPrefs.edit { putString(KEY_DECOY_PASSWORD_HASH, passwordHash.toStorageString()) }
            Log.d(TAG, "Decoy password set")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set decoy password: ${e.message}", e)
            false
        }
    }

    fun hasMainPassword(): Boolean = mainPrefs.contains(KEY_PASSWORD_HASH)

    fun hasDecoyPassword(): Boolean = decoyPrefs.contains(KEY_DECOY_PASSWORD_HASH)

    /**
     * Verifies password with rate limiting.
     * On success, initializes the session key for subsequent operations.
     *
     * @return Pair<isValid, errorMessage>
     */
    fun verifyPassword(password: String): Pair<Boolean, String?> {
        // Check rate limiting first
        if (loginAttemptManager.isLockedOut()) {
            val remaining = loginAttemptManager.getRemainingLockoutTime()
            return Pair(false, "Too many failed attempts. Try again in ${loginAttemptManager.formatRemainingTime(remaining)}")
        }

        // Try main password
        val mainHashStr = mainPrefs.getString(KEY_PASSWORD_HASH, null)
        if (mainHashStr != null) {
            try {
                val storedHash = PasswordHash.fromStorageString(mainHashStr)
                val computedHash = derivePasswordHash(password, storedHash.salt)
                if (MessageDigest.isEqual(computedHash, storedHash.hash)) {
                    // Initialize session with the derived key
                    sessionKeyManager.initializeSession(password, storedHash.salt)
                    loginAttemptManager.resetAttempts()
                    Log.d(TAG, "Main password verified, session initialized")
                    return Pair(true, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error verifying main password: ${e.message}")
            }
        }

        // Try decoy password
        val decoyHashStr = decoyPrefs.getString(KEY_DECOY_PASSWORD_HASH, null)
        if (decoyHashStr != null) {
            try {
                val storedHash = PasswordHash.fromStorageString(decoyHashStr)
                val computedHash = derivePasswordHash(password, storedHash.salt)
                if (MessageDigest.isEqual(computedHash, storedHash.hash)) {
                    // Initialize session with the derived key
                    sessionKeyManager.initializeSession(password, storedHash.salt)
                    loginAttemptManager.resetAttempts()
                    Log.d(TAG, "Decoy password verified, session initialized")
                    return Pair(true, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error verifying decoy password: ${e.message}")
            }
        }

        // Failed - record attempt
        val lockoutUntil = loginAttemptManager.recordFailedAttempt()
        val message = if (lockoutUntil > System.currentTimeMillis()) {
            "Incorrect password. Locked out for ${loginAttemptManager.formatRemainingTime(lockoutUntil - System.currentTimeMillis())}"
        } else {
            "Incorrect password"
        }
        return Pair(false, message)
    }

    /**
     * Simple password verification without rate limiting (for internal checks)
     */
    fun verifyPasswordSimple(password: String): Boolean {
        return verifyMainPassword(password) || verifyDecoyPassword(password)
    }

    private fun verifyMainPassword(password: String): Boolean {
        val hashStr = mainPrefs.getString(KEY_PASSWORD_HASH, null) ?: return false
        return try {
            val storedHash = PasswordHash.fromStorageString(hashStr)
            val computedHash = derivePasswordHash(password, storedHash.salt)
            MessageDigest.isEqual(computedHash, storedHash.hash)
        } catch (e: Exception) {
            false
        }
    }

    private fun verifyDecoyPassword(password: String): Boolean {
        val hashStr = decoyPrefs.getString(KEY_DECOY_PASSWORD_HASH, null) ?: return false
        return try {
            val storedHash = PasswordHash.fromStorageString(hashStr)
            val computedHash = derivePasswordHash(password, storedHash.salt)
            MessageDigest.isEqual(computedHash, storedHash.hash)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if the given password is the decoy password
     */
    fun isDecoyPassword(password: String): Boolean = verifyDecoyPassword(password)

    /**
     * PBKDF2 key derivation (used only for password verification)
     */
    private fun derivePasswordHash(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, 210000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    // ============================================================================
    // PASSWORD CHANGE (Atomic with crash recovery)
    // ============================================================================

    /**
     * Changes the main password with atomic transaction safety.
     *
     * Strategy:
     * 1. Load all secrets with OLD session key
     * 2. Pre-encrypt all secrets with NEW key (in memory)
     * 3. Write everything atomically in a single SharedPreferences commit
     * 4. Update session to new key
     *
     * If app crashes during step 3, data remains in old format (old password still works).
     * The atomic commit ensures either ALL data is updated or NONE.
     */
    fun changeMainPassword(oldPassword: String, newPassword: String): Boolean {
        if (!verifyMainPassword(oldPassword)) return false
        if (isDecoyPassword(oldPassword)) return false

        return try {
            changePasswordInternal(oldPassword, newPassword, isDecoy = false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to change main password: ${e.message}", e)
            false
        }
    }

    /**
     * Changes the decoy password with atomic transaction safety.
     */
    fun changeDecoyPassword(oldPassword: String, newPassword: String): Boolean {
        if (!verifyDecoyPassword(oldPassword)) return false

        return try {
            changePasswordInternal(oldPassword, newPassword, isDecoy = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to change decoy password: ${e.message}", e)
            false
        }
    }

    /**
     * Internal implementation for atomic password change.
     * Uses a single SharedPreferences.Editor.commit() for atomicity.
     */
    private fun changePasswordInternal(oldPassword: String, newPassword: String, isDecoy: Boolean): Boolean {
        val prefs = if (isDecoy) decoyPrefs else mainPrefs
        val passwordKey = if (isDecoy) KEY_DECOY_PASSWORD_HASH else KEY_PASSWORD_HASH

        // PHASE 1: Load all secrets with current (old) session key
        // Session is already initialized from verify*Password call
        val walletIds = getWalletIds(isDecoy)
        val secretsMap = mutableMapOf<String, WalletSecrets>()

        for (walletId in walletIds) {
            val secrets = loadWalletSecrets(walletId, isDecoy)
            if (secrets != null) {
                secretsMap[walletId] = secrets
            } else {
                Log.e(TAG, "Failed to load wallet $walletId during password change - aborting")
                return false
            }
        }

        // PHASE 2: Generate new credentials
        val newSalt = sessionKeyManager.generateSalt()
        val newHash = derivePasswordHash(newPassword, newSalt)
        val newPasswordHash = PasswordHash(newHash, newSalt)

        // PHASE 3: Pre-encrypt all secrets with NEW key (in memory only)
        // This validates we can encrypt everything before committing
        val newEncryptedSecrets = mutableMapOf<String, String>()

        // Temporarily switch to new session for encryption
        sessionKeyManager.initializeSession(newPassword, newSalt)

        try {
            for ((walletId, secrets) in secretsMap) {
                val plaintext = secrets.toJson().toByteArray()
                val encrypted = sessionKeyManager.encrypt(plaintext)
                val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
                newEncryptedSecrets[walletId] = encoded
            }
        } catch (e: Exception) {
            // Encryption failed - restore old session and abort
            Log.e(TAG, "Failed to pre-encrypt secrets: ${e.message}")
            val oldHashStr = prefs.getString(passwordKey, null)
            if (oldHashStr != null) {
                val oldHash = PasswordHash.fromStorageString(oldHashStr)
                sessionKeyManager.initializeSession(oldPassword, oldHash.salt)
            }
            return false
        }

        // PHASE 4: Atomic write - all or nothing
        // SharedPreferences.Editor.commit() is atomic for a single edit block
        val success = prefs.edit()
            .putString(passwordKey, newPasswordHash.toStorageString())
            .apply {
                newEncryptedSecrets.forEach { (walletId, encrypted) ->
                    putString("wallet_secrets_$walletId", encrypted)
                }
            }
            .commit()  // commit() returns boolean and is synchronous

        if (success) {
            Log.d(TAG, "${if (isDecoy) "Decoy" else "Main"} password changed successfully (${secretsMap.size} wallets re-encrypted)")
        } else {
            Log.e(TAG, "SharedPreferences commit failed during password change")
            // Restore old session since commit failed
            val oldHashStr = prefs.getString(passwordKey, null)
            if (oldHashStr != null) {
                val oldHash = PasswordHash.fromStorageString(oldHashStr)
                sessionKeyManager.initializeSession(oldPassword, oldHash.salt)
            }
        }

        return success
    }

    // ============================================================================
    // WALLET METADATA (Plain JSON in EncryptedSharedPreferences)
    // ============================================================================

    /**
     * Saves wallet metadata.
     * Stored as plain JSON - already protected by EncryptedSharedPreferences.
     */
    fun saveWalletMetadata(metadata: WalletMetadata, isDecoy: Boolean): Boolean {
        return try {
            val prefs = if (isDecoy) decoyPrefs else mainPrefs
            prefs.edit { putString("wallet_meta_${metadata.id}", metadata.toJson()) }
            updateWalletIndex(metadata.id, isDecoy)
            Log.d(TAG, "Saved metadata for wallet: ${metadata.id}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata: ${e.message}", e)
            false
        }
    }

    /**
     * Loads wallet metadata by ID.
     * Automatically migrates old format to new format and persists the change.
     */
    fun loadWalletMetadata(walletId: String, isDecoy: Boolean): WalletMetadata? {
        return try {
            val prefs = if (isDecoy) decoyPrefs else mainPrefs
            val json = prefs.getString("wallet_meta_$walletId", null) ?: return null
            val metadata = WalletMetadata.fromJson(json)
            
            // Auto-migrate: if old format (has savePassphraseLocally), re-save in new format
            if (json.contains("\"savePassphraseLocally\"")) {
                saveWalletMetadata(metadata, isDecoy)
                Log.d(TAG, "Migrated wallet metadata $walletId to new format")
            }
            
            metadata
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load metadata: ${e.message}", e)
            null
        }
    }

    /**
     * Loads all wallet metadata, respecting saved order.
     */
    fun loadAllWalletMetadata(isDecoy: Boolean): List<WalletMetadata> {
        val prefs = if (isDecoy) decoyPrefs else mainPrefs
        val walletIds = getWalletIds(isDecoy)

        val metadataMap = walletIds.mapNotNull { id ->
            try {
                val json = prefs.getString("wallet_meta_$id", null) ?: return@mapNotNull null
                id to WalletMetadata.fromJson(json)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load metadata for $id: ${e.message}")
                null
            }
        }.toMap()

        // Respect saved order
        val savedOrder = loadWalletOrder(isDecoy)
        return if (savedOrder != null) {
            val ordered = savedOrder.mapNotNull { metadataMap[it] }.toMutableList()
            // Add any not in order
            metadataMap.values.filter { it !in ordered }.forEach { ordered.add(it) }
            ordered
        } else {
            metadataMap.values.sortedBy { it.createdAt }
        }
    }

    /**
     * Updates wallet metadata (for rename operations).
     */
    fun updateWalletMetadata(metadata: WalletMetadata, isDecoy: Boolean): Boolean {
        return saveWalletMetadata(metadata, isDecoy)
    }

    // ============================================================================
    // WALLET SECRETS (Encrypted with Session Key)
    // ============================================================================

    /**
     * Saves wallet secrets encrypted with the session key.
     */
    fun saveWalletSecrets(walletId: String, secrets: WalletSecrets, isDecoy: Boolean): Boolean {
        check(sessionKeyManager.isSessionActive.value) { "Session not active" }

        return try {
            val prefs = if (isDecoy) decoyPrefs else mainPrefs
            val plaintext = secrets.toJson().toByteArray()
            val encrypted = sessionKeyManager.encrypt(plaintext)
            val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            prefs.edit { putString("wallet_secrets_$walletId", encoded) }
            Log.d(TAG, "Saved secrets for wallet: $walletId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save secrets: ${e.message}", e)
            false
        }
    }

    /**
     * Loads wallet secrets, decrypting with the session key.
     * Automatically migrates old format (passphrase) to new format (bip39Seed) and persists.
     */
    fun loadWalletSecrets(walletId: String, isDecoy: Boolean): WalletSecrets? {
        check(sessionKeyManager.isSessionActive.value) { "Session not active" }

        return try {
            val prefs = if (isDecoy) decoyPrefs else mainPrefs
            val encoded = prefs.getString("wallet_secrets_$walletId", null) ?: return null
            val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
            val decrypted = sessionKeyManager.decrypt(encrypted)
            val json = String(decrypted)
            val secrets = WalletSecrets.fromJson(json)
            
            // Auto-migrate: if old format (has passphrase, no bip39Seed), re-save in new format
            val isOldFormat = json.contains("\"passphrase\"") && !json.contains("\"bip39Seed\"")
            if (isOldFormat) {
                saveWalletSecrets(walletId, secrets, isDecoy)
                Log.d(TAG, "Migrated wallet secrets $walletId to new format (bip39Seed)")
            }
            
            secrets
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load secrets: ${e.message}", e)
            null
        }
    }

    // ============================================================================
    // WALLET INDEX & ORDER
    // ============================================================================

    private fun getWalletIds(isDecoy: Boolean): Set<String> {
        val prefs = if (isDecoy) decoyPrefs else mainPrefs
        return prefs.getStringSet(KEY_WALLET_IDS, emptySet()) ?: emptySet()
    }

    fun getWalletCount(isDecoy: Boolean): Int = getWalletIds(isDecoy).size

    private fun updateWalletIndex(walletId: String, isDecoy: Boolean) {
        val prefs = if (isDecoy) decoyPrefs else mainPrefs
        val currentIds = getWalletIds(isDecoy).toMutableSet()
        currentIds.add(walletId)
        prefs.edit { putStringSet(KEY_WALLET_IDS, currentIds) }
    }

    fun saveWalletOrder(orderedIds: List<String>, isDecoy: Boolean): Boolean {
        return try {
            val prefs = if (isDecoy) decoyPrefs else mainPrefs
            prefs.edit { putString(KEY_WALLET_ORDER, JSONArray(orderedIds).toString()) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save wallet order: ${e.message}", e)
            false
        }
    }

    fun loadWalletOrder(isDecoy: Boolean): List<String>? {
        return try {
            val prefs = if (isDecoy) decoyPrefs else mainPrefs
            val json = prefs.getString(KEY_WALLET_ORDER, null) ?: return null
            val array = JSONArray(json)
            (0 until array.length()).map { array.getString(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load wallet order: ${e.message}", e)
            null
        }
    }

    // ============================================================================
    // WALLET DELETION
    // ============================================================================

    /**
     * Deletes a wallet (metadata, secrets, and index entry).
     */
    fun deleteWallet(walletId: String, isDecoy: Boolean): Boolean {
        return try {
            val prefs = if (isDecoy) decoyPrefs else mainPrefs

            prefs.edit {
                remove("wallet_meta_$walletId")
                remove("wallet_secrets_$walletId")
            }

            // Update index
            val currentIds = getWalletIds(isDecoy).toMutableSet()
            currentIds.remove(walletId)
            prefs.edit { putStringSet(KEY_WALLET_IDS, currentIds) }

            // Update order
            val currentOrder = loadWalletOrder(isDecoy)?.toMutableList()
            if (currentOrder != null) {
                currentOrder.remove(walletId)
                saveWalletOrder(currentOrder, isDecoy)
            }

            Log.d(TAG, "Deleted wallet: $walletId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete wallet: ${e.message}", e)
            false
        }
    }

    // ============================================================================
    // SESSION MANAGEMENT
    // ============================================================================

    /**
     * Clears the session key cache. Call on logout.
     */
    fun clearSession() {
        sessionKeyManager.clearSession()
    }

    // ============================================================================
    // DATA WIPE (Security Feature)
    // ============================================================================

    /**
     * Gets the current number of failed login attempts.
     */
    fun getFailedAttemptCount(): Int {
        return loginAttemptManager.getFailedAttemptCount()
    }

    /**
     * Completely wipes all app data - both main and decoy wallets, passwords, and preferences.
     * Called when the "wipe on failed login" security feature is triggered.
     *
     * WARNING: This is irreversible! All wallet data will be permanently deleted.
     * 
     * Clears:
     * - Main vault data (wallets, password hash)
     * - Decoy vault data (wallets, password hash)
     * - Biometric password storage
     * - User preferences (including biometric settings)
     * - Login attempts
     * - Session keys
     */
    fun wipeAllData() {
        Log.w(TAG, "SECURITY WIPE: Wiping all application data due to failed login attempts")
        
        try {
            // Clear all main vault data
            mainPrefs.edit { clear() }
            Log.d(TAG, "Main vault data wiped")
            
            // Clear all decoy vault data
            decoyPrefs.edit { clear() }
            Log.d(TAG, "Decoy vault data wiped")
            
            // Clear biometric password storage
            try {
                val biometricPrefs = context.getSharedPreferences("biometric_prefs", android.content.Context.MODE_PRIVATE)
                biometricPrefs.edit { clear() }
                Log.d(TAG, "Biometric passwords wiped")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing biometric prefs: ${e.message}")
            }
            
            // Clear user preferences (biometric enabled state, etc.)
            try {
                // UserPreferencesRepository uses EncryptedSharedPreferences with "metrovault_settings"
                val userPrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                    context,
                    "metrovault_settings",
                    masterKey,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                userPrefs.edit { clear() }
                Log.d(TAG, "User preferences wiped")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing user prefs: ${e.message}")
            }
            
            // Reset login attempts
            loginAttemptManager.resetAttempts()
            Log.d(TAG, "Login attempts reset")
            
            // Clear session
            sessionKeyManager.clearSession()
            Log.d(TAG, "Session cleared")
            
            Log.w(TAG, "SECURITY WIPE: Complete - all data has been permanently deleted")
        } catch (e: Exception) {
            Log.e(TAG, "Error during security wipe: ${e.message}", e)
            // Even if there's an error, try to clear as much as possible
            try {
                mainPrefs.edit { clear() }
                decoyPrefs.edit { clear() }
                context.getSharedPreferences("biometric_prefs", android.content.Context.MODE_PRIVATE).edit { clear() }
            } catch (_: Exception) { }
        }
    }
}
