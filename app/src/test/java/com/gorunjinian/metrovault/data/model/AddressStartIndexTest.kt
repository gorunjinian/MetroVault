package com.gorunjinian.metrovault.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the two rules the Addresses screen relies on: what user input collapses to (so "reset"
 * is just "type something small"), and which batch a configured index opens the list at.
 */
class AddressStartIndexTest {

    @Test
    fun `anything below one batch normalises to the default`() {
        assertEquals(0, AddressStartIndex.normalize(-5))
        assertEquals(0, AddressStartIndex.normalize(0))
        assertEquals(0, AddressStartIndex.normalize(19))
    }

    @Test
    fun `one batch and above is kept as entered`() {
        assertEquals(20, AddressStartIndex.normalize(20))
        assertEquals(56, AddressStartIndex.normalize(56))
        assertEquals(AddressStartIndex.MAX, AddressStartIndex.normalize(AddressStartIndex.MAX))
    }

    @Test
    fun `values above the maximum are clamped`() {
        assertEquals(AddressStartIndex.MAX, AddressStartIndex.normalize(AddressStartIndex.MAX + 1))
        assertEquals(AddressStartIndex.MAX, AddressStartIndex.normalize(Int.MAX_VALUE))
    }

    @Test
    fun `batch start snaps down to the batch containing the index`() {
        assertEquals(0, AddressStartIndex.batchStart(0))
        assertEquals(0, AddressStartIndex.batchStart(19))
        assertEquals(20, AddressStartIndex.batchStart(20))
        assertEquals(20, AddressStartIndex.batchStart(25))
        assertEquals(40, AddressStartIndex.batchStart(56))
        assertEquals(999_980, AddressStartIndex.batchStart(AddressStartIndex.MAX))
    }

    @Test
    fun `batch start never goes negative`() {
        assertEquals(0, AddressStartIndex.batchStart(-1))
    }
}
