package com.gorunjinian.metrovault.core.qr

import com.gorunjinian.bbqr.ContinuousJoinResult
import com.gorunjinian.bbqr.ContinuousJoiner
import com.gorunjinian.bbqr.DecodeError
import com.gorunjinian.bbqr.FileType
import com.gorunjinian.bbqr.Header
import com.gorunjinian.bbqr.JoinError
import com.gorunjinian.bcur.ResultType
import com.gorunjinian.bcur.UR
import com.gorunjinian.bcur.URDecoder

/** How a scanned payload travelled: decides how its frames join and how a reply is formatted. */
enum class QRTransport {
    /** One plain QR. */
    SINGLE,

    /** A `p1/n <data>` animation, joined by concatenating the parts in order. */
    SIMPLE_MULTIPART,

    /** A BC-UR, single-part or fountain-coded. */
    UR,

    /** A BBQr, single- or multi-part. */
    BBQR
}

/**
 * A fully assembled scan, still in its transport form. Nothing here knows what the content means:
 * [com.gorunjinian.metrovault.domain.service.scan.ScanPayloadClassifier] decides that.
 */
sealed interface AssembledQR {
    val transport: QRTransport

    /**
     * Plain text: one QR, or the joined parts of a `p1/n` animation. [rawBytes] are ZXing's raw
     * segment bytes for a single QR (CompactSeedQR is binary and does not survive as text); null
     * for a joined animation.
     */
    class Text(
        val text: String,
        val rawBytes: ByteArray?,
        override val transport: QRTransport
    ) : AssembledQR

    /** A BC-UR of any registry type, undecoded. [isMultipart] when it arrived fountain-coded. */
    class Ur(val ur: UR, val isMultipart: Boolean) : AssembledQR {
        override val transport: QRTransport get() = QRTransport.UR
    }

    /** A joined, decompressed BBQr. */
    class BBQr(val fileType: FileType, val data: ByteArray, val numParts: Int) : AssembledQR {
        override val transport: QRTransport get() = QRTransport.BBQR
    }
}

/**
 * Joins scanned QR frames into one [AssembledQR], whatever the transport: any BC-UR type
 * (fountain codes via bcur-kotlin), BBQr (via bbqr-kotlin), `p1/n` text animations, or a single
 * plain QR.
 *
 * The first frame fixes the transport. Frames that belong to something else (another UR type, a
 * different BBQr, a stray plain QR mid-animation) are [FrameResult.Ignored] rather than restarting
 * the scan, so a camera that briefly catches a neighboring code doesn't lose progress. Call
 * [reset] to start over.
 *
 * Not thread-safe: feed frames from one coroutine. Joining (BBQr zlib, fountain decoding) can be
 * slow enough to stall the UI thread, so call it off the main thread.
 */
class QRFrameAssembler {

    /** What one frame did to the scan. */
    sealed interface FrameResult {
        /** Accepted; more frames are needed. [percent] is 0-99. */
        data class Progress(val percent: Int) : FrameResult

        /** The payload is complete. Later frames are ignored until [reset]. */
        class Complete(val payload: AssembledQR) : FrameResult

        /** Not part of this scan, or a part already counted. */
        data object Ignored : FrameResult

        /** The frames cannot be joined. The caller should [reset] and scan again. */
        data class Failed(val reason: String) : FrameResult
    }

    private var transport: QRTransport? = null
    private var completed: AssembledQR? = null

    private var urDecoder: URDecoder? = null
    private var urFramesAccepted = 0

    private var bbqrJoiner: ContinuousJoiner? = null
    private var bbqrTotal = 0
    private var bbqrPartsLeft = 0

    private val simpleParts = sortedMapOf<Int, String>()
    private var simpleTotal = 0

    /** The transport fixed by the first accepted frame; null before any. */
    val detectedTransport: QRTransport? get() = transport

    /** True once a second frame is needed: a multi-part UR, BBQr or `p1/n` scan in progress. */
    val isAnimated: Boolean
        get() = when (transport) {
            QRTransport.UR -> urFramesAccepted > 1 || (urDecoder?.expectedPartCount ?: 0) > 1
            QRTransport.BBQR -> bbqrTotal > 1
            QRTransport.SIMPLE_MULTIPART -> simpleTotal > 1
            QRTransport.SINGLE, null -> false
        }

    /** Short progress text for the viewfinder, e.g. `42% BC-UR` or `3/7 BBQr parts`. */
    val progressLabel: String
        get() = when (transport) {
            QRTransport.UR -> "${((urDecoder?.estimatedPercentComplete ?: 0.0) * 100).toInt().coerceIn(0, 100)}% BC-UR"
            QRTransport.BBQR -> "${bbqrTotal - bbqrPartsLeft}/$bbqrTotal BBQr parts"
            QRTransport.SIMPLE_MULTIPART -> "${simpleParts.size}/$simpleTotal parts"
            QRTransport.SINGLE, null -> "Scanning..."
        }

    /**
     * Feed one decoded frame.
     *
     * @param text The QR's text as ZXing decoded it
     * @param rawBytes ZXing's raw segment bytes, kept only for a single plain QR (CompactSeedQR)
     */
    fun receive(text: String, rawBytes: ByteArray? = null): FrameResult {
        if (completed != null || text.isEmpty()) return FrameResult.Ignored
        return when (transport) {
            null -> receiveFirst(text, rawBytes)
            QRTransport.UR -> receiveUr(text)
            QRTransport.BBQR -> receiveBBQr(text)
            QRTransport.SIMPLE_MULTIPART -> receiveSimple(text)
            // A single QR completes on its first frame, so a later one never reaches here.
            QRTransport.SINGLE -> FrameResult.Ignored
        }
    }

