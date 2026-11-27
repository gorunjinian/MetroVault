package com.gorunjinian.metrovault.feature.scan

import com.gorunjinian.metrovault.domain.service.scan.ScannedPayload

/** What the payload is, to follow "This QR code holds". */
val ScannedPayload.label: String
    get() = when (this) {
        is ScannedPayload.Psbt -> "a transaction to sign"
        is ScannedPayload.MessagePsbt, is ScannedPayload.SignRequest -> "a message-signing request"
        is ScannedPayload.Address -> "a Bitcoin address"
        is ScannedPayload.SilentPaymentAddress -> "a silent payment address"
        is ScannedPayload.SignedMessage -> "a signed message"
        is ScannedPayload.Signature -> "a signature"
        is ScannedPayload.Seed -> "a wallet seed"
        is ScannedPayload.MultisigSetup -> "a multisig setup"
        is ScannedPayload.ExtendedKeys -> "an extended public key"
        is ScannedPayload.RawTransaction -> "a finished transaction"
        is ScannedPayload.Text -> "plain text"
        is ScannedPayload.Invalid -> "unusable data"
    }

/**
 * Tells the user a scan isn't what the screen needs. A damaged payload explains itself instead.
 *
 * @param expected What the screen needs, with its article: "a transaction to sign"
 */
fun ScannedPayload.mismatchMessage(expected: String): String =
    if (this is ScannedPayload.Invalid) reason else "This QR code holds $label, not $expected."

/**
 * What an address field takes from the payload, or null if it can't be an address. Plain text is
 * kept, so an address the classifier doesn't know still reaches the field for the user to see.
 */
val ScannedPayload.addressText: String?
    get() = when (this) {
        is ScannedPayload.Address -> address
        is ScannedPayload.SilentPaymentAddress -> address
        is ScannedPayload.Text -> text.trim()
        else -> null
    }
