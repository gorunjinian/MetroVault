package com.gorunjinian.metrovault.core.crypto

import android.content.Context
import android.content.SharedPreferences
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

class BiometricPasswordManager(context: Context) {

    companion object {
        private const val KEY_ALIAS_MAIN = "biometric_key_main"
        private const val KEY_ALIAS_DECOY = "biometric_key_decoy"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PREFS_NAME = "biometric_prefs"
        private const val KEY_ENCRYPTED_PASS_MAIN = "encrypted_pass_main"
        private const val KEY_IV_MAIN = "iv_main"
        private const val KEY_ENCRYPTED_PASS_DECOY = "encrypted_pass_decoy"
        private const val KEY_IV_DECOY = "iv_decoy"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasEncodedPassword(isDecoy: Boolean): Boolean {
        val key = if (isDecoy) KEY_ENCRYPTED_PASS_DECOY else KEY_ENCRYPTED_PASS_MAIN
        return prefs.contains(key)
    }

    /**
     * Checks if the biometric key is still valid (not invalidated by new biometric enrollment).
     * Returns false if the key doesn't exist or has been permanently invalidated.
     */
    fun isKeyValid(isDecoy: Boolean): Boolean {
        return try {
            val keyAlias = if (isDecoy) KEY_ALIAS_DECOY else KEY_ALIAS_MAIN
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            
            if (!keyStore.containsAlias(keyAlias)) return false
            
            val secretKey = keyStore.getKey(keyAlias, null) as? SecretKey ?: return false
            val cipher = Cipher.getInstance("${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}")
            
            // This will throw KeyPermanentlyInvalidatedException if the key was invalidated
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            true
        } catch (_: KeyPermanentlyInvalidatedException) {
            // Key was invalidated due to new biometric enrollment
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Deletes the biometric key from Android Keystore.
     * Call this when the key is permanently invalidated.
     */
    fun deleteKey(isDecoy: Boolean) {
        try {
            val keyAlias = if (isDecoy) KEY_ALIAS_DECOY else KEY_ALIAS_MAIN
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getDecryptCipher(isDecoy: Boolean): Cipher? {
        return try {
            val keyAlias = if (isDecoy) KEY_ALIAS_DECOY else KEY_ALIAS_MAIN
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            
            if (!keyStore.containsAlias(keyAlias)) return null
            
            val secretKey = keyStore.getKey(keyAlias, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance("${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}")
            
            val ivKey = if (isDecoy) KEY_IV_DECOY else KEY_IV_MAIN
            val ivString = prefs.getString(ivKey, null) ?: return null
            val iv = Base64.decode(ivString, Base64.DEFAULT)
            
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            cipher
        } catch (_: KeyPermanentlyInvalidatedException) {
            // Key was invalidated due to new biometric enrollment
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    fun decryptPassword(isDecoy: Boolean, cipher: Cipher): String? {
        return try {
            val key = if (isDecoy) KEY_ENCRYPTED_PASS_DECOY else KEY_ENCRYPTED_PASS_MAIN
            val encryptedString = prefs.getString(key, null) ?: return null
            val encryptedBytes = Base64.decode(encryptedString, Base64.DEFAULT)
            
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun removeBiometricData(isDecoy: Boolean) {
        val key = if (isDecoy) KEY_ENCRYPTED_PASS_DECOY else KEY_ENCRYPTED_PASS_MAIN
        val ivKey = if (isDecoy) KEY_IV_DECOY else KEY_IV_MAIN
        
        prefs.edit {
            remove(key)
                .remove(ivKey)
        }
    }

    /**
     * Gets or creates a cipher for encrypting the password with biometric protection.
     * If the existing key is permanently invalidated (e.g., new fingerprint enrolled),
     * it deletes the old key and creates a new one.
     */
    fun getEncryptCipher(isDecoy: Boolean): Cipher {
        val keyAlias = if (isDecoy) KEY_ALIAS_DECOY else KEY_ALIAS_MAIN
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        
        // Check if key exists and is still valid
        var needNewKey = !keyStore.containsAlias(keyAlias)
        
        if (!needNewKey) {
            // Key exists, check if it's still valid
            try {
                val secretKey = keyStore.getKey(keyAlias, null) as SecretKey
                val testCipher = Cipher.getInstance("${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}")
                testCipher.init(Cipher.ENCRYPT_MODE, secretKey)
                // Key is valid, use it
            } catch (_: KeyPermanentlyInvalidatedException) {
                // Key was invalidated due to new biometric enrollment
                // Delete the old key and create a new one
                keyStore.deleteEntry(keyAlias)
                removeBiometricData(isDecoy) // Also remove stale encrypted password
                needNewKey = true
            }
        }
        
        if (needNewKey) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .apply {
                    // Invalidate biometric key if new fingerprint/face is enrolled
                    // Prevents attacker from adding their biometric to access wallet
                    setInvalidatedByBiometricEnrollment(true)
                }
                .build()
            
            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        }
        
        val secretKey = keyStore.getKey(keyAlias, null) as SecretKey
        val cipher = Cipher.getInstance("${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return cipher
    }

    fun storeEncryptedPassword(password: String, isDecoy: Boolean, cipher: Cipher): Boolean {
        return try {
            val encryptedBytes = cipher.doFinal(password.toByteArray())
            val encryptedString = Base64.encodeToString(encryptedBytes, Base64.DEFAULT)
            val ivString = Base64.encodeToString(cipher.iv, Base64.DEFAULT)
            
            val key = if (isDecoy) KEY_ENCRYPTED_PASS_DECOY else KEY_ENCRYPTED_PASS_MAIN
            val ivKey = if (isDecoy) KEY_IV_DECOY else KEY_IV_MAIN
            
            prefs.edit {
                putString(key, encryptedString)
                    .putString(ivKey, ivString)
            }
                
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
