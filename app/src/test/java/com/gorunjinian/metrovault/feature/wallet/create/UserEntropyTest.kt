package com.gorunjinian.metrovault.feature.wallet.create

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserEntropyTest {

    @Test
    fun everyInputBecomesOneByteInEntryOrder() {
        assertArrayEquals(byteArrayOf(0, 1, 1, 0), encodeUserEntropy(listOf(0, 1, 1, 0)))
        assertArrayEquals(byteArrayOf(6, 1, 3), encodeUserEntropy(listOf(6, 1, 3)))
    }

    @Test
    fun noInputIsDroppedAtAnyLength() {
        // The old packing lost flips past the last multiple of 8 and an odd final die roll
        for (count in 1..20) {
            assertEquals("coin tosses: $count", count, encodeUserEntropy(List(count) { it % 2 }).size)
            assertEquals("dice rolls: $count", count, encodeUserEntropy(List(count) { it % 6 + 1 }).size)
        }
    }

    @Test
    fun fewInputsStillProduceEntropy() {
        // Fewer than 8 tosses or a single roll used to encode to nothing, so the input was ignored
        assertTrue(encodeUserEntropy(listOf(1)).isNotEmpty())
        assertTrue(encodeUserEntropy(List(7) { 0 }).isNotEmpty())
    }

    @Test
    fun trailingInputChangesTheEncoding() {
        val eightTosses = List(8) { it % 2 }
        assertFalse(encodeUserEntropy(eightTosses).contentEquals(encodeUserEntropy(eightTosses + 1)))

        val twoRolls = listOf(4, 2)
        assertFalse(encodeUserEntropy(twoRolls).contentEquals(encodeUserEntropy(twoRolls + 5)))
    }

    @Test
    fun orderMatters() {
        assertFalse(encodeUserEntropy(listOf(0, 1)).contentEquals(encodeUserEntropy(listOf(1, 0))))
    }

    @Test
    fun emptyInputEncodesToEmpty() {
        assertEquals(0, encodeUserEntropy(emptyList()).size)
    }
}
