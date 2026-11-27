package com.gorunjinian.metrovault.scan

import com.gorunjinian.bbqr.FileType
import com.gorunjinian.bbqr.SplitOptions
import com.gorunjinian.bbqr.SplitResult
import com.gorunjinian.bbqr.Version
import com.gorunjinian.metrovault.domain.service.scan.CompletedScan
import com.gorunjinian.metrovault.domain.service.scan.InvalidKind
import com.gorunjinian.metrovault.domain.service.scan.ScanSession
import com.gorunjinian.metrovault.domain.service.scan.ScanSession.Update
import com.gorunjinian.metrovault.domain.service.scan.ScannedPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scanner behind every screen: frames in, classified scans out, ready for the next scan. */
class ScanSessionTest {

    private val session = ScanSession()

    private fun receive(text: String): Update = session.receive(text)

    private fun complete(text: String): CompletedScan {
        val update = receive(text)
        assertTrue("expected a finished scan, got $update", update is Update.Complete)
        return (update as Update.Complete).scan
    }

    private fun bbqrParts(text: String): List<String> {
        val parts = SplitResult.fromData(
            text.toByteArray(), FileType.UnicodeText, SplitOptions(minSplitNumber = 3, maxVersion = Version.V40)
        ).parts
        assertTrue("test needs several parts, got ${parts.size}", parts.size >= 3)
        return parts
    }

    @Test
    fun aPlainQrKeepsItsExactText() {
        val scan = complete("  hello world \n")

        assertEquals(ScannedPayload.Text("  hello world \n"), scan.payload)
        assertEquals("  hello world \n", scan.text)
    }

    @Test
    fun anAnimatedQrReportsProgressThenItsPayload() {
        val message = "signmessage m/84h/0h/0h/0/5 ascii:" + (1..400).joinToString(" ") { "line $it" }
        val parts = bbqrParts(message)

        val first = receive(parts[0])
        assertTrue("$first", first is Update.Progress)
        assertEquals("1/${parts.size} BBQr parts", (first as Update.Progress).label)
        assertEquals(Update.Ignored, receive(parts[0]))
        parts.drop(1).dropLast(1).forEach { assertTrue(receive(it) is Update.Progress) }

        val scan = complete(parts.last())
        assertTrue(scan.payload is ScannedPayload.SignRequest)
        assertNull("only a plain QR has its text", scan.text)
    }

    @Test
    fun theNextFrameAfterAScanStartsANewOne() {
        assertEquals("first", complete("first").text)
        assertEquals("second", complete("second").text)
    }

    @Test
    fun framesThatContradictEachOtherFinishAsUnreadableAndTheScanStartsOver() {
        receive("p1/2 AAA")
        val invalid = complete("p1/2 XXX").payload as ScannedPayload.Invalid
        assertEquals(InvalidKind.UNREADABLE, invalid.kind)

        receive("p1/2 hello ")
        assertEquals(ScannedPayload.Text("hello world"), complete("p2/2 world").payload)
    }

    @Test
    fun resetForgetsThePartsReceivedSoFar() {
        receive("p1/2 AAA")
        session.reset()

        assertEquals("plain", complete("plain").text)
    }
}
