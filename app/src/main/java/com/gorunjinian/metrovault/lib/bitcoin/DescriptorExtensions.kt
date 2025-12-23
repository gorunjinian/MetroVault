package com.gorunjinian.metrovault.lib.bitcoin

import com.gorunjinian.metrovault.data.model.ScriptType

/**
 * Extensions for unified output descriptor generation.
 * Generates descriptors with multipath syntax compatible with Sparrow, Bitcoin Core, etc.
 */
object DescriptorExtensions {

    /**
     * Generates a unified output descriptor with multipath syntax.
     *
     * @param fingerprint Master fingerprint (4 bytes as Long)
     * @param accountPath Account derivation path (e.g., "m/84'/0'/0'")
     * @param extendedKey Extended public OR private key string (xpub/xprv/zpub/zprv/etc.)
     * @param scriptType Script type for the wallet
     * @return Descriptor string with checksum
     */
    fun getUnifiedDescriptor(
        fingerprint: Long,
        accountPath: String,
        extendedKey: String,
        scriptType: ScriptType
    ): String {
        // Format fingerprint as 8-char lowercase hex
        val fp = (fingerprint and 0xFFFFFFFFL).toString(16).padStart(8, '0')

        // Convert path notation: remove "m/" prefix and replace ' with h
        // e.g., "m/84'/0'/0'" -> "84h/0h/0h"
        val pathWithH = accountPath
            .removePrefix("m/")
            .replace("'", "h")

        // Build descriptor based on script type
        val desc = when (scriptType) {
            ScriptType.P2WPKH -> "wpkh([$fp/$pathWithH]$extendedKey/<0;1>/*)"
            ScriptType.P2TR -> "tr([$fp/$pathWithH]$extendedKey/<0;1>/*)"
            ScriptType.P2SH_P2WPKH -> "sh(wpkh([$fp/$pathWithH]$extendedKey/<0;1>/*))"
            ScriptType.P2PKH -> "pkh([$fp/$pathWithH]$extendedKey/<0;1>/*)"
        }

        // Use existing checksum function from Descriptor.kt
        return "$desc#${Descriptor.checksum(desc)}"
    }
}
