package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.Base58
import org.junit.Assert.assertEquals
import org.junit.Test

class Base58Test {

    @Test
    fun basicEncodeDecode() {
        assertEquals("JxF12TrwUP45BMd", Base58.encode("Hello World".encodeToByteArray()))
        assertEquals("1", Base58.encode(ByteArray(1)))
        assertEquals("1111111", Base58.encode(ByteArray(7)))
        assertEquals("", Base58.encode(ByteArray(0)))
    }
}
