package com.gorunjinian.metrovault.feature.wallet.details

import com.gorunjinian.metrovault.domain.service.bitcoin.SignMessageRequest
import com.gorunjinian.metrovault.domain.service.scan.CompletedScan
import com.gorunjinian.metrovault.domain.service.scan.InvalidKind
import com.gorunjinian.metrovault.domain.service.scan.ScannedPayload
import com.gorunjinian.metrovault.feature.scan.addressText
import com.gorunjinian.metrovault.feature.scan.mismatchMessage

/** The field a scan on the Sign/Verify Message screen was started for. */
enum class MessageScanTarget { ADDRESS, MESSAGE }

/**
 * What a scan contributes to the Sign/Verify Message form.
 *
 * A signing request is recognized whichever field the scan was started for, since it fills the
 * form itself. Anything else goes to that field: the address, or the message exactly as encoded
 * (the signature commits to every character). A seed is never put in either.
 */
internal sealed interface MessageScanInput {
    /** A PSBT to stage as a message-signing request; a spending PSBT is turned away when staged. */
    data class MessagePsbt(val base64: String) : MessageScanInput

    data class Request(val request: SignMessageRequest) : MessageScanInput

    data class Address(val address: String) : MessageScanInput

    data class Message(val message: String) : MessageScanInput

    data class Unusable(val reason: String) : MessageScanInput

    companion object {
        fun from(scan: CompletedScan, target: MessageScanTarget): MessageScanInput =
            when (val payload = scan.payload) {
                is ScannedPayload.MessagePsbt -> MessagePsbt(payload.base64)
                is ScannedPayload.Psbt -> MessagePsbt(payload.base64)
                is ScannedPayload.SignRequest -> Request(payload.request)
                is ScannedPayload.Seed -> Unusable("This QR code holds a wallet seed. Don't use it as an address or a message.")
                else -> when (target) {
                    MessageScanTarget.ADDRESS -> payload.addressText?.let { Address(it) }
                        ?: Unusable(payload.mismatchMessage("an address"))
                    MessageScanTarget.MESSAGE -> message(scan)
                }
            }

        private fun message(scan: CompletedScan): MessageScanInput {
            val payload = scan.payload
            // A failed request, a damaged seed or a private key is still one; never sign its text.
            if (payload is ScannedPayload.Invalid && payload.kind in NEVER_A_MESSAGE) return Unusable(payload.reason)
            val text = scan.text ?: (payload as? ScannedPayload.Text)?.text
            return text?.let { Message(it) } ?: Unusable(payload.mismatchMessage("a message"))
        }

        private val NEVER_A_MESSAGE = setOf(
            InvalidKind.PSBT,
            InvalidKind.SIGN_REQUEST,
            InvalidKind.SEED,
            InvalidKind.EXTENDED_KEY,
            InvalidKind.UNREADABLE
        )
    }
}
