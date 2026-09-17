@file:Suppress("DEPRECATION")

package com.gorunjinian.metrovault.core.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.compose.runtime.Stable
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import androidx.core.content.edit
import com.gorunjinian.metrovault.core.crypto.BiometricPasswordManager
import com.gorunjinian.metrovault.core.crypto.KeyDerivation
import com.gorunjinian.metrovault.core.crypto.LoginAttemptManager
import com.gorunjinian.metrovault.core.crypto.SessionKeyManager
import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.data.model.WalletKeys
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.data.model.WalletSecrets
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import org.json.JSONArray
import java.io.File

/**
 * Secure password record with per-user salt.
 * Stored format: salt:hash:iterations[:version] (Base64 encoded)
 *
 * Version semantics for [hash]:
 * - [VERSION_LEGACY] (3-part records): hash is the raw PBKDF2 master key.
 *   Insecure at rest — the same bytes derive the wallet encryption key — so
 *   legacy records are upgraded in place on the first successful verification.
 * - [VERSION_VERIFIER]: hash = HKDF(masterKey, "password-verification"), which
 *   is one-way with respect to the master key and safe to store.
 */
data class PasswordHash(
    val hash: ByteArray,
    val salt: ByteArray,
    val iterations: Int,
    val version: Int = VERSION_VERIFIER
) {
    fun toStorageString(): String {
        val saltB64 = java.util.Base64.getEncoder().encodeToString(salt)
        val hashB64 = java.util.Base64.getEncoder().encodeToString(hash)
        return "$saltB64:$hashB64:$iterations:$version"
    }

    companion object {
        const val VERSION_LEGACY = 1
        const val VERSION_VERIFIER = 2

        fun fromStorageString(str: String): PasswordHash {
            val parts = str.split(":")
            require(parts.size == 3 || parts.size == 4) { "Invalid password hash format" }
            return PasswordHash(
                salt = java.util.Base64.getDecoder().decode(parts[0]),
                hash = java.util.Base64.getDecoder().decode(parts[1]),
                iterations = parts[2].toInt(),
                version = if (parts.size == 4) parts[3].toInt() else VERSION_LEGACY
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PasswordHash) return false
        return hash.contentEquals(other.hash) &&
                salt.contentEquals(other.salt) &&
                iterations == other.iterations &&
                version == other.version
    }

    override fun hashCode(): Int {
        var result = hash.contentHashCode()
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + iterations
        result = 31 * result + version
        return result
    }
}

/**
 * Result of a login-password verification attempt.
 *
 * @property lockedOut true when the failure was caused by rate limiting, not a
 *   wrong password — callers must not treat this as stale credentials.
 * @property isDuress true when the entered password is the duress password.
 *   [success] is false and no session was opened; the caller runs
 *   [SecureStorage.enterDuressSession], which wipes all data and opens the
 *   fake session that stands in for a normal unlock.
 */
data class PasswordVerification(
    val success: Boolean,
    val isDecoy: Boolean = false,
    val errorMessage: String? = null,
    val lockedOut: Boolean = false,
    val isDuress: Boolean = false
)

/**
 * Simplified secure storage for wallet data.
 *
 * Architecture:
 * - Password verification: PBKDF2 (at login only, iteration count stored per
 *   record) + one-way HKDF verifier stored on disk — the stored verifier
 *   cannot be used to derive the wallet encryption key
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
        // Lives in the main vault file: it is a verifier only, nothing is
        // encrypted under it, and it is wiped together with everything else.
        private const val KEY_DURESS_PASSWORD_HASH = "duress_password_hash"

        // Shared rejection text for any password that collides with one of the
        // other passwords. Deliberately neutral: in decoy mode it must not
        // reveal that a main vault or a duress password exists.
        private const val PASSWORD_UNAVAILABLE = "This password is unavailable — choose a different one"

        // EncryptedSharedPreferences keeps its Tink keysets in the same file
        // under keys with this prefix; they are present on a fresh install and
        // survive clear(), so they never count as user data.
        private const val ESP_RESERVED_KEY_PREFIX = "__androidx_security_crypto_encrypted_prefs"
        private const val KEY_WALLET_IDS = "wallet_ids"
        private const val KEY_WALLET_ORDER = "wallet_order"
        private const val KEY_KEY_IDS = "wallet_key_ids"       // Index of all WalletKeys IDs
        private const val KEY_MIGRATION_VERSION = "migration_version"
        private const val CURRENT_MIGRATION_VERSION = 2        // v2 = WalletKeys refactoring

        // Iteration count for NEWLY created password records (OWASP figure for
        // PBKDF2-HMAC-SHA256). Existing records keep their stored count until
        // the next password change (which re-encrypts everything anyway).
        private const val PBKDF2_ITERATIONS = 600_000

        // Storage prefixes for blobs encrypted with the session key
        private const val PREFIX_WALLET_KEY = "wallet_key_"
        private const val PREFIX_WALLET_SECRETS = "wallet_secrets_"
    }

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val persistentMainPrefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val persistentDecoyPrefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            DECOY_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // Every vault read and write goes through these. During a duress session
    // they resolve to RAM-only stores so the fake session never touches disk;
    // otherwise to the encrypted files. Only the wipe and the startup safety
    // net address the persistent files directly.
    private val mainPrefs: SharedPreferences
        get() = if (DuressSession.isActive) DuressSession.mainPrefs else persistentMainPrefs

    private val decoyPrefs: SharedPreferences
        get() = if (DuressSession.isActive) DuressSession.decoyPrefs else persistentDecoyPrefs

    // ============================================================================
    // PASSWORD MANAGEMENT
    // ============================================================================

    /**
     * Creates a new verifier-format password record for [password].
     */
    private fun createPasswordRecord(password: String): Pair<PasswordHash, ByteArray> {
        val salt = sessionKeyManager.generateSalt()
        val masterKey = KeyDerivation.pbkdf2(password, salt, PBKDF2_ITERATIONS)
        val record = PasswordHash(KeyDerivation.deriveVerifier(masterKey), salt, PBKDF2_ITERATIONS)
        return record to masterKey
    }

    /**
     * Sets the main password. Called on first launch.
     */
    fun setMainPassword(password: String): Boolean {
        if (hasMainPassword()) return false
        return try {
            val (record, masterKey) = createPasswordRecord(password)
            masterKey.fill(0)
            mainPrefs.edit { putString(KEY_PASSWORD_HASH, record.toStorageString()) }
            AppLog.d(TAG) { "Password set" }
            true
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to set password: ${e.message}" }
            false
        }
    }

    /**
     * Sets the decoy password. It must differ from the main and duress passwords.
     *
     * @return Pair<success, errorMessage>
     */
    fun setDecoyPassword(password: String): Pair<Boolean, String?> {
        if (hasDecoyPassword()) return false to "A decoy password is already set"

        if (verifyMainPassword(password) || verifyDuressPassword(password)) {
            AppLog.w(TAG) { "Password cannot match existing password" }
            return false to PASSWORD_UNAVAILABLE
        }

        return try {
            val (record, masterKey) = createPasswordRecord(password)
            masterKey.fill(0)
            decoyPrefs.edit { putString(KEY_DECOY_PASSWORD_HASH, record.toStorageString()) }
            AppLog.d(TAG) { "Password set" }
            true to null
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to set password: ${e.message}" }
            false to "Failed to save decoy password"
        }
    }

    /**
     * Sets or replaces the duress password — the password that, when entered on
     * the unlock screen, destroys all app data (see [wipeAllData]).
     *
     * It must differ from both vault passwords: a collision would make the
     * login ambiguous and could wipe the device on a legitimate unlock. There
     * is no old-password step because nothing is encrypted under it; the
     * caller is already authenticated in the main vault.
     *
     * @return Pair<success, errorMessage>
     */
    fun setDuressPassword(password: String): Pair<Boolean, String?> {
        if (classifyPassword(password) != PasswordOwner.NONE) {
            AppLog.w(TAG) { "Duress password cannot match a vault password" }
            return false to PASSWORD_UNAVAILABLE
        }

        return try {
            val (record, masterKey) = createPasswordRecord(password)
            masterKey.fill(0)
            mainPrefs.edit { putString(KEY_DURESS_PASSWORD_HASH, record.toStorageString()) }
            AppLog.d(TAG) { "Duress password set" }
            true to null
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to set duress password: ${e.message}" }
            false to "Failed to save duress password"
        }
    }

    /**
     * Removes the duress password. Entering it afterwards is just a wrong password.
     */
    fun removeDuressPassword(): Boolean {
        return try {
            mainPrefs.edit { remove(KEY_DURESS_PASSWORD_HASH) }
            AppLog.d(TAG) { "Duress password removed" }
            true
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to remove duress password: ${e.message}" }
            false
        }
    }

    fun hasMainPassword(): Boolean = mainPrefs.contains(KEY_PASSWORD_HASH)

    fun hasDecoyPassword(): Boolean = decoyPrefs.contains(KEY_DECOY_PASSWORD_HASH)

    fun hasDuressPassword(): Boolean = mainPrefs.contains(KEY_DURESS_PASSWORD_HASH)

    /**
     * Verifies [password] against the stored record at [key], honoring the
     * record's stored iteration count and format version.
     *
     * On success returns the PBKDF2 master key (caller MUST wipe it) and
     * silently upgrades legacy records — which stored the raw master key on
     * disk — to the one-way verifier format. Returns null on mismatch.
     */
    private fun verifyStoredPassword(
        prefs: SharedPreferences,
        key: String,
        password: String
    ): ByteArray? {
        val stored = try {
            PasswordHash.fromStorageString(prefs.getString(key, null) ?: return null)
        } catch (e: Exception) {
            AppLog.e(TAG) { "Invalid stored password record: ${e.message}" }
            return null
        }

        val masterKey = KeyDerivation.pbkdf2(password, stored.salt, stored.iterations)
        val matches = if (stored.version >= PasswordHash.VERSION_VERIFIER) {
            MessageDigest.isEqual(KeyDerivation.deriveVerifier(masterKey), stored.hash)
        } else {
            MessageDigest.isEqual(masterKey, stored.hash)
        }

        if (!matches) {
            masterKey.fill(0)
            return null
        }

        if (stored.version < PasswordHash.VERSION_VERIFIER) {
            // Upgrade in place: stop persisting raw master-key material on disk.
            // The master key (and thus the wallet encryption key) is unchanged,
            // so no data re-encryption is needed.
            val upgraded = PasswordHash(
                KeyDerivation.deriveVerifier(masterKey), stored.salt, stored.iterations
            )
            prefs.edit { putString(key, upgraded.toStorageString()) }
            AppLog.d(TAG) { "Upgraded password record to verifier format" }
        }

        return masterKey
    }

    /**
     * Verifies password with rate limiting.
     * On success, initializes the session key for subsequent operations.
     *
     * The duress password is recognised only here — the unlock path — and is
     * reported via [PasswordVerification.isDuress] rather than acted on, so
     * the caller controls the wipe. It is checked even while rate-limit locked
     * out: a user under coercion must always be able to trigger it.
     *
     * @param recordFailure false for machine-originated attempts (e.g. a
     *   biometric-stored password) so they don't count toward lockout/wipe
     * @param allowDuress false for machine-originated attempts: only a human
     *   typing the duress password may wipe the device
     */
    fun verifyPassword(
        password: String,
        recordFailure: Boolean = true,
        allowDuress: Boolean = true
    ): PasswordVerification {
        // Check rate limiting first
        if (loginAttemptManager.isLockedOut()) {
            if (allowDuress && verifyDuressPassword(password)) {
                return PasswordVerification(success = false, isDuress = true)
            }
            val remaining = loginAttemptManager.getRemainingLockoutTime()
            return PasswordVerification(
                success = false,
                errorMessage = "Too many failed attempts. Try again in ${loginAttemptManager.formatRemainingTime(remaining)}",
                lockedOut = true
            )
        }

        // Try main password, then decoy
        for (isDecoy in listOf(false, true)) {
            val prefs = if (isDecoy) decoyPrefs else mainPrefs
            val key = if (isDecoy) KEY_DECOY_PASSWORD_HASH else KEY_PASSWORD_HASH
            val masterKey = verifyStoredPassword(prefs, key, password) ?: continue
            try {
                sessionKeyManager.initializeSessionWithMasterKey(masterKey)
                loginAttemptManager.resetAttempts()
                AppLog.d(TAG) { "Password verified, session initialized" }
                return PasswordVerification(success = true, isDecoy = isDecoy)
            } finally {
                masterKey.fill(0)
            }
        }

        // Neither vault matched — last, the duress password. Checked after the
        // vaults so a correct login never pays for the extra derivation.
        if (allowDuress && verifyDuressPassword(password)) {
            return PasswordVerification(success = false, isDuress = true)
        }

        // Failed
        if (!recordFailure) {
            return PasswordVerification(success = false, errorMessage = "Incorrect password")
        }
        val lockoutUntil = loginAttemptManager.recordFailedAttempt()
        val message = if (lockoutUntil > System.currentTimeMillis()) {
            "Incorrect password. Locked out for ${loginAttemptManager.formatRemainingTime(lockoutUntil - System.currentTimeMillis())}"
        } else {
            "Incorrect password"
        }
        return PasswordVerification(success = false, errorMessage = message)
    }

    /**
     * Simple password verification without rate limiting (for internal checks)
     */
    fun verifyPasswordSimple(password: String): Boolean {
        if (DuressSession.acceptsAnyPassword) return password.isNotEmpty()
        return verifyMainPassword(password) || verifyDecoyPassword(password)
    }

    private fun verifyMainPassword(password: String): Boolean =
        verifyStoredPassword(mainPrefs, KEY_PASSWORD_HASH, password)
            ?.also { it.fill(0) } != null

    private fun verifyDecoyPassword(password: String): Boolean =
        verifyStoredPassword(decoyPrefs, KEY_DECOY_PASSWORD_HASH, password)
            ?.also { it.fill(0) } != null

    private fun verifyDuressPassword(password: String): Boolean =
        verifyStoredPassword(mainPrefs, KEY_DURESS_PASSWORD_HASH, password)
            ?.also { it.fill(0) } != null

    /**
     * Checks if the given password is the decoy password
     */
    fun isDecoyPassword(password: String): Boolean {
        if (DuressSession.acceptsAnyPassword) return password.isNotEmpty()
        return verifyDecoyPassword(password)
    }

    /** Which vault a password belongs to. See [classifyPassword]. */
    enum class PasswordOwner { MAIN, DECOY, NONE }

    /**
     * Identifies which vault a password belongs to in a single pass — at most two PBKDF2
     * derivations, without rate limiting. Password-confirmation dialogs should use this
     * instead of `verifyPasswordSimple(p) && !isDecoyPassword(p)`, which derives the decoy
     * hash twice (up to three PBKDF2 runs at 600k iterations each).
     *
     * The duress password is never a vault owner: every prompt other than the
     * unlock screen treats it as an incorrect password.
     *
     * Inside a duress session the "decoy" record is whatever [DuressSession]
     * holds in RAM (the duress password's verifier, or nothing, in which case
     * any non-empty password passes) so the fake session can answer prompts.
     */
    fun classifyPassword(password: String): PasswordOwner = when {
        DuressSession.acceptsAnyPassword ->
            if (password.isNotEmpty()) PasswordOwner.DECOY else PasswordOwner.NONE
        verifyMainPassword(password) -> PasswordOwner.MAIN
        verifyDecoyPassword(password) -> PasswordOwner.DECOY
        else -> PasswordOwner.NONE
    }

    // ============================================================================
    // PASSWORD CHANGE (Atomic with crash recovery)
    // ============================================================================

    /**
     * Changes the main password with atomic transaction safety.
     *
     * @return Pair<success, errorMessage>
     */
    fun changeMainPassword(oldPassword: String, newPassword: String): Pair<Boolean, String?> {
        if (DuressSession.isActive) return changePasswordInDuressSession(oldPassword, newPassword)
        if (!verifyMainPassword(oldPassword)) return false to "Current password is incorrect"
        if (isDecoyPassword(oldPassword)) return false to "Current password is incorrect"
        // The passwords must never collide: a shared vault password would make
        // login ambiguous and half-open both vaults, and a vault password equal
        // to the duress password could wipe the device on a legitimate unlock.
        if (verifyDecoyPassword(newPassword) || verifyDuressPassword(newPassword)) {
            return false to PASSWORD_UNAVAILABLE
        }

        return try {
            if (changePasswordInternal(oldPassword, newPassword, isDecoy = false)) {
                true to null
            } else {
                false to "Password change failed — no data was modified"
            }
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to change password: ${e.message}" }
            false to "Password change failed — no data was modified"
        }
    }

    /**
     * Changes the decoy password with atomic transaction safety.
     *
     * @return Pair<success, errorMessage>
     */
    fun changeDecoyPassword(oldPassword: String, newPassword: String): Pair<Boolean, String?> {
        if (DuressSession.isActive) return changePasswordInDuressSession(oldPassword, newPassword)
        if (!verifyDecoyPassword(oldPassword)) return false to "Current password is incorrect"
        // Deliberately the same neutral message as the main-side check: in
        // decoy mode this dialog is just "Change Password" and the error must
        // not reveal that another vault or a duress password exists.
        if (verifyMainPassword(newPassword) || verifyDuressPassword(newPassword)) {
            return false to PASSWORD_UNAVAILABLE
        }

        return try {
            if (changePasswordInternal(oldPassword, newPassword, isDecoy = true)) {
                true to null
            } else {
                false to "Password change failed — no data was modified"
            }
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to change password: ${e.message}" }
            false to "Password change failed — no data was modified"
        }
    }

    /**
     * Password change inside a duress session. Nothing on disk is involved and
     * the in-memory wallet blobs stay under the random session key, so only
     * the RAM verifier is replaced; from then on prompts accept the new
     * password only. The response mirrors a real change so the fake session
     * behaves normally.
     */
    private fun changePasswordInDuressSession(oldPassword: String, newPassword: String): Pair<Boolean, String?> {
        if (!DuressSession.acceptsAnyPassword && !verifyDecoyPassword(oldPassword)) {
            return false to "Current password is incorrect"
        }
        return try {
            val (record, masterKey) = createPasswordRecord(newPassword)
            masterKey.fill(0)
            DuressSession.decoyPrefs.edit { putString(KEY_DECOY_PASSWORD_HASH, record.toStorageString()) }
            DuressSession.passwordRecordStored()
            true to null
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to change duress-session password: ${e.message}" }
            false to "Password change failed — no data was modified"
        }
    }

    /**
     * Internal implementation for atomic password change.
     *
     * Fully self-contained: the old vault key is re-derived from
     * [oldPassword] + the vault's stored salt rather than taken from the
     * ambient session. This makes the operation correct regardless of which
     * vault the current session belongs to (e.g. changing the decoy password
     * while logged into the main vault).
     *
     * Strategy:
     * 1. Derive old master key from oldPassword + stored record; verify it
     * 2. Decrypt every encrypted blob in this vault with the old key
     * 3. Re-encrypt everything with the new key (in memory)
     * 4. Write new password record + re-encrypted blobs in ONE atomic commit
     * 5. Roll the global session to the new key only if the active session
     *    belongs to the vault being changed
     *
     * If the app crashes before step 4 commits, all data remains in the old
     * format and the old password still works.
     */
    private fun changePasswordInternal(oldPassword: String, newPassword: String, isDecoy: Boolean): Boolean {
        val prefs = if (isDecoy) decoyPrefs else mainPrefs
        val passwordKey = if (isDecoy) KEY_DECOY_PASSWORD_HASH else KEY_PASSWORD_HASH

        val storedStr = prefs.getString(passwordKey, null) ?: return false
        val stored = PasswordHash.fromStorageString(storedStr)

        var oldMasterKey: ByteArray? = null
        var oldEncKey: ByteArray? = null
        var newMasterKey: ByteArray? = null
        var newEncKey: ByteArray? = null
        try {
            // PHASE 1: Derive and verify the old vault key (independent of session)
            oldMasterKey = KeyDerivation.pbkdf2(oldPassword, stored.salt, stored.iterations)
            val expected = if (stored.version >= PasswordHash.VERSION_VERIFIER) {
                KeyDerivation.deriveVerifier(oldMasterKey)
            } else {
                oldMasterKey
            }
            if (!MessageDigest.isEqual(expected, stored.hash)) return false
            oldEncKey = KeyDerivation.deriveWalletEncryptionKey(oldMasterKey)

            // PHASE 2: Generate new credentials
            val newSalt = sessionKeyManager.generateSalt()
            newMasterKey = KeyDerivation.pbkdf2(newPassword, newSalt, PBKDF2_ITERATIONS)
            newEncKey = KeyDerivation.deriveWalletEncryptionKey(newMasterKey)
            val newRecord = PasswordHash(
                KeyDerivation.deriveVerifier(newMasterKey), newSalt, PBKDF2_ITERATIONS
            )

            // PHASE 3: Decrypt every encrypted blob in this vault with the old
            // key and re-encrypt with the new key (in memory only). This covers
            // WalletKeys and any legacy WalletSecrets left by old versions.
            val reEncrypted = mutableMapOf<String, String>()
            var totalBlobs = 0
            var unreadableBlobs = 0
            for ((prefKey, value) in prefs.all) {
                if (value !is String) continue  // also skips the "wallet_key_ids" StringSet index
                if (prefKey == KEY_KEY_IDS) continue
                val isKeyBlob = prefKey.startsWith(PREFIX_WALLET_KEY)
                val isSecretsBlob = prefKey.startsWith(PREFIX_WALLET_SECRETS)
                if (!isKeyBlob && !isSecretsBlob) continue

                totalBlobs++
                val plaintext = try {
                    KeyDerivation.decryptAesGcm(Base64.decode(value, Base64.NO_WRAP), oldEncKey)
                } catch (_: Exception) {
                    null
                }
                if (plaintext == null) {
                    // The old key is provably correct (verified above), so this
                    // blob was already unreadable before the change. Preserve it
                    // unchanged rather than blocking the password change.
                    unreadableBlobs++
                    AppLog.w(TAG) { "Blob undecryptable with verified old key - preserving as-is" }
                    continue
                }
                try {
                    reEncrypted[prefKey] = Base64.encodeToString(
                        KeyDerivation.encryptAesGcm(plaintext, newEncKey), Base64.NO_WRAP
                    )
                } finally {
                    plaintext.fill(0)
                }
            }

            // Safety net: if NOTHING decrypted while blobs exist, something is
            // systematically wrong - abort rather than orphan an entire vault.
            if (totalBlobs > 0 && reEncrypted.isEmpty()) {
                AppLog.e(TAG) { "All $totalBlobs blobs failed to decrypt - aborting password change" }
                return false
            }

            // PHASE 4: Atomic write - all or nothing
            val editor = prefs.edit().putString(passwordKey, newRecord.toStorageString())
            reEncrypted.forEach { (prefKey, encrypted) -> editor.putString(prefKey, encrypted) }
            if (!editor.commit()) {
                AppLog.e(TAG) { "SharedPreferences commit failed during password change" }
                return false
            }

            // PHASE 5: Roll the active session only if it belongs to this vault.
            // (Changing the decoy password from a main session must NOT touch
            // the main session key, and vice versa.)
            if (sessionKeyManager.isCurrentEncryptionKey(oldEncKey)) {
                sessionKeyManager.initializeSessionWithMasterKey(newMasterKey)
            }

            AppLog.d(TAG) {
                "Password changed (${reEncrypted.size} blobs re-encrypted" +
                        (if (unreadableBlobs > 0) ", $unreadableBlobs unreadable preserved" else "") + ")"
            }
            return true
        } finally {
            oldMasterKey?.fill(0)
            oldEncKey?.fill(0)
            newMasterKey?.fill(0)
            newEncKey?.fill(0)
        }
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
            AppLog.d(TAG) { "Saved wallet metadata" }
            true
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to save metadata: ${e.message}" }
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
                AppLog.d(TAG) { "Migrated a wallet record to new metadata format" }
            }

            metadata
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to load metadata: ${e.message}" }
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
                AppLog.e(TAG) { "Failed to load metadata: ${e.message}" }
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
    // WALLET SECRETS (DEPRECATED - Only for migration from old versions)
    // All new code should use WalletKeys instead.
    // These methods are kept only for the one-time migration (migrateWalletSecretsToKeys).
    // ============================================================================

    /**
     * @deprecated Use WalletKeys instead. This is only for migration support.
     */
    @Deprecated("Use WalletKeys instead - kept for migration only")
    fun saveWalletSecrets(walletId: String, secrets: WalletSecrets, isDecoy: Boolean): Boolean {
        check(sessionKeyManager.isSessionActive.value) { "Session not active" }

        var plaintext: ByteArray? = null
        return try {
            val prefs = if (isDecoy) decoyPrefs else mainPrefs
            plaintext = secrets.toJson().toByteArray()
            val encrypted = sessionKeyManager.encrypt(plaintext)
            val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            prefs.edit { putString("wallet_secrets_$walletId", encoded) }
            AppLog.d(TAG) { "Saved wallet secrets" }
            true
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to save secrets: ${e.message}" }
            false
        } finally {
            plaintext?.fill(0)  // Wipe sensitive plaintext from memory
        }
    }

    /**
     * Loads wallet secrets, decrypting with the session key.
     * Automatically migrates old format (passphrase) to new format (bip39Seed) and persists.
     *
     * @deprecated Use WalletKeys instead. This is only for migration support.
     */
    @Suppress("DEPRECATION")
    @Deprecated("Use WalletKeys instead - kept for migration only")
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
                AppLog.d(TAG) { "Migrated a wallet record to new secrets format" }
            }

            secrets
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to load secrets: ${e.message}" }
            null
        }
    }

    /**
     * Deletes wallet secrets from storage.
     * Called after successful migration to WalletKeys to remove redundant data.
     */
    private fun deleteWalletSecrets(walletId: String, isDecoy: Boolean) {
        val prefs = if (isDecoy) decoyPrefs else mainPrefs
        prefs.edit { remove("wallet_secrets_$walletId") }
        AppLog.d(TAG) { "Deleted old wallet secrets" }
    }

    // ============================================================================
    // WALLET KEYS (Centralized Key Store - Encrypted with Session Key)
    // ============================================================================

    /**
     * Saves a wallet key to the key store.
     */
    fun saveWalletKey(key: WalletKeys, isDecoy: Boolean): Boolean {
        check(sessionKeyManager.isSessionActive.value) { "Session not active" }

        var plaintext: ByteArray? = null
        return try {
            val prefs = if (isDecoy) decoyPrefs else mainPrefs
            plaintext = key.toJson().toByteArray()
            val encrypted = sessionKeyManager.encrypt(plaintext)
            val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            prefs.edit { putString("wallet_key_${key.keyId}", encoded) }
            updateKeyIndex(key.keyId, isDecoy)
            AppLog.d(TAG) { "Saved wallet key" }
            true
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to save wallet key: ${e.message}" }
            false
        } finally {
            plaintext?.fill(0)  // Wipe sensitive plaintext from memory
        }
    }

    /**
     * Loads a wallet key by ID.
     */
    fun loadWalletKey(keyId: String, isDecoy: Boolean): WalletKeys? {
        check(sessionKeyManager.isSessionActive.value) { "Session not active" }

        return try {
            val prefs = if (isDecoy) decoyPrefs else mainPrefs
            val encoded = prefs.getString("wallet_key_$keyId", null) ?: return null
            val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
            val decrypted = sessionKeyManager.decrypt(encrypted)
            val json = String(decrypted)
            WalletKeys.fromJson(json)
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to load wallet key: ${e.message}" }
            null
        }
    }

    /**
     * Loads all wallet keys.
     */
    fun loadAllWalletKeys(isDecoy: Boolean): List<WalletKeys> {
        return getKeyIds(isDecoy).mapNotNull { loadWalletKey(it, isDecoy) }
    }

    /**
     * Finds a key by fingerprint.
     * Used for matching cosigners during multisig import.
     */
    fun findKeyByFingerprint(fingerprint: String, isDecoy: Boolean): WalletKeys? {
        return loadAllWalletKeys(isDecoy).find {
            it.fingerprint.equals(fingerprint, ignoreCase = true)
        }
    }

    /**
     * Gets all wallet IDs that reference a given key ID.
     */
    fun getWalletsReferencingKey(keyId: String, isDecoy: Boolean): List<String> {
        return loadAllWalletMetadata(isDecoy)
            .filter { it.keyIds.contains(keyId) }
            .map { it.id }
    }

    /**
     * Deletes a wallet key (only if no wallets reference it).
     * @return true if deleted, false if still referenced or error
     */
    fun deleteWalletKey(keyId: String, isDecoy: Boolean): Boolean {
        val references = getWalletsReferencingKey(keyId, isDecoy)
        if (references.isNotEmpty()) {
            AppLog.w(TAG) { "Cannot delete key: still referenced by ${references.size} wallet(s)" }
            return false
        }

        return try {
            val prefs = if (isDecoy) decoyPrefs else mainPrefs
            prefs.edit { remove("wallet_key_$keyId") }

            // Update key index
            val currentIds = getKeyIds(isDecoy).toMutableSet()
            currentIds.remove(keyId)
            prefs.edit { putStringSet(KEY_KEY_IDS, currentIds) }

            AppLog.d(TAG) { "Deleted wallet key" }
            true
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to delete wallet key: ${e.message}" }
            false
        }
    }

    /**
     * Deletes a key if not referenced by any wallets.
     * Safe to call even if key is referenced - will just return false.
     */
    fun deleteKeyIfUnreferenced(keyId: String, isDecoy: Boolean): Boolean {
        return deleteWalletKey(keyId, isDecoy)
    }

    private fun getKeyIds(isDecoy: Boolean): Set<String> {
        val prefs = if (isDecoy) decoyPrefs else mainPrefs
        return prefs.getStringSet(KEY_KEY_IDS, emptySet()) ?: emptySet()
    }

    private fun updateKeyIndex(keyId: String, isDecoy: Boolean) {
        val prefs = if (isDecoy) decoyPrefs else mainPrefs
        val currentIds = getKeyIds(isDecoy).toMutableSet()
        currentIds.add(keyId)
        prefs.edit { putStringSet(KEY_KEY_IDS, currentIds) }
    }

    /**
     * Generates the next key label ("Key 1", "Key 2", etc.)
     */
    fun getNextKeyLabel(isDecoy: Boolean): String {
        val existingKeys = loadAllWalletKeys(isDecoy)
        val existingNumbers = existingKeys.mapNotNull { key ->
            val match = Regex("^Key (\\d+)$").find(key.label)
            match?.groupValues?.get(1)?.toIntOrNull()
        }.toSet()

        var nextNumber = 1
        while (nextNumber in existingNumbers) {
            nextNumber++
        }
        return "Key $nextNumber"
    }

    /**
     * Refreshes key bindings for all multisig wallets based on current local keys.
     *
     * Call this after importing a new wallet/key to ensure multisig wallets
     * discover any newly available signing keys.
     *
     * For each multisig wallet:
     * 1. Scans all local keys for fingerprint matches with cosigners
     * 2. Updates keyIds list with matching key IDs
     * 3. Updates cosigner.isLocal and cosigner.keyId flags
     * 4. Persists changes only if bindings actually changed
     *
     * @param isDecoy Whether to refresh in decoy mode
     * @return Number of multisig wallets that were updated
     */
    fun refreshMultisigKeyBindings(isDecoy: Boolean): Int {
        val allKeys = loadAllWalletKeys(isDecoy)
        val allMetadata = loadAllWalletMetadata(isDecoy)
        var updatedCount = 0

        for (metadata in allMetadata) {
            if (!metadata.isMultisig || metadata.multisigConfig == null) continue

            val config = metadata.multisigConfig
            val newKeyIds = mutableListOf<String>()

            // Map each cosigner to check for local key matches
            val updatedCosigners = config.cosigners.map { cosigner ->
                val matchingKey = allKeys.find {
                    it.fingerprint.equals(cosigner.fingerprint, ignoreCase = true)
                }
                if (matchingKey != null) {
                    newKeyIds.add(matchingKey.keyId)
                    cosigner.copy(isLocal = true, keyId = matchingKey.keyId)
                } else {
                    cosigner.copy(isLocal = false, keyId = null)
                }
            }

            // Only update if bindings changed
            if (newKeyIds.toSet() != metadata.keyIds.toSet()) {
                val updatedConfig = config.copy(
                    cosigners = updatedCosigners,
                    localKeyFingerprints = allKeys
                        .filter { key -> newKeyIds.contains(key.keyId) }
                        .map { it.fingerprint.lowercase() }
                )
                val updatedMetadata = metadata.copy(
                    keyIds = newKeyIds,
                    multisigConfig = updatedConfig
                )
                saveWalletMetadata(updatedMetadata, isDecoy)
                updatedCount++
                AppLog.d(TAG) { "Refreshed multisig wallet: ${metadata.keyIds.size} -> ${newKeyIds.size} local key(s)" }
            }
        }

        if (updatedCount > 0) {
            AppLog.d(TAG) { "Refreshed $updatedCount multisig wallet(s) with updated key bindings" }
        }
        return updatedCount
    }

    /**
     * Gets all multisig wallets that reference a given key by fingerprint.
     * This includes multisig wallets where the key matches a cosigner fingerprint,
     * regardless of whether the key was present at import time.
     *
     * @param fingerprint The key fingerprint to check
     * @param isDecoy Whether to check in decoy mode
     * @return List of wallet names that use this key as a cosigner
     */
    fun getMultisigWalletsUsingFingerprint(fingerprint: String, isDecoy: Boolean): List<String> {
        return loadAllWalletMetadata(isDecoy)
            .filter { metadata ->
                metadata.isMultisig &&
                metadata.multisigConfig?.cosigners?.any {
                    it.fingerprint.equals(fingerprint, ignoreCase = true)
                } == true
            }
            .map { it.name }
    }

    // ============================================================================
    // WALLET INDEX & ORDER
    // ============================================================================

    fun getWalletIds(isDecoy: Boolean): Set<String> {
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
            AppLog.e(TAG, e) { "Failed to save wallet order: ${e.message}" }
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
            AppLog.e(TAG, e) { "Failed to load wallet order: ${e.message}" }
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

            AppLog.d(TAG) { "Deleted wallet" }
            true
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to delete wallet: ${e.message}" }
            false
        }
    }

    // ============================================================================
    // MIGRATION (One-time, silent, after login)
    // ============================================================================

    /**
     * Runs migration if needed. Call after login to ensure data is in new format.
     * Migration is idempotent - safe to call multiple times.
     */
    fun runMigrationIfNeeded(isDecoy: Boolean): Boolean {
        val prefs = if (isDecoy) decoyPrefs else mainPrefs
        val currentVersion = prefs.getInt(KEY_MIGRATION_VERSION, 1)

        if (currentVersion >= CURRENT_MIGRATION_VERSION) {
            return true  // Already migrated
        }

        AppLog.d(TAG) { "Starting migration from v$currentVersion to v$CURRENT_MIGRATION_VERSION" }

        return try {
            // v1 -> v2: WalletSecrets to WalletKeys migration
            migrateWalletSecretsToKeys(isDecoy)

            // Update version
            prefs.edit { putInt(KEY_MIGRATION_VERSION, CURRENT_MIGRATION_VERSION) }
            AppLog.d(TAG) { "Migration complete to v$CURRENT_MIGRATION_VERSION" }
            true
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Migration failed: ${e.message}" }
            false
        }
    }

    /**
     * Migrates WalletSecrets to WalletKeys format.
     *
     * SAFETY GUARANTEES:
     * - WalletSecrets is NEVER deleted until WalletKeys is verified readable
     * - Each step is verified before proceeding
     * - Partial migrations are cleaned up on subsequent runs
     * - Idempotent: safe to run multiple times
     *
     * For each wallet with WalletSecrets:
     * 1. Check if a WalletKey with same fingerprint already exists (deduplication)
     * 2. If not, create a new WalletKey with auto-generated label
     * 3. VERIFY WalletKeys can be loaded back (critical safety check)
     * 4. Update WalletMetadata with keyIds reference
     * 5. VERIFY metadata has keyIds
     * 6. Only then delete old WalletSecrets
     */
    @Suppress("DEPRECATION")
    private fun migrateWalletSecretsToKeys(isDecoy: Boolean) {
        val prefs = if (isDecoy) decoyPrefs else mainPrefs
        val walletIds = getWalletIds(isDecoy)
        val fingerprintToKeyId = mutableMapOf<String, String>()
        var keyCounter = 1
        var migratedCount = 0
        var cleanedUpCount = 0

        AppLog.d(TAG) { "Migrating ${walletIds.size} wallets from WalletSecrets to WalletKeys" }

        for (walletId in walletIds) {
            try {
                val metadata = loadWalletMetadata(walletId, isDecoy) ?: continue

                // Skip multisig wallets (they don't have WalletSecrets)
                if (metadata.isMultisig) {
                    AppLog.d(TAG) { "Wallet is multisig, skipping secrets migration" }
                    continue
                }

                // Check if already migrated (has keyIds)
                if (metadata.keyIds.isNotEmpty()) {
                    // Already migrated - but check if old secrets still exist (from crashed migration)
                    if (prefs.contains("wallet_secrets_$walletId")) {
                        // Verify the WalletKey exists and is readable before deleting secrets
                        val keyId = metadata.keyIds.first()
                        val verifyKey = loadWalletKey(keyId, isDecoy)
                        if (verifyKey != null) {
                            deleteWalletSecrets(walletId, isDecoy)
                            cleanedUpCount++
                            AppLog.d(TAG) { "Cleaned up leftover secrets for already-migrated wallet" }
                        } else {
                            AppLog.w(TAG) { "Wallet has keyIds but key not found - keeping secrets as backup" }
                        }
                    }
                    continue
                }

                // Load old WalletSecrets
                val secrets = loadWalletSecrets(walletId, isDecoy)
                if (secrets == null) {
                    AppLog.w(TAG) { "No secrets found, skipping" }
                    continue
                }

                // Use metadata fingerprint
                val fingerprint = metadata.masterFingerprint.lowercase()

                // Check if we already have a key with this fingerprint (deduplication)
                val existingKeyId = fingerprintToKeyId[fingerprint]
                val keyId: String

                if (existingKeyId != null) {
                    // Reuse existing key - but verify it exists
                    val verifyKey = loadWalletKey(existingKeyId, isDecoy)
                    if (verifyKey == null) {
                        AppLog.e(TAG) { "Cached keyId not found, skipping wallet" }
                        continue
                    }
                    keyId = existingKeyId
                    AppLog.d(TAG) { "Reusing existing key" }
                } else {
                    // Create new WalletKey
                    keyId = java.util.UUID.randomUUID().toString()
                    val label = "Key $keyCounter"
                    keyCounter++

                    val walletKeys = WalletKeys(
                        keyId = keyId,
                        mnemonic = secrets.mnemonic,
                        bip39Seed = secrets.bip39Seed,
                        fingerprint = fingerprint,
                        label = label
                    )

                    // Step 1: Save the new WalletKey
                    if (!saveWalletKey(walletKeys, isDecoy)) {
                        AppLog.e(TAG) { "Failed to save wallet key - secrets preserved" }
                        continue
                    }

                    // Step 2: CRITICAL - Verify we can read it back before proceeding
                    val verifyKey = loadWalletKey(keyId, isDecoy)
                    if (verifyKey == null) {
                        AppLog.e(TAG) { "CRITICAL: Saved key but cannot load it back - secrets preserved" }
                        continue
                    }
                    if (verifyKey.mnemonic != secrets.mnemonic || verifyKey.bip39Seed != secrets.bip39Seed) {
                        AppLog.e(TAG) { "CRITICAL: Key data mismatch after save - secrets preserved" }
                        continue
                    }

                    fingerprintToKeyId[fingerprint] = keyId
                    AppLog.d(TAG) { "Created and verified new key" }
                }

                // Step 3: Update metadata with keyIds
                val updatedMetadata = metadata.copy(keyIds = listOf(keyId))
                if (!saveWalletMetadata(updatedMetadata, isDecoy)) {
                    AppLog.e(TAG) { "Failed to update metadata - secrets preserved" }
                    continue
                }

                // Step 4: Verify metadata was updated correctly
                val verifyMetadata = loadWalletMetadata(walletId, isDecoy)
                if (verifyMetadata?.keyIds?.firstOrNull() != keyId) {
                    AppLog.e(TAG) { "Metadata verification failed - secrets preserved" }
                    continue
                }

                // Step 5: ALL VERIFIED - Safe to delete old WalletSecrets
                deleteWalletSecrets(walletId, isDecoy)
                migratedCount++
                AppLog.d(TAG) { "Successfully migrated wallet" }

            } catch (e: Exception) {
                // Any exception = keep secrets, skip this wallet
                AppLog.e(TAG, e) { "Error migrating wallet (secrets preserved): ${e.message}" }
            }
        }

        AppLog.d(TAG) { "Migration complete: $migratedCount wallets migrated, ${fingerprintToKeyId.size} unique keys, $cleanedUpCount leftover secrets cleaned" }
    }

    /**
     * Clears the session key cache. Call on logout.
     *
     * Also ends any duress session, dropping its RAM-only stores, before the
     * session flag flips: observers reacting to the lock then see an
     * unprovisioned app and route to setup rather than unlock.
     */
    fun clearSession() {
        DuressSession.end()
        sessionKeyManager.clearSession()
    }

    // ============================================================================
    // DURESS SESSION
    // ============================================================================

    /**
     * Destroys all persistent data and opens the fake session shown after a
     * duress unlock. See [DuressSession] for what the session holds.
     *
     * The duress password's verifier (when one exists) is carried into the
     * RAM "decoy" record before the wipe so in-session prompts keep accepting
     * the password the attacker just watched being typed. The session key is
     * random: nothing on disk was ever encrypted under it and it dies with
     * the session. The caller opens the session in decoy mode and creates the
     * throwaway wallet.
     */
    fun enterDuressSession() {
        val duressRecord = persistentMainPrefs.getString(KEY_DURESS_PASSWORD_HASH, null)

        wipeAllData()

        DuressSession.start(acceptAnyPassword = duressRecord == null)
        if (duressRecord != null) {
            DuressSession.decoyPrefs.edit { putString(KEY_DECOY_PASSWORD_HASH, duressRecord) }
        }

        val sessionKey = ByteArray(32)
        SecureRandom().nextBytes(sessionKey)
        try {
            sessionKeyManager.initializeSessionWithMasterKey(sessionKey)
        } finally {
            sessionKey.fill(0)
        }
        AppLog.w(TAG) { "Duress session started" }
    }

    /**
     * Safety net, run at startup and whenever a session ends. With no main
     * password on disk nothing else on disk can be legitimate: decoy and
     * duress passwords are only ever set from a provisioned main vault.
     * Anything found is residue, either of a wipe cut short by a process kill
     * or of a duress session's disk writes (settings toggles, biometric
     * material), and is destroyed before setup can run. A genuinely fresh
     * install has no residue and is left untouched.
     *
     * @return true if a wipe ran
     */
    fun wipeResidueIfUnprovisioned(): Boolean {
        if (hasMainPassword()) return false

        val files = listOf(
            PREFS_NAME,
            DECOY_PREFS_NAME,
            UserPreferencesRepository.PREFS_NAME,
            BiometricPasswordManager.PREFS_NAME,
            LoginAttemptManager.PREFS_NAME
        )
        // Raw (undecrypted) view: the cached instance the encrypted wrapper
        // sits on, so this never creates a second keyset.
        val hasResidue = files.any { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.keys
                .any { !it.startsWith(ESP_RESERVED_KEY_PREFIX) }
        }
        if (!hasResidue) return false

        AppLog.w(TAG) { "No main password but residual data found - wiping" }
        wipeAllData()
        return true
    }

    /**
     * Gets the current number of failed login attempts.
     */
    fun getFailedAttemptCount(): Int {
        return loginAttemptManager.getFailedAttemptCount()
    }

    /**
     * Completely wipes all app data - both main and decoy wallets, passwords,
     * preferences and lockout state - and deletes the backing files so the
     * app's data directory looks like a fresh install on the next launch.
     * Triggered by the "wipe on failed login" feature and by the duress password.
     *
     * WARNING: This is irreversible! All wallet data will be permanently deleted.
     *
     * Clears:
     * - Main vault data (wallets, password hash, duress password hash)
     * - Decoy vault data (wallets, password hash)
     * - Biometric password storage and its Android Keystore keys
     * - User preferences (including biometric settings)
     * - Login attempts
     * - Session keys
     * - Every file under shared_prefs/, files/, cache/ and no_backup/
     *
     * Ordering matters: each store is first cleared through its live
     * SharedPreferences instance with a synchronous commit, so memory and disk
     * agree, and only then are the files removed. The files are deleted
     * directly rather than via Context.deleteSharedPreferences, because that
     * call evicts Android's process-wide cached instance: any SecureStorage or
     * UserPreferencesRepository created afterwards would get a second,
     * independent in-memory copy of the same file, and the two copies' writes
     * would silently clobber each other. Leaving the cached instances alive
     * keeps exactly one authoritative copy per file; a later write simply
     * recreates the file from it.
     */
    fun wipeAllData() {
        AppLog.w(TAG) { "SECURITY WIPE: Wiping all application data" }

        // Any fake session is over too; its RAM stores go with it
        DuressSession.end()

        try {
            // Clear all main vault data
            persistentMainPrefs.edit(commit = true) { clear() }
            AppLog.d(TAG) { "Main vault data wiped" }

            // Clear all decoy vault data
            persistentDecoyPrefs.edit(commit = true) { clear() }
            AppLog.d(TAG) { "Decoy vault data wiped" }

            // Clear biometric password storage
            try {
                context.getSharedPreferences(BiometricPasswordManager.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit(commit = true) { clear() }
                AppLog.d(TAG) { "Biometric passwords wiped" }
            } catch (e: Exception) {
                AppLog.e(TAG) { "Error clearing biometric prefs: ${e.message}" }
            }

            // Delete biometric keys from Android Keystore (the encrypted
            // passwords are gone, so the wrapping keys must not linger)
            try {
                val biometricKeys = BiometricPasswordManager(context)
                BiometricPasswordManager.ALL_TARGETS.forEach { biometricKeys.deleteKey(it) }
                AppLog.d(TAG) { "Biometric keystore keys deleted" }
            } catch (e: Exception) {
                AppLog.e(TAG) { "Error deleting biometric keystore keys: ${e.message}" }
            }

            // Clear user preferences (biometric enabled state, etc.). This goes
            // through the same cached SharedPreferences instance every
            // UserPreferencesRepository wraps, so their in-memory maps are
            // cleared too (their StateFlows are refreshed by the caller).
            try {
                val userPrefs = EncryptedSharedPreferences.create(
                    context,
                    UserPreferencesRepository.PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                userPrefs.edit(commit = true) { clear() }
                AppLog.d(TAG) { "User preferences wiped" }
            } catch (e: Exception) {
                AppLog.e(TAG) { "Error clearing user prefs: ${e.message}" }
            }

            // Reset login attempts
            loginAttemptManager.resetAttempts()
            AppLog.d(TAG) { "Login attempts reset" }

            // Remove the (now empty) files from disk
            deleteDataFiles()

            // Clear session
            sessionKeyManager.clearSession()
            AppLog.d(TAG) { "Session cleared" }

            AppLog.w(TAG) { "SECURITY WIPE: Complete - all data has been permanently deleted" }
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Error during security wipe: ${e.message}" }
            // Even if there's an error, try to clear as much as possible
            try {
                persistentMainPrefs.edit(commit = true) { clear() }
                persistentDecoyPrefs.edit(commit = true) { clear() }
                context.getSharedPreferences(BiometricPasswordManager.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit(commit = true) { clear() }
                deleteDataFiles()
            } catch (_: Exception) { }
            sessionKeyManager.clearSession()
        }
    }

    /**
     * Deletes everything inside the app's private data directories - every
     * shared-preferences file (including SharedPreferences' `.bak` backups)
     * plus the files, cache and no-backup directories. The directories
     * themselves are kept; Android recreates their contents on demand.
     */
    private fun deleteDataFiles() {
        val directories = listOf(
            File(context.dataDir, "shared_prefs"),
            context.filesDir,
            context.cacheDir,
            context.noBackupFilesDir
        )
        for (dir in directories) {
            val entries = try {
                dir.listFiles() ?: continue
            } catch (_: Exception) {
                continue
            }
            var deleted = 0
            for (entry in entries) {
                if (entry.deleteRecursively()) deleted++
            }
            AppLog.d(TAG) { "Deleted $deleted/${entries.size} entries under ${dir.name}" }
        }
    }
}
