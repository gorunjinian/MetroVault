package com.gorunjinian.metrovault.data.model

import org.json.JSONObject

/**
 * Wallet key - stores cryptographic key material.
 * Each WalletKey represents a unique BIP39 mnemonic/seed pair.
 * 
 * ARCHITECTURE:
 * - Keys are stored separately from wallets
 * - Multiple wallets can reference the same key (via keyId)
 * - Single-sig wallets have exactly 1 keyId reference
 * - Multisig wallets have 0-N keyId references (for local signing keys)
 * 
 * SECURITY:
 * - Stored as encrypted JSON (session key + EncryptedSharedPreferences)
 * - Fingerprint stored for fast cosigner matching (avoids re-derivation)
 * - Label is user-facing only (not sensitive)
 * 
 * @property keyId Unique identifier (UUID)
 * @property mnemonic BIP39 mnemonic phrase (12 or 24 words, space-separated)
 * @property bip39Seed Hex-encoded 64-byte (512-bit) BIP39 seed
 * @property fingerprint Master fingerprint (8 hex chars, e.g., "1a2b3c4d")
 * @property label User-facing label (e.g., "Key 1", "My Main Seed")
 */
data class WalletKey(
    val keyId: String,
    val mnemonic: String,
    val bip39Seed: String,
    val fingerprint: String,
    val label: String = ""
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("keyId", keyId)
            put("mnemonic", mnemonic)
            put("bip39Seed", bip39Seed)
            put("fingerprint", fingerprint)
            put("label", label)
        }.toString()
    }

    companion object {
        fun fromJson(json: String): WalletKey {
            val obj = JSONObject(json)
            return WalletKey(
                keyId = obj.getString("keyId"),
                mnemonic = obj.getString("mnemonic"),
                bip39Seed = obj.getString("bip39Seed"),
                fingerprint = obj.getString("fingerprint"),
                label = obj.optString("label", "")
            )
        }
    }
}
