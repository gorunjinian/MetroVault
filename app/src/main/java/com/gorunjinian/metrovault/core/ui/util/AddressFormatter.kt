package com.gorunjinian.metrovault.core.ui.util

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Shared display formatting for Bitcoin addresses.
 */
object AddressFormatter {

    private const val GROUP_SIZE = 5
    private val bold = SpanStyle(fontWeight = FontWeight.Bold)

    /**
     * Formats an address for display: [groupsBeforeEllipsis] five-character groups from the
     * start and [groupsAfterEllipsis] from the end, separated by an ellipsis
     * (`bc1qa   bcdef  ...  12345   67890   abcde` with the defaults). Addresses short enough
     * that truncating would save nothing are shown in full. [boldFirstGroup] and
     * [boldLastGroup] embolden the address's first and last five characters — the parts users
     * actually compare when verifying an address.
     */
    fun formatTruncatedAddress(
        address: String,
        groupsBeforeEllipsis: Int = 2,
        groupsAfterEllipsis: Int = 3,
        boldFirstGroup: Boolean = true,
        boldLastGroup: Boolean = true
    ): AnnotatedString {
        require(groupsBeforeEllipsis >= 1 && groupsAfterEllipsis >= 1) {
            "At least one group is required on each side of the ellipsis"
        }
        val charsBefore = groupsBeforeEllipsis * GROUP_SIZE
        val charsAfter = groupsAfterEllipsis * GROUP_SIZE
        return buildAnnotatedString {
            when {
                // Too short for distinct bold ends: one run, bold if either end would be.
                address.length <= 2 * GROUP_SIZE ->
                    appendStyled(address, bold = boldFirstGroup || boldLastGroup)

                // Short enough to show fully, unchunked, with just the bold ends.
                address.length <= charsBefore + charsAfter + 3 -> {
                    appendStyled(address.take(GROUP_SIZE), bold = boldFirstGroup)
                    append(address.substring(GROUP_SIZE, address.length - GROUP_SIZE))
                    appendStyled(address.takeLast(GROUP_SIZE), bold = boldLastGroup)
                }

                else -> {
                    appendGroups(address.take(charsBefore), boldFirst = boldFirstGroup, boldLast = false)
                    append("  ...  ")
                    appendGroups(address.takeLast(charsAfter), boldFirst = false, boldLast = boldLastGroup)
                }
            }
        }
    }

    /** Appends [s] as five-character groups separated by three spaces. */
    private fun AnnotatedString.Builder.appendGroups(s: String, boldFirst: Boolean, boldLast: Boolean) {
        val groups = s.chunked(GROUP_SIZE)
        groups.forEachIndexed { index, group ->
            if (index > 0) append("   ")
            appendStyled(
                group,
                bold = (boldFirst && index == 0) || (boldLast && index == groups.lastIndex)
            )
        }
    }

    private fun AnnotatedString.Builder.appendStyled(s: String, bold: Boolean) {
        if (bold) withStyle(this@AddressFormatter.bold) { append(s) } else append(s)
    }
}
