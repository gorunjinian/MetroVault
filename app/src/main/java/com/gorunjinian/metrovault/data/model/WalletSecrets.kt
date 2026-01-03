package com.gorunjinian.metrovault.data.model

import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode

/**
 * Wallet secrets - sensitive data (mnemonic, BIP39 seed).
 * Stored as plaintext JSON, encrypted by SessionKeyManager before storage.
 *
 * @deprecated This class is deprecated. Use [WalletKeys] instead.
 * This class is kept only for one-time migration from older app versions.
 * All new code should use WalletKey which provides centralized key management.
 *
 * Security: Single encryption layer via session key (derived from password via PBKDF2+HKDF)
 * stored within EncryptedSharedPreferences (Android Keystore backed).
 *
 * STORAGE MODEL:
 * - bip39Seed: 512-bit hex-encoded seed derived from mnemonic + passphrase
 * - Passphrase is NEVER stored; only the derived seed is saved
 * - This is secure because PBKDF2 is one-way (cannot recover passphrase from seed)
 *
 * MIGRATION (from v1.x format):
 * - Old format stored: mnemonic + passphrase (plaintext string)
 * - New format stores: mnemonic + bip39Seed (hex string)
 * - Migration computes: bip39Seed = PBKDF2(mnemonic, passphrase)
 * - Migration happens transparently on first load after app update
 */
@Suppress("DEPRECATION")
@Deprecated("Use WalletKeys instead - this class is kept only for migration from older versions")
data class WalletSecrets(
    val mnemonic: String,
    val bip39Seed: String = ""  // Hex-encoded 64-byte (512-bit) BIP39 seed
) {
    fun toJson(): String {
        return org.json.JSONObject().apply {
            put("mnemonic", mnemonic)
            put("bip39Seed", bip39Seed)
        }.toString()
    }

    companion object {
        /**
         * Deserializes WalletSecrets from JSON with automatic migration.
         * 
         * FORMAT DETECTION:
         * - New format: has "bip39Seed" field with non-empty value
         * - Old format: has "passphrase" field (may be empty string)
         * 
         * MIGRATION LOGIC (old → new):
         * 1. Read mnemonic and passphrase from old JSON
         * 2. Compute BIP39 seed: PBKDF2(mnemonic, passphrase, 2048 iterations)
         * 3. Convert to hex string and store as bip39Seed
         * 4. Next save will write new format (passphrase field dropped)
         */
        fun fromJson(json: String): WalletSecrets {
            val obj = org.json.JSONObject(json)
            val mnemonic = obj.getString("mnemonic")
            
            // Check if new format (bip39Seed) or old format (passphrase)
            val seed = if (obj.has("bip39Seed") && obj.getString("bip39Seed").isNotEmpty()) {
                // New format - seed already stored
                obj.getString("bip39Seed")
            } else {
                // Old format migration: compute seed from mnemonic + passphrase
                val passphrase = obj.optString("passphrase", "")
                val seedBytes = MnemonicCode.toSeed(mnemonic.split(" "), passphrase)
                seedBytes.joinToString("") { "%02x".format(it) }
            }
            
            return WalletSecrets(
                mnemonic = mnemonic,
                bip39Seed = seed
            )
        }
    }
}
