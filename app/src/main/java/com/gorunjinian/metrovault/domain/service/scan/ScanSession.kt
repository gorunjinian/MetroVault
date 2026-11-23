package com.gorunjinian.metrovault.domain.service.scan

import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.core.qr.AssembledQR
import com.gorunjinian.metrovault.core.qr.QRFrameAssembler
import com.gorunjinian.metrovault.core.qr.QRFrameAssembler.FrameResult

/**
 * A finished scan.
 *
 * @property payload What the scan turned out to be
 * @property text The text exactly as encoded, when the scan was a plain-text QR; null for a UR or
 *   BBQr. A message to sign must keep every character, which [payload] may have normalized away
 *   (a `bitcoin:` link becomes its address).
 */
class CompletedScan(val payload: ScannedPayload, val text: String?)

/**
 * Turns camera frames into [CompletedScan]s: joins them with [QRFrameAssembler], then classifies
 * the result with [ScanPayloadClassifier]. Every scanning screen runs on this.
 *
 * The frames are dropped as soon as a scan finishes, so a scanned seed isn't kept here, and the
 * next frame starts a new scan. Joining can be slow: call [receive] off the main thread.
 */
class ScanSession {

    /** What one frame did to the scan. */
    sealed interface Update {
        /** Accepted; more frames are needed. [label] is the progress in words, e.g. `3/7 BBQr parts`. */
        data class Progress(val percent: Int, val label: String) : Update

        /** The scan finished. Frames that can't be joined finish it too, as [ScannedPayload.Invalid]. */
        class Complete(val scan: CompletedScan) : Update

        /** Not part of this scan, or a part already counted. */
        data object Ignored : Update
    }

    private val assembler = QRFrameAssembler()

    @Synchronized
    fun receive(text: String, byteSegment: ByteArray? = null): Update = try {
        when (val result = assembler.receive(text, byteSegment)) {
            is FrameResult.Progress -> Update.Progress(result.percent, assembler.progressLabel)
            is FrameResult.Ignored -> Update.Ignored
            is FrameResult.Failed -> unreadable(result.reason)
            is FrameResult.Complete -> {
                assembler.reset()
                val assembled = result.payload
                Update.Complete(
                    CompletedScan(
                        payload = ScanPayloadClassifier.classify(assembled),
                        text = (assembled as? AssembledQR.Text)?.text
                    )
                )
            }
        }
    } catch (e: Exception) {
        // Nothing should get here, but an exception would end the camera's frame loop for good.
        AppLog.e(TAG, e) { "Scan failed: ${e::class.simpleName}" }
        unreadable("This QR code couldn't be read.")
    }

    /** Forget the frames received so far. */
    @Synchronized
    fun reset() = assembler.reset()

    private fun unreadable(reason: String): Update {
        assembler.reset()
        return Update.Complete(CompletedScan(ScannedPayload.Invalid(InvalidKind.UNREADABLE, reason), text = null))
    }

    private companion object {
        const val TAG = "ScanSession"
    }
}
