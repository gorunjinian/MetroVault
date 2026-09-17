package com.gorunjinian.metrovault.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InputSigningRefusal.describe] is what the confirmation screen shows when signing is refused.
 * A four-input transaction refused for one reason must read as one paragraph naming the four
 * inputs, not the same paragraph four times.
 */
class InputSigningRefusalDescribeTest {

    @Test
    fun oneReasonAcrossSeveralInputsIsOneParagraph() {
        val refusals = (0..3).map { InputSigningRefusal.MissingPreviousTransaction(it) }

        val text = InputSigningRefusal.describe(refusals)

        assertTrue(text.startsWith("Inputs #0 to #3: "))
        assertEquals("one paragraph expected:\n$text", 1, text.split("\n\n").size)
        assertTrue(text.contains("non-witness UTXOs"))
    }

    @Test
    fun nonContiguousInputsAreListed() {
        val refusals = listOf(0, 1, 3).map { InputSigningRefusal.MissingPreviousTransaction(it) }

        assertTrue(InputSigningRefusal.describe(refusals).startsWith("Inputs #0, #1 and #3: "))
    }

    @Test
    fun aSingleInputKeepsTheSingularForm() {
        val text = InputSigningRefusal.describe(listOf(InputSigningRefusal.MissingPreviousTransaction(2)))

        assertTrue(text.startsWith("Input #2: "))
        assertFalse(text.contains("Inputs"))
    }

    @Test
    fun distinctReasonsStayDistinctInFirstSeenOrder() {
        val refusals = listOf(
            InputSigningRefusal.SighashNotAllowed(0, 0x02),
            InputSigningRefusal.MissingPreviousTransaction(1),
            InputSigningRefusal.MissingPreviousTransaction(2),
            InputSigningRefusal.SighashNotAllowed(3, 0x02),
        )

        val paragraphs = InputSigningRefusal.describe(refusals).split("\n\n")

        assertEquals(2, paragraphs.size)
        assertTrue(paragraphs[0].startsWith("Inputs #0 and #3: Sighash type 0x2"))
        assertTrue(paragraphs[1].startsWith("Inputs #1 and #2: Previous transaction missing"))
    }

    @Test
    fun perInputMessageCarriesTheIndexAndTheDetail() {
        val refusal = InputSigningRefusal.Other(4, "already finalized")

        assertEquals("Input #4: Could not be signed: already finalized", refusal.message)
    }
}
