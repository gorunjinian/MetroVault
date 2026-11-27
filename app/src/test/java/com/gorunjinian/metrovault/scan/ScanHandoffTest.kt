package com.gorunjinian.metrovault.scan

import com.gorunjinian.metrovault.domain.service.scan.CompletedScan
import com.gorunjinian.metrovault.domain.service.scan.ScanHandoff
import com.gorunjinian.metrovault.domain.service.scan.ScannedPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** A scan reaches only the screen it was meant for, once, and never outlives a lock. */
class ScanHandoffTest {

    private val handoff = ScanHandoff()

    private fun scan(text: String) = CompletedScan(ScannedPayload.Text(text), text)

    @Test
    fun aScanIsHandedOutOnce() {
        val scan = scan("a")
        val ticket = handoff.put(scan)

        assertSame(scan, handoff.take(ticket))
        assertNull(handoff.take(ticket))
    }

    @Test
    fun anotherTicketGetsNothingAndLeavesTheScanForItsOwner() {
        val scan = scan("a")
        val ticket = handoff.put(scan)

        assertNull(handoff.take("another"))
        assertNull(handoff.take(""))
        assertSame(scan, handoff.take(ticket))
    }

    @Test
    fun aNewScanReplacesOneNobodyTook() {
        val stale = handoff.put(scan("stale"))
        val fresh = handoff.put(scan("fresh"))

        // A screen still holding the old ticket must not get the new scan, or use it up
        assertNull(handoff.take(stale))
        assertEquals("fresh", handoff.take(fresh)?.text)
    }

    @Test
    fun clearingDropsTheScan() {
        val ticket = handoff.put(scan("a"))

        handoff.clear()

        assertNull(handoff.take(ticket))
    }

    @Test
    fun ticketsAreNotReusedByANewProcess() {
        // Entries are restored with their ticket after the process died; counting would repeat it.
        assertNotEquals(ScanHandoff().put(scan("a")), ScanHandoff().put(scan("a")))
    }
}