    /** Forget every frame so the next one starts a new scan. */
    fun reset() {
        transport = null
        completed = null
        urDecoder = null
        urFramesAccepted = 0
        bbqrJoiner = null
        bbqrTotal = 0
        bbqrPartsLeft = 0
        simpleParts.clear()
        simpleTotal = 0
    }

    private fun receiveFirst(text: String, rawBytes: ByteArray?): FrameResult {
        if (text.startsWith("ur:", ignoreCase = true)) {
            transport = QRTransport.UR
            urDecoder = URDecoder()
            val result = receiveUr(text)
            // A malformed first UR frame shouldn't lock the scan into UR mode.
            if (result == FrameResult.Ignored) reset()
            return result
        }
        if (text.startsWith("B$") && isBBQrHeader(text)) {
            transport = QRTransport.BBQR
            bbqrJoiner = ContinuousJoiner()
            val result = receiveBBQr(text)
            if (result == FrameResult.Ignored) reset()
            return result
        }
        SIMPLE_FRAME.matchEntire(text)?.let { match ->
            val part = match.groupValues[1].toInt()
            val total = match.groupValues[2].toInt()
            if (total >= 1 && part in 1..total) {
                transport = QRTransport.SIMPLE_MULTIPART
                simpleTotal = total
                return receiveSimple(text)
            }
        }
        transport = QRTransport.SINGLE
        return complete(AssembledQR.Text(text, rawBytes, QRTransport.SINGLE))
    }

    private fun receiveUr(text: String): FrameResult {
        if (!text.startsWith("ur:", ignoreCase = true)) return FrameResult.Ignored
        val decoder = urDecoder ?: return FrameResult.Ignored
        // False for a different UR type or a part that added nothing new; throws on bad bytewords.
        val accepted = try {
            decoder.receivePart(text)
        } catch (_: Exception) {
            false
        }
        if (!accepted) return FrameResult.Ignored
        urFramesAccepted++

        val result = decoder.result ?: return FrameResult.Progress(
            (decoder.estimatedPercentComplete * 100).toInt().coerceIn(0, 99)
        )
        val ur = result.ur
        return if (result.type == ResultType.SUCCESS && ur != null) {
            complete(AssembledQR.Ur(ur, isMultipart = urFramesAccepted > 1 || decoder.expectedPartCount > 1))
        } else {
            FrameResult.Failed(result.error ?: "The BC-UR frames could not be joined")
        }
    }

    private fun receiveBBQr(text: String): FrameResult {
        if (!text.startsWith("B$")) return FrameResult.Ignored
        val joiner = bbqrJoiner ?: return FrameResult.Ignored
        return try {
            when (val result = joiner.addPart(text)) {
                is ContinuousJoinResult.NotStarted -> FrameResult.Ignored
                is ContinuousJoinResult.InProgress -> {
                    if (bbqrTotal == 0) {
                        // First frame: the joiner has counted it already.
                        bbqrTotal = result.partsLeft + 1
                    } else if (result.partsLeft == bbqrPartsLeft) {
                        return FrameResult.Ignored // A part we already had
                    }
                    bbqrPartsLeft = result.partsLeft
                    FrameResult.Progress(((bbqrTotal - bbqrPartsLeft) * 100 / bbqrTotal).coerceIn(0, 99))
                }
                is ContinuousJoinResult.Complete -> {
                    if (bbqrTotal == 0) bbqrTotal = 1
                    bbqrPartsLeft = 0
                    complete(AssembledQR.BBQr(result.joined.fileType, result.joined.data, bbqrTotal))
                }
            }
        } catch (e: JoinError.DuplicatePartWrongContent) {
            FrameResult.Failed("Two BBQr frames claim the same part with different content")
        } catch (e: DecodeError) {
            FrameResult.Failed("The BBQr data could not be decoded: ${e.message}")
        } catch (_: Exception) {
            // A different BBQr (header mismatch) or a malformed part
            FrameResult.Ignored
        }
    }

    private fun receiveSimple(text: String): FrameResult {
        val match = SIMPLE_FRAME.matchEntire(text) ?: return FrameResult.Ignored
        val part = match.groupValues[1].toInt()
        val total = match.groupValues[2].toInt()
        // Frames of another animation (different length) don't belong to this one.
        if (total != simpleTotal || part !in 1..total) return FrameResult.Ignored
        val data = match.groupValues[3]

        val existing = simpleParts[part]
        if (existing != null) {
            return if (existing == data) {
                FrameResult.Ignored
            } else {
                FrameResult.Failed("Two frames claim part $part/$total with different content")
            }
        }
        simpleParts[part] = data

        if (simpleParts.size < total) {
            return FrameResult.Progress((simpleParts.size * 100 / total).coerceIn(0, 99))
        }
        return complete(
            AssembledQR.Text(simpleParts.values.joinToString(""), null, QRTransport.SIMPLE_MULTIPART)
        )
    }

    private fun complete(payload: AssembledQR): FrameResult {
        completed = payload
        return FrameResult.Complete(payload)
    }

    private fun isBBQrHeader(text: String): Boolean =
        try {
            Header.parse(text)
            true
        } catch (_: Exception) {
            false
        }

    private companion object {
        /** `p<part>/<total> <data>`, the plain animated format some wallets use for base64 PSBTs. */
        val SIMPLE_FRAME = Regex("^p(\\d{1,4})/(\\d{1,4}) (.*)$", RegexOption.DOT_MATCHES_ALL)
    }
}
