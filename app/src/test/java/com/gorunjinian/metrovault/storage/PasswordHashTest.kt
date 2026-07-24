package com.gorunjinian.metrovault.storage

import com.gorunjinian.metrovault.core.storage.PasswordHash
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordHashTest {

    private val salt = ByteArray(32) { it.toByte() }
    private val hash = ByteArray(32) { (255 - it).toByte() }

    @Test
    fun `v2 record roundtrips through storage string`() {
        val record = PasswordHash(hash, salt, 600_000)
        val parsed = PasswordHash.fromStorageString(record.toStorageString())
        assertEquals(record, parsed)
        assertEquals(PasswordHash.VERSION_VERIFIER, parsed.version)
        assertEquals(600_000, parsed.iterations)
    }

    @Test
    fun `legacy 3-part record parses as version 1`() {
        // Format written by app versions before the verifier migration
        val b64Salt = java.util.Base64.getEncoder().encodeToString(salt)
        val b64Hash = java.util.Base64.getEncoder().encodeToString(hash)
        val parsed = PasswordHash.fromStorageString("$b64Salt:$b64Hash:210000")
        assertEquals(PasswordHash.VERSION_LEGACY, parsed.version)
        assertEquals(210_000, parsed.iterations)
        assertArrayEquals(salt, parsed.salt)
        assertArrayEquals(hash, parsed.hash)
    }

    @Test
    fun `explicit version field is preserved`() {
        val record = PasswordHash(hash, salt, 210_000, PasswordHash.VERSION_LEGACY)
        val parsed = PasswordHash.fromStorageString(record.toStorageString())
        assertEquals(PasswordHash.VERSION_LEGACY, parsed.version)
    }

    @Test
    fun `invalid formats are rejected`() {
        for (bad in listOf("", "one:two", "a:b:c:d:e", "not-a-record")) {
            val result = runCatching { PasswordHash.fromStorageString(bad) }
            assertTrue("expected failure for '$bad'", result.isFailure)
        }
    }
}
