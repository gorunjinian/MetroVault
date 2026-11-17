    package com.gorunjinian.metrovault.domain.service.bitcoin

import com.gorunjinian.vaultovich.Block
import com.gorunjinian.vaultovich.BlockHash
import com.gorunjinian.vaultovich.DeterministicWallet
import com.gorunjinian.vaultovich.KeyPath
import com.gorunjinian.vaultovich.MessageSigning
import com.gorunjinian.vaultovich.PrivateKey
import org.json.JSONObject

/**
 * A text message-signing request scanned from a coordinator: which key signs (a full BIP-32 path
 * from the master key), what it signs, and which address form the signature is presented for.
 *
 * Two wire formats are understood, both single-frame plain-text QRs:
 * - Specter style: `signmessage m/84h/0h/0h/0/5 ascii:hello` (payload prefix `ascii:`, `utf8:`
 *   or `hex:`; no prefix means plain text).
 * - Coinkite / Coldcard style JSON: `{"msg": "hello", "subpath": "m/84h/0h/0h/0/5", "addr_fmt": "p2wpkh"}`.
 *
 * This class is pure (no wallet, no Android state) so the parsing and derivation are unit-tested
 * directly; [WalletMessageSigner] supplies the active wallet's master key.
 */
data class SignMessageRequest(
    val keyPath: KeyPath,
    val message: String,
    val addressType: MessageSigning.AddressType,
) {
    /** The signing key for a request and the address it is presented under. */
    data class ResolvedKey(val address: String, val privateKey: PrivateKey)

    /** The path as shown to the user, e.g. `m/84h/1h/0h/0/5`. */
    val displayPath: String get() = keyPath.asString('h')

    /**
     * The chain the address is encoded for. The path's coin type decides (`0h` mainnet, `1h`
     * testnet) because the requester derived the address from that same path; a path without a
     * recognisable coin type falls back to the active wallet's chain.
     */
    fun chainHash(walletChainHash: BlockHash): BlockHash = when (keyPath.path.getOrNull(1)) {
        DeterministicWallet.hardened(0) -> Block.LivenetGenesisBlock.hash
        DeterministicWallet.hardened(1) -> Block.Testnet4GenesisBlock.hash
        else -> walletChainHash
    }

    /** Derive the key at [keyPath] from [masterPrivateKey] and encode its address. */
    fun resolve(
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        walletChainHash: BlockHash,
    ): ResolvedKey {
        val derived = masterPrivateKey.derivePrivateKey(keyPath)
        val publicKey = derived.publicKey
        val chainHash = chainHash(walletChainHash)
        val address = when (addressType) {
            MessageSigning.AddressType.P2PKH -> publicKey.p2pkhAddress(chainHash)
            MessageSigning.AddressType.P2SH_P2WPKH -> publicKey.p2shOfP2wpkhAddress(chainHash)
            MessageSigning.AddressType.P2WPKH -> publicKey.p2wpkhAddress(chainHash)
            MessageSigning.AddressType.P2TR -> publicKey.xOnly().p2trAddress(chainHash)
        }
        return ResolvedKey(address, derived.privateKey)
    }

    companion object {
        private val SIGNMESSAGE = Regex(
            "^signmessage\\s+(\\S+)\\s(.*)$",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val PATH = Regex("^m(/\\d{1,10}[h']?)*$")
        private val HEX = Regex("^[0-9a-fA-F]+$")

        /**
         * Whether [text] is meant as a signing request (as opposed to a bare address or message),
         * so a malformed one is reported instead of being pasted into a field.
         */
        fun looksLikeRequest(text: String): Boolean {
            val trimmed = text.trimStart()
            return trimmed.startsWith("signmessage ", ignoreCase = true) ||
                (trimmed.startsWith("{") && trimmed.contains("\"msg\""))
        }

        /** Parse either wire format; null when [text] is not a well-formed request. */
        fun parse(text: String): SignMessageRequest? {
            val trimmed = text.trimStart()
            return try {
                if (trimmed.startsWith("{")) parseCoinkiteJson(trimmed) else parseSignMessage(trimmed)
            } catch (_: Exception) {
                null
            }
        }

        private fun parseSignMessage(text: String): SignMessageRequest? {
            val match = SIGNMESSAGE.matchEntire(text) ?: return null
            val keyPath = parsePath(match.groupValues[1]) ?: return null
            val payload = match.groupValues[2]
            val message = when {
                payload.startsWith("ascii:", ignoreCase = true) -> payload.substring(6)
                payload.startsWith("utf8:", ignoreCase = true) -> payload.substring(5)
                payload.startsWith("hex:", ignoreCase = true) -> decodeHexText(payload.substring(4).trim()) ?: return null
                else -> payload
            }
            if (message.isEmpty()) return null
            return SignMessageRequest(keyPath, message, addressTypeForPurpose(keyPath) ?: MessageSigning.AddressType.P2WPKH)
        }

        private fun parseCoinkiteJson(text: String): SignMessageRequest? {
            val json = JSONObject(text)
            val message = json.optString("msg", "")
            if (message.isEmpty()) return null
            // Coldcard defaults: the master key and a classic (P2PKH) address.
            val keyPath = parsePath(json.optString("subpath", "m")) ?: return null
            val addressType = if (json.has("addr_fmt")) {
                when (json.optString("addr_fmt").trim().lowercase()) {
                    "p2pkh" -> MessageSigning.AddressType.P2PKH
                    "p2wpkh" -> MessageSigning.AddressType.P2WPKH
                    "p2sh-p2wpkh", "p2wpkh-p2sh" -> MessageSigning.AddressType.P2SH_P2WPKH
                    "p2tr" -> MessageSigning.AddressType.P2TR
                    else -> return null // e.g. multisig formats: no single key can honour them
                }
            } else {
                addressTypeForPurpose(keyPath) ?: MessageSigning.AddressType.P2PKH
            }
            return SignMessageRequest(keyPath, message, addressType)
        }

        /**
         * Strict path parsing: every component must be a valid child index. A component that
         * cannot be read rejects the whole request, since skipping it would silently sign with a
         * different key.
         */
        private fun parsePath(raw: String): KeyPath? {
            val normalized = raw.trim().replace('H', 'h').replace('M', 'm')
            if (!PATH.matches(normalized)) return null
            return try {
                KeyPath(normalized)
            } catch (_: Exception) {
                null
            }
        }

        private fun addressTypeForPurpose(keyPath: KeyPath): MessageSigning.AddressType? =
            when (keyPath.path.firstOrNull()) {
                DeterministicWallet.hardened(44) -> MessageSigning.AddressType.P2PKH
                DeterministicWallet.hardened(49) -> MessageSigning.AddressType.P2SH_P2WPKH
                DeterministicWallet.hardened(84) -> MessageSigning.AddressType.P2WPKH
                DeterministicWallet.hardened(86) -> MessageSigning.AddressType.P2TR
                else -> null
            }

        /** Hex payloads carry text; anything that is not valid UTF-8 cannot be shown or signed as a string. */
        private fun decodeHexText(hex: String): String? {
            if (hex.length % 2 != 0 || !HEX.matches(hex)) return null
            val bytes = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            val decoded = String(bytes, Charsets.UTF_8)
            return decoded.takeIf { it.toByteArray(Charsets.UTF_8).contentEquals(bytes) }
        }
    }
}
