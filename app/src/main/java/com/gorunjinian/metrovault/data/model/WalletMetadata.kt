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
    val createdAt: Long,
    val accounts: List<Int> = listOf(0),      // Account numbers under this wallet
    val activeAccountNumber: Int = 0,          // Currently active account number
    val accountNames: Map<Int, String> = emptyMap()  // Custom display names (empty = use default)
) {
    /**
     * Get the display name for an account.
     * Returns custom name if set, otherwise default "Account N" format.
     */
    fun getAccountDisplayName(accountNumber: Int): String {
        return accountNames[accountNumber] ?: "Account $accountNumber"
    }
    /**
     * Get the derivation path for the active account.
     * If activeAccountNumber differs from the stored path's account, builds a new path.
     */
    fun getActiveDerivationPath(): String {
        val pathAccount = DerivationPaths.getAccountNumber(derivationPath)
        return if (pathAccount == activeAccountNumber) {
            derivationPath
        } else {
            DerivationPaths.withAccountNumber(derivationPath, activeAccountNumber)
        }
    }

    fun toJson(): String {
        return org.json.JSONObject().apply {
            put("id", id)
            put("name", name)
            put("derivationPath", derivationPath)
            put("masterFingerprint", masterFingerprint)
            put("hasPassphrase", hasPassphrase)
            put("createdAt", createdAt)
            put("accounts", org.json.JSONArray(accounts))
            put("activeAccountNumber", activeAccountNumber)
            // Serialize accountNames map
            if (accountNames.isNotEmpty()) {
                put("accountNames", org.json.JSONObject().apply {
                    accountNames.forEach { (k, v) -> put(k.toString(), v) }
                })
            }
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
            
            // MIGRATION: Wallets without accounts array get [0] as default
            val accounts = if (obj.has("accounts")) {
                val arr = obj.getJSONArray("accounts")
                (0 until arr.length()).map { arr.getInt(it) }
            } else {
                listOf(0)
            }
            val activeAccountNumber = obj.optInt("activeAccountNumber", 0)
            
            // MIGRATION: Parse accountNames map, default to empty for existing wallets
            val accountNames = if (obj.has("accountNames")) {
                val namesObj = obj.getJSONObject("accountNames")
                namesObj.keys().asSequence().associate { key ->
                    key.toInt() to namesObj.getString(key)
                }
            } else {
                emptyMap()
            }
            
            return WalletMetadata(
                id = obj.getString("id"),
                name = obj.getString("name"),
                derivationPath = obj.getString("derivationPath"),
                masterFingerprint = obj.optString("masterFingerprint", ""),
                hasPassphrase = hasPassphrase,
                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                accounts = accounts,
                activeAccountNumber = activeAccountNumber,
                accountNames = accountNames
            )
        }
    }
}

