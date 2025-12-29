package com.gorunjinian.metrovault.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Multisig wallet configuration.
 * Stores all information needed to generate addresses and sign transactions.
 *
 * @property m Required number of signatures (threshold)
 * @property n Total number of cosigners
 * @property cosigners All cosigner key information
 * @property localKeyFingerprints Fingerprints of keys we have locally
 * @property scriptType Type of multisig script (P2WSH, P2SH_P2WSH, P2SH)
 * @property rawDescriptor Original descriptor string for address generation
 */
data class MultisigConfig(
    val m: Int,
    val n: Int,
    val cosigners: List<CosignerInfo>,
    val localKeyFingerprints: List<String>,
    val scriptType: MultisigScriptType,
    val rawDescriptor: String
) {
    /**
     * Determines if this is a testnet multisig wallet.
     * Checks the cosigner derivation paths for coin_type=1' (testnet).
     * Also checks xpub prefixes as fallback (tpub/Vpub/Upub = testnet).
     */
    fun isTestnet(): Boolean {
        // Check cosigner derivation paths for coin_type=1'
        // Path format: "48h/1h/0h/2h" or "48'/1'/0'/2'" where second element is coin_type
        for (cosigner in cosigners) {
            val pathParts = cosigner.derivationPath.split("/").filter { it.isNotEmpty() }
            if (pathParts.size >= 2) {
                val coinType = pathParts[1].replace("'", "").replace("h", "")
                if (coinType == "1") return true
            }
        }

        // Fallback: check xpub prefixes
        for (cosigner in cosigners) {
            val xpub = cosigner.xpub.lowercase()
            // Testnet prefixes: tpub, upub, vpub (lowercase single-sig), Vpub, Upub (uppercase multisig)
            if (xpub.startsWith("tpub") || xpub.startsWith("upub") || xpub.startsWith("vpub")) {
                return true
            }
        }

        return false
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("m", m)
        put("n", n)
        put("cosigners", JSONArray(cosigners.map { it.toJson() }))
        put("localKeyFingerprints", JSONArray(localKeyFingerprints))
        put("scriptType", scriptType.name)
        put("rawDescriptor", rawDescriptor)
    }

    companion object {
        fun fromJson(json: JSONObject): MultisigConfig {
            val cosignersArray = json.getJSONArray("cosigners")
            val cosigners = (0 until cosignersArray.length()).map { 
                CosignerInfo.fromJson(cosignersArray.getJSONObject(it)) 
            }
            
            val fingerprintsArray = json.getJSONArray("localKeyFingerprints")
            val fingerprints = (0 until fingerprintsArray.length()).map { 
                fingerprintsArray.getString(it) 
            }
            
            return MultisigConfig(
                m = json.getInt("m"),
                n = json.getInt("n"),
                cosigners = cosigners,
                localKeyFingerprints = fingerprints,
                scriptType = MultisigScriptType.valueOf(json.getString("scriptType")),
                rawDescriptor = json.getString("rawDescriptor")
            )
        }
    }
}

/**
 * Information about a single cosigner in a multisig setup.
 *
 * @property xpub Extended public key (tpub/xpub/Zpub etc.)
 * @property fingerprint Master fingerprint (8 hex characters)
 * @property derivationPath BIP48 derivation path (e.g., "48h/1h/0h/2h")
 * @property isLocal True if we have this key's mnemonic locally
 */
data class CosignerInfo(
    val xpub: String,
    val fingerprint: String,
    val derivationPath: String,
    val isLocal: Boolean,
    val keyId: String? = null    // Direct reference to WalletKey (if we have this key locally)
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("xpub", xpub)
        put("fingerprint", fingerprint)
        put("derivationPath", derivationPath)
        put("isLocal", isLocal)
        if (keyId != null) {
            put("keyId", keyId)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): CosignerInfo = CosignerInfo(
            xpub = json.getString("xpub"),
            fingerprint = json.getString("fingerprint"),
            derivationPath = json.getString("derivationPath"),
            isLocal = json.optBoolean("isLocal", false),
            keyId = json.optString("keyId", null).takeIf { !it.isNullOrEmpty() }
        )
    }
}

/**
 * Script types supported for multisig wallets.
 */
enum class MultisigScriptType {
    /** Native SegWit multisig - P2WSH (bc1q.../tb1q... 62+ chars) */
    P2WSH,
    
    /** Nested SegWit multisig - P2SH-P2WSH (3.../2...) */
    P2SH_P2WSH,
    
    /** Legacy multisig - P2SH (3.../2...) */
    P2SH;

    companion object {
        /**
         * Detect script type from descriptor wrapper.
         * @param descriptor The descriptor string
         * @return The detected script type, defaults to P2WSH
         */
        fun fromDescriptor(descriptor: String): MultisigScriptType {
            val lower = descriptor.lowercase()
            return when {
                lower.startsWith("wsh(") -> P2WSH
                lower.startsWith("sh(wsh(") -> P2SH_P2WSH
                lower.startsWith("sh(") && !lower.startsWith("sh(wsh(") -> P2SH
                else -> P2WSH // Default to P2WSH for modern multisig
            }
        }
    }
}
