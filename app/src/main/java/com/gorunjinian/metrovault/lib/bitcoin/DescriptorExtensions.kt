package com.gorunjinian.metrovault.lib.bitcoin

import com.gorunjinian.metrovault.data.model.ScriptType

/**
 * Extensions for unified output descriptor generation.
 * Generates descriptors with multipath syntax compatible with Sparrow, Bitcoin Core, etc.
 */
object DescriptorExtensions {

    // ==================== Private Helpers ====================
    
    /**
     * Formats a fingerprint as 8-character lowercase hex string.
     * @param fingerprint Master fingerprint (4 bytes as Long)
     * @return Hex string like "12345678"
     */
    private fun formatFingerprint(fingerprint: Long): String =
        (fingerprint and 0xFFFFFFFFL).toString(16).padStart(8, '0')
    
    /**
     * Converts derivation path notation for descriptor format.
     * Removes "m/" prefix and replaces ' with h.
     * @param path Derivation path like "m/84'/0'/0'"
     * @return Formatted path like "84h/0h/0h"
     */
    private fun formatPathForDescriptor(path: String): String =
        path.removePrefix("m/").replace("'", "h")

    // ==================== Public API ====================

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
        val fp = formatFingerprint(fingerprint)
        val pathWithH = formatPathForDescriptor(accountPath)

        // Build descriptor based on script type
        val desc = when (scriptType) {
            ScriptType.P2WPKH -> "wpkh([$fp/$pathWithH]$extendedKey/<0;1>/*)"
            ScriptType.P2TR -> "tr([$fp/$pathWithH]$extendedKey/<0;1>/*)"
            ScriptType.P2SH_P2WPKH -> "sh(wpkh([$fp/$pathWithH]$extendedKey/<0;1>/*))"
            ScriptType.P2PKH -> "pkh([$fp/$pathWithH]$extendedKey/<0;1>/*)"
        }

        return "$desc#${Descriptor.checksum(desc)}"
    }

    /**
     * Generates a BIP48 multisig cosigner descriptor.
     * 
     * @param fingerprint Master fingerprint (4 bytes as Long)
     * @param bip48Path BIP48 derivation path (e.g., "m/48'/0'/0'/2'")
     * @param extendedKey Extended public OR private key string (Zpub/Zprv/Vpub/Vprv/etc.)
     * @param isWrapped True for P2SH-P2WSH (script type 1'), false for P2WSH (script type 2')
     * @return Descriptor string with checksum
     */
    fun getBip48Descriptor(
        fingerprint: Long,
        bip48Path: String,
        extendedKey: String,
        isWrapped: Boolean = false
    ): String {
        val fp = formatFingerprint(fingerprint)
        val pathWithH = formatPathForDescriptor(bip48Path)

        // Build descriptor - wsh() for native, sh(wsh()) for wrapped
        // Note: For a single cosigner's key, we use /0/* for receive addresses
        val desc = if (isWrapped) {
            "sh(wsh([$fp/$pathWithH]$extendedKey/0/*))"
        } else {
            "wsh([$fp/$pathWithH]$extendedKey/0/*)"
        }

        return "$desc#${Descriptor.checksum(desc)}"
    }
}

