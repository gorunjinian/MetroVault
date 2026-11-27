package com.gorunjinian.metrovault.domain.service.scan

import com.gorunjinian.metrovault.core.qr.OutputFormat
import com.gorunjinian.metrovault.domain.service.bitcoin.SignMessageRequest
import com.gorunjinian.metrovault.domain.service.multisig.MultisigSetupFormat
import com.gorunjinian.vaultovich.ScriptType

/** The chain an address or key is encoded for. Signet shares testnet's encodings. */
enum class ScanNetwork { MAINNET, TESTNET, REGTEST }

/**
 * What a scanned QR turned out to be, decided by [ScanPayloadClassifier] from the content alone:
 * no wallet is consulted. [ScanRouter] then picks the action and the wallet.
 */
sealed interface ScannedPayload {

    /**
     * A transaction to review and sign.
     *
     * @property base64 The PSBT, re-encoded as base64 whatever the transport
     * @property replyFormat The QR format the signed PSBT should default to, matching the request
     * @property inputFingerprints Master fingerprints (lowercase hex) the inputs' BIP-32 and
     *   taproot derivations name; empty when the PSBT carries none
     */
    data class Psbt(
        val base64: String,
        val replyFormat: OutputFormat,
        val inputFingerprints: Set<String>
    ) : ScannedPayload

    /**
     * A BIP-322 message-signing PSBT: it carries `PSBT_GLOBAL_GENERIC_SIGNED_MESSAGE`. Its address
     * and message are validated against the signing wallet's chain when it is staged, since the
     * address encoding depends on that chain.
     */
    data class MessagePsbt(
        val base64: String,
        val replyFormat: OutputFormat,
        val inputFingerprints: Set<String>
    ) : ScannedPayload

    /** A Bitcoin address, bare or from a BIP-21 `bitcoin:` URI (whose optional fields are kept). */
    data class Address(
        val address: String,
        val network: ScanNetwork,
        val amountSats: Long? = null,
        val label: String? = null,
        val message: String? = null
    ) : ScannedPayload

    /** A BIP-352 silent payment address. Keys are compressed-point hex, lowercase. */
    data class SilentPaymentAddress(
        val address: String,
        val network: ScanNetwork,
        val scanPubKeyHex: String,
        val spendPubKeyHex: String
    ) : ScannedPayload

    /** A text message-signing request: `signmessage …` or Coinkite JSON. */
    data class SignRequest(val request: SignMessageRequest) : ScannedPayload

    /** An armored signed message carrying all three parts: message, address and signature. */
    data class SignedMessage(
        val message: String,
        val address: String,
        val signature: String
    ) : ScannedPayload

    /** A signature on its own; the address and message must come from the user. */
    data class Signature(val signature: String, val encoding: SignatureEncoding) : ScannedPayload

    /**
     * A BIP-39 mnemonic. [toString] never prints the words, so a stray log line can't leak them.
     */
    data class Seed(val words: List<String>, val format: SeedFormat) : ScannedPayload {
        override fun toString(): String = "Seed(format=$format, words=${words.size})"
    }

    /** A multisig wallet setup for Import Multisig, which parses it against the vault's keys. */
    data class MultisigSetup(
        val content: String,
        val format: MultisigSetupFormat,
        val walletName: String? = null
    ) : ScannedPayload

    /**
     * Extended public keys from a coordinator or another signer: a bare xpub, a key expression,
     * a single-sig descriptor, or a UR key/account record.
     *
     * @property descriptor The descriptor text the keys came from, if any
     */
    data class ExtendedKeys(
        val keys: List<ScannedExtendedKey>,
        val descriptor: String? = null
    ) : ScannedPayload

    /** A complete serialized transaction; decoded for display only. */
    data class RawTransaction(val hex: String, val txid: String) : ScannedPayload

    /** Text nothing else claimed. */
    data class Text(val text: String) : ScannedPayload

    /** Content that looks like [kind] but can't be used, with a reason fit to show the user. */
    data class Invalid(val kind: InvalidKind, val reason: String) : ScannedPayload
}

/**
 * One extended public key.
 *
 * @property xpub As scanned, in any SLIP-132 form (`xpub`, `zpub`, `Vpub`, …)
 * @property fingerprint Master fingerprint from the key origin (lowercase hex), if given. A key
 *   with a fingerprint and no [derivationPath] claims to be that master key itself.
 * @property derivationPath Origin path without the `m/` prefix (e.g. `84'/0'/0'`), if given
 * @property network From the key's version bytes
 * @property childPath The steps after the key, without the leading slash: a branch (`0` or a
 *   `<0;1>` multipath step) and its `*` wildcard, as written; null if there are none
 * @property descriptorScript The address type the key's single-sig descriptor wraps it in; null
 *   for a key that didn't come in a descriptor
 */
data class ScannedExtendedKey(
    val xpub: String,
    val fingerprint: String?,
    val derivationPath: String?,
    val network: ScanNetwork,
    val childPath: String? = null,
    val descriptorScript: ScriptType? = null
)

enum class SignatureEncoding {
    /** 65-byte recoverable ECDSA (Electrum / BIP-137 headers). */
    RECOVERABLE_ECDSA,

    /** A BIP-322 "simple" signature: a serialized witness stack. */
    BIP322_SIMPLE
}

enum class SeedFormat {
    /** Space-separated words. */
    WORDS,

    /** SeedSigner Standard SeedQR: four digits per word. */
    STANDARD_SEEDQR,

    /** SeedSigner CompactSeedQR: raw entropy bytes. */
    COMPACT_SEEDQR,

    /** `ur:crypto-bip39`. */
    UR_BIP39
}

enum class InvalidKind {
    PSBT,
    ADDRESS,
    SIGN_REQUEST,
    SIGNED_MESSAGE,
    SEED,
    EXTENDED_KEY,
    TRANSACTION,

    /** A recognized transport carrying something MetroVault doesn't handle. */
    UNSUPPORTED,

    /** Frames that can't be joined into anything. */
    UNREADABLE
}
