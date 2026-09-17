package com.gorunjinian.metrovault.core.crypto

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64
import androidx.core.content.edit
import com.gorunjinian.metrovault.core.logging.AppLog

/**
 * Stores one password per biometric target, encrypted under a biometric-bound
 * Android Keystore key, so a fingerprint can stand in for typing it.
 *
 * Targets are [TARGET_MAIN], [TARGET_DECOY] and [TARGET_DURESS]. Only one is
 * active at a time (the user's biometric target preference). The duress slot
 * holds a random token rather than a password: the unlock screen treats a
 * successful biometric decrypt of that slot as the trigger for the duress
 * wipe, so the on-screen flow is indistinguishable from a real unlock.
 *
 * [TARGET_NONE] (and any unknown value) has no slot: queries answer false or
 * null and mutations are no-ops, so callers can pass the stored preference
 * straight through without guarding. Only [getEncryptCipher], which creates
 * keys, insists on a real target.
 */
class BiometricPasswordManager(context: Context) {

    companion object {
        private const val TAG = "BiometricPasswordManager"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION =
            "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}"

        /** Plain SharedPreferences file holding the biometric-wrapped passwords. */
        internal const val PREFS_NAME = "biometric_prefs"

        // Canonical biometric target values, persisted in user preferences.
        const val TARGET_MAIN = "main"
        const val TARGET_DECOY = "decoy"
        const val TARGET_DURESS = "duress"
        const val TARGET_NONE = "none"

        val ALL_TARGETS = listOf(TARGET_MAIN, TARGET_DECOY, TARGET_DURESS)
    }

    /** Keystore alias and preference keys backing one target. */
    private class Slot(val keyAlias: String, val passwordKey: String, val ivKey: String)

    private fun slot(target: String): Slot? = when (target) {
        TARGET_MAIN -> Slot("biometric_key_main", "encrypted_pass_main", "iv_main")
        TARGET_DECOY -> Slot("biometric_key_decoy", "encrypted_pass_decoy", "iv_decoy")
        TARGET_DURESS -> Slot("biometric_key_duress", "encrypted_pass_duress", "iv_duress")
        else -> null
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasEncodedPassword(target: String): Boolean {
        val slot = slot(target) ?: return false
        return prefs.contains(slot.passwordKey)
    }

    /**
     * Checks if the biometric key is still valid (not invalidated by new biometric enrollment).
     * Returns false if the key doesn't exist or has been permanently invalidated.
     */
    fun isKeyValid(target: String): Boolean {
        val slot = slot(target) ?: return false
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            if (!keyStore.containsAlias(slot.keyAlias)) return false

            val secretKey = keyStore.getKey(slot.keyAlias, null) as? SecretKey ?: return false
            val cipher = Cipher.getInstance(TRANSFORMATION)

            // This will throw KeyPermanentlyInvalidatedException if the key was invalidated
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            true
        } catch (_: KeyPermanentlyInvalidatedException) {
            // Key was invalidated due to new biometric enrollment
            false
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to validate biometric key: ${e.message}" }
            false
        }
    }

    /**
     * Deletes the biometric key from Android Keystore.
     * Call this when the key is permanently invalidated.
     */
    fun deleteKey(target: String) {
        val slot = slot(target) ?: return
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (keyStore.containsAlias(slot.keyAlias)) {
                keyStore.deleteEntry(slot.keyAlias)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to delete biometric key: ${e.message}" }
        }
    }

    fun getDecryptCipher(target: String): Cipher? {
        val slot = slot(target) ?: return null
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            if (!keyStore.containsAlias(slot.keyAlias)) return null

            val secretKey = keyStore.getKey(slot.keyAlias, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)

            val ivString = prefs.getString(slot.ivKey, null) ?: return null
            val iv = Base64.decode(ivString, Base64.DEFAULT)

            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            cipher
        } catch (_: KeyPermanentlyInvalidatedException) {
            // Key was invalidated due to new biometric enrollment
            null
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to create decrypt cipher: ${e.message}" }
            null
        }
    }

    fun decryptPassword(target: String, cipher: Cipher): String? {
        val slot = slot(target) ?: return null
        return try {
            val encryptedString = prefs.getString(slot.passwordKey, null) ?: return null
            val encryptedBytes = Base64.decode(encryptedString, Base64.DEFAULT)

            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes)
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to decrypt biometric password: ${e.message}" }
            null
        }
    }

    fun removeBiometricData(target: String) {
        val slot = slot(target) ?: return
        prefs.edit {
            remove(slot.passwordKey)
                .remove(slot.ivKey)
        }
    }

    /**
     * Removes the stored data and Keystore key of every target other than
     * [keep]. Only one target is ever active, so activating a new one must
     * not leave the previous target's ciphertext behind.
     */
    fun removeAllExcept(keep: String?) {
        for (target in ALL_TARGETS) {
            if (target == keep) continue
            removeBiometricData(target)
            deleteKey(target)
        }
    }

    /**
     * Gets or creates a cipher for encrypting the password with biometric protection.
     * If the existing key is permanently invalidated (e.g., new fingerprint enrolled),
     * it deletes the old key and creates a new one.
     *
     * @throws IllegalArgumentException for a target without a slot
     */
    fun getEncryptCipher(target: String): Cipher {
        val slot = requireNotNull(slot(target)) { "No biometric slot for target '$target'" }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        // Check if key exists and is still valid
        var needNewKey = !keyStore.containsAlias(slot.keyAlias)

        if (!needNewKey) {
            // Key exists, check if it's still valid
            try {
                val secretKey = keyStore.getKey(slot.keyAlias, null) as SecretKey
                val testCipher = Cipher.getInstance(TRANSFORMATION)
                testCipher.init(Cipher.ENCRYPT_MODE, secretKey)
                // Key is valid, use it
            } catch (_: KeyPermanentlyInvalidatedException) {
                // Key was invalidated due to new biometric enrollment
                // Delete the old key and create a new one
                keyStore.deleteEntry(slot.keyAlias)
                removeBiometricData(target) // Also remove stale encrypted password
                needNewKey = true
            }
        }

        if (needNewKey) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                slot.keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .apply {
                    // Invalidate biometric key if new fingerprint/face is enrolled
                    // Prevents attacker from adding their biometric to access wallet
                    setInvalidatedByBiometricEnrollment(true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // Explicitly pin auth-per-use to STRONG biometrics only
                        // (never device credential) instead of relying on the
                        // legacy defaults for setUserAuthenticationRequired
                        setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    }
                }
                .build()

            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        }

        val secretKey = keyStore.getKey(slot.keyAlias, null) as SecretKey
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return cipher
    }

    fun storeEncryptedPassword(password: String, target: String, cipher: Cipher): Boolean {
        val slot = slot(target) ?: return false
        return try {
            val encryptedBytes = cipher.doFinal(password.toByteArray())
            val encryptedString = Base64.encodeToString(encryptedBytes, Base64.DEFAULT)
            val ivString = Base64.encodeToString(cipher.iv, Base64.DEFAULT)

            prefs.edit {
                putString(slot.passwordKey, encryptedString)
                    .putString(slot.ivKey, ivString)
            }

            true
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to store encrypted password: ${e.message}" }
            false
        }
    }
}
