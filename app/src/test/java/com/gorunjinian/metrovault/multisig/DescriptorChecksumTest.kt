package com.gorunjinian.metrovault.multisig

import com.gorunjinian.metrovault.domain.service.multisig.BSMS
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests [BSMS.descriptorChecksum], the source of the checksum that binds a multisig wallet's
 * verified state to its descriptor.
 */
class DescriptorChecksumTest {

    private val descriptor =
        "wsh(sortedmulti(2," +
            "[1a2b3c4d/48'/0'/0'/2']xpubAAAA/0/*," +
            "[deadbeef/48'/0'/0'/2']xpubBBBB/0/*))"

    @Test
    fun deterministic() {
        assertEquals(BSMS.descriptorChecksum(descriptor), BSMS.descriptorChecksum(descriptor))
    }

    @Test
    fun stripsExistingChecksum() {
        // Adding the computed checksum back and re-hashing must yield the same value
        val checksum = BSMS.descriptorChecksum(descriptor)
        assertEquals(checksum, BSMS.descriptorChecksum("$descriptor#$checksum"))
    }

    @Test
    fun isEightChars() {
        assertEquals(8, BSMS.descriptorChecksum(descriptor).length)
    }
}
