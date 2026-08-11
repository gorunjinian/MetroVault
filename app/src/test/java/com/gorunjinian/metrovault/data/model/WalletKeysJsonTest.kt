package com.gorunjinian.metrovault.data.model

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Round-trip tests for the hand-rolled [WalletKeys] JSON codec.
 *
 * This record holds the mnemonic and BIP39 seed, so a codec that silently drops a field loses key
 * material that only the user's physical backup can restore.
 */
class WalletKeysJsonTest {

    private val fullyPopulated = WalletKeys(
        keyId = "3f2c1b0a-0000-4000-8000-000000000001",
        mnemonic = "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon about",
        bip39Seed = "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1" +
            "9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
        fingerprint = "73c5da0a",
        label = "My Main Seed"
    )

    private fun roundTrip(k: WalletKeys) = WalletKeys.fromJson(k.toJson())

    @Test
    fun `fully populated key survives a round-trip unchanged`() {
        assertEquals(fullyPopulated, roundTrip(fullyPopulated))
    }

    @Test
    fun `key with a defaulted label survives a round-trip unchanged`() {
        val k = fullyPopulated.copy(label = "")
        assertEquals(k, roundTrip(k))
    }

    @Test
    fun `a key JSON survives two round-trips identically`() {
        val once = fullyPopulated.toJson()
        val twice = WalletKeys.fromJson(once).toJson()
        assertEquals(JSONObject(once).toString(), JSONObject(twice).toString())
    }

    @Test
    fun `fingerprint is normalized to lowercase on read`() {
        // Fingerprints are matched against cosigner fingerprints, which are normalized the same
        // way. A round-trip is deliberately NOT the identity for an uppercase fingerprint.
        assertEquals("73c5da0a", roundTrip(fullyPopulated.copy(fingerprint = "73C5DA0A")).fingerprint)
    }

    @Test
    fun `label is written unconditionally so an absent label reads as empty`() {
        // Unlike WalletMetadata, this codec has no conditional writes — label is always present.
        val json = JSONObject(fullyPopulated.toJson())
        assertEquals(5, json.length())
        json.remove("label")
        assertEquals("", WalletKeys.fromJson(json.toString()).label)
    }

    @Test
    fun `a record missing key material fails loudly rather than defaulting`() {
        // keyId, mnemonic, bip39Seed and fingerprint use getString, so a truncated record throws
        // instead of producing a WalletKeys with empty key material that would derive junk
        // addresses. Pinned deliberately: silent defaults here would be a correctness hazard.
        for (field in listOf("keyId", "mnemonic", "bip39Seed", "fingerprint")) {
            val json = JSONObject(fullyPopulated.toJson()).apply { remove(field) }.toString()
            assertThrows("missing $field should throw", JSONException::class.java) {
                WalletKeys.fromJson(json)
            }
        }
    }

    @Test
    fun `unknown fields are ignored`() {
        val json = JSONObject(fullyPopulated.toJson()).put("someFutureField", 1).toString()
        assertEquals(fullyPopulated, WalletKeys.fromJson(json))
    }

    @Test
    fun `labels with JSON metacharacters survive a round-trip`() {
        val nasty = """Key "1" \ {"keyId":"injected"} 日本語"""
        assertEquals(nasty, roundTrip(fullyPopulated.copy(label = nasty)).label)
    }
}
