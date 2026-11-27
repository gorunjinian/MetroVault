package com.gorunjinian.metrovault.domain.service.scan

import java.util.UUID

/**
 * Carries one [CompletedScan] from the screen that scanned it to the screen that will use it.
 *
 * Navigation writes its arguments to the saved instance state, so a scan can't travel in a route:
 * it may be a seed, or a PSBT too big to save. The screen being opened gets a ticket instead, and
 * trades it here for the scan, which only ever lives in memory.
 *
 * A scan is handed out once. One that nobody takes is dropped by the next [put], or by [clear]
 * when the app locks or returns Home.
 */
class ScanHandoff {

    private class Held(val ticket: String, val scan: CompletedScan)

    private var held: Held? = null

    /** Holds [scan] in place of any other, and returns the ticket that [take] trades for it. */
    @Synchronized
    fun put(scan: CompletedScan): String {
        // Random rather than counted: a ticket restored after the process died must match nothing.
        val ticket = UUID.randomUUID().toString()
        held = Held(ticket, scan)
        return ticket
    }

    /** The scan [ticket] was given for, or null once it was taken, replaced or cleared. */
    @Synchronized
    fun take(ticket: String): CompletedScan? {
        val scan = held?.takeIf { it.ticket == ticket }?.scan ?: return null
        held = null
        return scan
    }

    /** Drops the scan. A seed's words are Strings, so letting go of them is all that can be done. */
    @Synchronized
    fun clear() {
        held = null
    }
}
