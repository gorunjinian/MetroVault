package com.gorunjinian.metrovault.data.model

/**
 * Wallet metadata - non-sensitive data for display.
 * Stored as plain JSON within EncryptedSharedPreferences.
 * 
 * FIELD DEFINITIONS:
 * - hasPassphrase: Controls wallet unlock behavior
 *   - false: Opens directly using stored BIP39 seed (no passphrase entry needed)
 *   - true: Passphrase entry required on open (stored seed is base seed without passphrase)
 * 
 * MIGRATION (from v1.x format):
 * Old format had two fields:
 *   - hasPassphrase: true if wallet was created with a passphrase
 *   - savePassphraseLocally: true if passphrase was saved to disk
 * 
 * New format has only:
 *   - hasPassphrase: true ONLY if passphrase needs to be entered on wallet open
 * 
 * Migration formula: new.hasPassphrase = old.hasPassphrase && !old.savePassphraseLocally
 * 
 * EXAMPLE MIGRATIONS:
 * | Old hasPassphrase | Old saveLocally | → New hasPassphrase | Behavior              |
 * |-------------------|-----------------|---------------------|-----------------------|
 * | false             | true            | false               | Opens directly        |
 * | true              | true            | false               | Opens directly        |
 * | true              | false           | true                | Prompts for passphrase|
 */
data class WalletMetadata(
    val id: String,
    val name: String,
    val derivationPath: String,
    val masterFingerprint: String,
    val hasPassphrase: Boolean,  // true = passphrase entry required on open
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
        /**
         * Deserializes WalletMetadata from JSON with automatic migration.
         * 
         * FORMAT DETECTION:
         * - Old format: has "savePassphraseLocally" field
         * - New format: no "savePassphraseLocally" field, hasPassphrase has new meaning
         * 
         * MIGRATION LOGIC:
         * If old format detected:
         *   hasPassphrase = legacyHasPassphrase && !legacySaveLocally
         * If new format:
         *   hasPassphrase is used directly (already has correct meaning)
         */
        fun fromJson(json: String): WalletMetadata {
            val obj = org.json.JSONObject(json)
            
            // Detect format by checking for savePassphraseLocally field
            val hasPassphrase = if (obj.has("savePassphraseLocally")) {
                // OLD FORMAT: Apply migration logic
                val legacyHasPassphrase = obj.optBoolean("hasPassphrase", false)
                val legacySaveLocally = obj.optBoolean("savePassphraseLocally", true)
                legacyHasPassphrase && !legacySaveLocally
            } else {
                // NEW FORMAT: Use hasPassphrase directly
                obj.optBoolean("hasPassphrase", false)
            }
            
            return WalletMetadata(
                id = obj.getString("id"),
                name = obj.getString("name"),
                derivationPath = obj.getString("derivationPath"),
                masterFingerprint = obj.optString("masterFingerprint", ""),
                hasPassphrase = hasPassphrase,
                createdAt = obj.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}

