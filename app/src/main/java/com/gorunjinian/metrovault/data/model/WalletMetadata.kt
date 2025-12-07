package com.gorunjinian.metrovault.data.model

/**
 * Wallet metadata - non-sensitive data for display.
 * Stored as plain JSON within EncryptedSharedPreferences.
 */
data class WalletMetadata(
    val id: String,
    val name: String,
    val derivationPath: String,
    val masterFingerprint: String,
    val hasPassphrase: Boolean,
    val createdAt: Long
) {
    fun toJson(): String {
        return org.json.JSONObject().apply {
            put("id", id)
            put("name", name)
            put("derivationPath", derivationPath)
            put("masterFingerprint", masterFingerprint)
            put("hasPassphrase", hasPassphrase)
            put("createdAt", createdAt)
        }.toString()
    }

    companion object {
        fun fromJson(json: String): WalletMetadata {
            val obj = org.json.JSONObject(json)
            return WalletMetadata(
                id = obj.getString("id"),
                name = obj.getString("name"),
                derivationPath = obj.getString("derivationPath"),
                masterFingerprint = obj.optString("masterFingerprint", ""),
                hasPassphrase = obj.optBoolean("hasPassphrase", false),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}
