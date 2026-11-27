package com.gorunjinian.metrovault.domain.service.scan

/**
 * Reads an armored signed message, the one QR form that carries message, address and signature
 * together:
 *
 * ```
 * -----BEGIN BITCOIN SIGNED MESSAGE-----
 * <message, possibly several lines>
 * -----BEGIN SIGNATURE-----            (Electrum / Sparrow; Coldcard writes BEGIN BITCOIN SIGNATURE)
 * <address>
 * <signature>
 * -----END BITCOIN SIGNED MESSAGE-----  (Coldcard: END BITCOIN SIGNATURE)
 * ```
 *
 * The message is kept byte for byte: everything between the header line and the signature marker,
 * less the single line break before the marker. Verification hashes exactly those bytes, so no
 * trimming or line-ending normalization is applied to it.
 */
object SignedMessageArmor {

    const val MESSAGE_HEADER = "-----BEGIN BITCOIN SIGNED MESSAGE-----"

    private val SIGNATURE_MARKER =
        Regex("^-----BEGIN (?:BITCOIN )?SIGNATURE-----[ \\t\\r]*$", RegexOption.MULTILINE)
    private val END_MARKER =
        Regex("^-----END BITCOIN (?:SIGNED MESSAGE|SIGNATURE)-----[ \\t\\r]*$", RegexOption.MULTILINE)

    /** Whether [text] opens with the armor header, so a malformed block is reported rather than shown as text. */
    fun isArmored(text: String): Boolean = text.trimStart().startsWith(MESSAGE_HEADER)

    /** The three parts, or null when [text] isn't a complete armored signed message. */
    fun parse(text: String): ScannedPayload.SignedMessage? {
        val headerStart = text.indexOf(MESSAGE_HEADER)
        if (headerStart < 0) return null

        val headerEnd = headerStart + MESSAGE_HEADER.length
        val headerLineEnd = text.indexOf('\n', headerEnd)
        if (headerLineEnd < 0 || text.substring(headerEnd, headerLineEnd).isNotBlank()) return null
        val messageStart = headerLineEnd + 1

        val marker = SIGNATURE_MARKER.find(text, messageStart) ?: return null
        var messageEnd = marker.range.first
        if (messageEnd > messageStart && text[messageEnd - 1] == '\n') {
            messageEnd--
            if (messageEnd > messageStart && text[messageEnd - 1] == '\r') messageEnd--
        }
        val message = text.substring(messageStart, messageEnd)
        if (message.isEmpty()) return null

        val tailStart = marker.range.last + 1
        val tailEnd = END_MARKER.find(text, tailStart)?.range?.first ?: text.length
        val lines = text.substring(tailStart, tailEnd).lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return null

        // A long signature may be wrapped over several lines.
        return ScannedPayload.SignedMessage(
            message = message,
            address = lines[0],
            signature = lines.drop(1).joinToString("")
        )
    }
}
