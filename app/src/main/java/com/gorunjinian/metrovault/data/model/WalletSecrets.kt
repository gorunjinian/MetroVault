package com.gorunjinian.metrovault.data.model

/**
 * Wallet secrets - sensitive data (mnemonic, passphrase).
 * Stored as plaintext JSON, encrypted by SessionKeyManager before storage.
 *
 * Security: Single encryption layer via session key (derived from password via PBKDF2+HKDF)
 * stored within EncryptedSharedPreferences (Android Keystore backed).
 */
data class WalletSecrets(
    val mnemonic: String,
    val passphrase: String = ""
) {
    fun toJson(): String {
        return org.json.JSONObject().apply {
            put("mnemonic", mnemonic)
            put("passphrase", passphrase)
        }.toString()
    }

    companion object {
        fun fromJson(json: String): WalletSecrets {
            val obj = org.json.JSONObject(json)
            return WalletSecrets(
                mnemonic = obj.getString("mnemonic"),
                passphrase = obj.optString("passphrase", "")
            )
        }
    }
}
