package com.gorunjinian.metrovault.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import com.gorunjinian.metrovault.domain.service.scan.CompletedScan
import com.gorunjinian.metrovault.domain.service.scan.ScanHandoff

/** Set on a screen's entry by [navigateWithScan]. */
private const val SCAN_TICKET = "scanTicket"

/**
 * Open [route] and hand its screen [scan], to use as if its own camera had read it. The scan waits
 * in [handoff]; the new entry carries only the ticket for it.
 */
fun NavHostController.navigateWithScan(
    route: String,
    scan: CompletedScan,
    handoff: ScanHandoff,
    builder: NavOptionsBuilder.() -> Unit = {}
) {
    val ticket = handoff.put(scan)
    navigate(route, builder)
    currentBackStackEntry?.savedStateHandle?.set(SCAN_TICKET, ticket)
}

/**
 * How this entry's screen takes the scan it was opened with: the first call returns it, later ones
 * return null. Null when the screen was opened without a scan.
 */
@Composable
internal fun NavBackStackEntry.rememberScanTaker(handoff: ScanHandoff): (() -> CompletedScan?)? =
    remember(this) {
        savedStateHandle.get<String>(SCAN_TICKET)?.let { ticket -> { handoff.take(ticket) } }
    }
