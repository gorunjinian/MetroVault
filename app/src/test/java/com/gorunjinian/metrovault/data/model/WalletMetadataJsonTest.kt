package com.gorunjinian.metrovault.data.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for the hand-rolled [WalletMetadata] JSON codec.
 *
 * The codec is written by hand in two halves that must be kept in sync ([WalletMetadata.toJson]
 * and [WalletMetadata.Companion.fromJson]), and four fields are written only conditionally. These
 * tests pin both halves plus every conditional rule, so a field added to the data class without a
 * matching pair of codec edits fails here instead of silently dropping user data on the next save.
 */
class WalletMetadataJsonTest {

    private fun multisigConfig() = MultisigConfig(
        m = 2,
        n = 3,
        cosigners = listOf(
            CosignerInfo(
                xpub = "Zpub6xLocalCosigner",
                fingerprint = "1a2b3c4d",
                derivationPath = "48h/0h/0h/2h",
                isLocal = true,
                keyId = "key-a"
            ),
            CosignerInfo(
                xpub = "Zpub6xRemoteCosigner",
                fingerprint = "aabbccdd",
                derivationPath = "48h/0h/0h/2h",
                isLocal = false,
                keyId = null
            )
        ),
        localKeyFingerprints = listOf("1a2b3c4d"),
        scriptType = MultisigScriptType.P2SH_P2WSH,
        rawDescriptor = "sh(wsh(sortedmulti(2,[1a2b3c4d/48h/0h/0h/2h]Zpub6xLocalCosigner/0/*)))"
    )

    /**
     * Every field set to a non-default value. This is the field-drift canary: because no field
     * holds its default, a field that the codec forgets round-trips back to its default and the
     * equality assert fails.
     *
     * The combination (multisig *and* silent-payment) is not a state the app produces; it is used
     * deliberately here so that both conditional blocks are exercised in one round-trip.
     */
    private fun fullyPopulated() = WalletMetadata(
        id = "wallet-1",
        name = "Main Wallet",
        derivationPath = "m/48h/0h/0h/2h",
        masterFingerprint = "1a2b3c4d",
        hasPassphrase = true,
        createdAt = 1_700_000_000_000L,
        accounts = listOf(0, 1, 5),
        activeAccountNumber = 5,
        accountNames = mapOf(0 to "Primary", 5 to "Savings"),
        isMultisig = true,
        multisigConfig = multisigConfig(),
        keyIds = listOf("key-a", "key-b"),
        multisigVerified = true,
        multisigVerifiedDescriptorChecksum = "abcd1234",
        isSilentPayment = true,
        silentPaymentScanPubKey = "02" + "11".repeat(32),
        silentPaymentSpendPubKey = "03" + "22".repeat(32)
    )

    private fun roundTrip(m: WalletMetadata) = WalletMetadata.fromJson(m.toJson())

    // ==================== Round-trip ====================

    @Test
    fun `fully populated metadata survives a round-trip unchanged`() {
        val original = fullyPopulated()
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `minimal metadata survives a round-trip unchanged`() {
        // Only the six required constructor params; everything else defaulted.
        val original = WalletMetadata(
            id = "wallet-min",
            name = "Minimal",
            derivationPath = "m/84h/0h/0h",
            masterFingerprint = "deadbeef",
            hasPassphrase = false,
            createdAt = 42L
        )
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `nested multisig config survives a round-trip unchanged`() {
        val original = fullyPopulated()
        val parsed = roundTrip(original)
        assertEquals(original.multisigConfig, parsed.multisigConfig)
        // Spot-check through the nesting: the optional cosigner keyId is the field most likely to
        // be dropped, since CosignerInfo writes it conditionally.
        val cosigners = parsed.multisigConfig!!.cosigners
        assertEquals("key-a", cosigners[0].keyId)
        assertNull(cosigners[1].keyId)
    }

    @Test
    fun `a metadata JSON survives two round-trips identically`() {
        // Guards against a codec that is lossy only on re-save (e.g. a field read into a different
        // shape than it is written from).
        val once = fullyPopulated().toJson()
        val twice = WalletMetadata.fromJson(once).toJson()
        assertEquals(JSONObject(once).toString(), JSONObject(twice).toString())
    }

    // ==================== Conditional-write rules ====================

    @Test
    fun `empty accountNames is omitted from the JSON and reads back empty`() {
        val m = fullyPopulated().copy(accountNames = emptyMap())
        assertFalse(JSONObject(m.toJson()).has("accountNames"))
        assertEquals(emptyMap<Int, String>(), roundTrip(m).accountNames)
    }

    @Test
    fun `empty keyIds is omitted from the JSON and reads back empty`() {
        val m = fullyPopulated().copy(keyIds = emptyList())
        assertFalse(JSONObject(m.toJson()).has("keyIds"))
        assertEquals(emptyList<String>(), roundTrip(m).keyIds)
    }

    @Test
    fun `null multisigConfig is omitted from the JSON and reads back null`() {
        val m = fullyPopulated().copy(multisigConfig = null)
        assertFalse(JSONObject(m.toJson()).has("multisigConfig"))
        assertNull(roundTrip(m).multisigConfig)
    }

    @Test
    fun `multisig registration is dropped when the wallet is not multisig`() {
        // toJson gates the registration pair on `isMultisig && multisigVerified`. A single-sig
        // wallet therefore cannot carry a stale verified flag across a save.
        val m = fullyPopulated().copy(isMultisig = false, multisigVerified = true)
        val json = JSONObject(m.toJson())
        assertFalse(json.has("multisigVerified"))
        assertFalse(json.has("multisigVerifiedDescriptorChecksum"))

        val parsed = roundTrip(m)
        assertFalse(parsed.multisigVerified)
        assertEquals("", parsed.multisigVerifiedDescriptorChecksum)
    }

    @Test
    fun `multisig registration is dropped when the wallet is unverified`() {
        // The checksum is only meaningful alongside the verified flag, so an unverified wallet
        // loses any checksum it was holding. Pinned because it makes verification non-resumable:
        // re-verification must recompute the checksum rather than compare against a stored one.
        val m = fullyPopulated().copy(multisigVerified = false)
        assertFalse(JSONObject(m.toJson()).has("multisigVerifiedDescriptorChecksum"))
        assertEquals("", roundTrip(m).multisigVerifiedDescriptorChecksum)
    }

    @Test
    fun `silent-payment pubkeys are dropped when the wallet is not a silent-payment wallet`() {
        val m = fullyPopulated().copy(isSilentPayment = false)
        val json = JSONObject(m.toJson())
        assertFalse(json.has("isSilentPayment"))
        assertFalse(json.has("silentPaymentScanPubKey"))
        assertFalse(json.has("silentPaymentSpendPubKey"))

        val parsed = roundTrip(m)
        assertFalse(parsed.isSilentPayment)
        assertEquals("", parsed.silentPaymentScanPubKey)
        assertEquals("", parsed.silentPaymentSpendPubKey)
    }

    @Test
    fun `silent-payment pubkeys survive when the wallet is a silent-payment wallet`() {
        val m = WalletMetadata(
            id = "sp-1",
            name = "SP",
            derivationPath = "m/352h/0h/0h",
            masterFingerprint = "1a2b3c4d",
            hasPassphrase = false,
            createdAt = 1L,
            isSilentPayment = true,
            silentPaymentScanPubKey = "02" + "aa".repeat(32),
            silentPaymentSpendPubKey = "03" + "bb".repeat(32)
        )
        val parsed = roundTrip(m)
        assertTrue(parsed.isSilentPayment)
        assertEquals("02" + "aa".repeat(32), parsed.silentPaymentScanPubKey)
        assertEquals("03" + "bb".repeat(32), parsed.silentPaymentSpendPubKey)
    }

    // ==================== Normalization ====================

    @Test
    fun `master fingerprint is normalized to lowercase on read`() {
        // Normalization happens on read only, so a round-trip is deliberately NOT the identity for
        // an uppercase fingerprint. Cosigner matching compares these, so the case must not leak.
        val m = fullyPopulated().copy(masterFingerprint = "1A2B3C4D")
        assertEquals("1a2b3c4d", roundTrip(m).masterFingerprint)
    }

    // ==================== Defaults for absent fields ====================

    @Test
    fun `metadata written before the accounts field defaults to account zero`() {
        val legacy = """
            {"id":"w1","name":"Old","derivationPath":"m/84h/0h/0h",
             "masterFingerprint":"1a2b3c4d","hasPassphrase":false,"createdAt":1}
        """.trimIndent()
        val parsed = WalletMetadata.fromJson(legacy)
        assertEquals(listOf(0), parsed.accounts)
        assertEquals(0, parsed.activeAccountNumber)
        assertEquals(emptyMap<Int, String>(), parsed.accountNames)
        assertEquals(emptyList<String>(), parsed.keyIds)
        assertFalse(parsed.isMultisig)
        assertNull(parsed.multisigConfig)
        assertFalse(parsed.isSilentPayment)
    }

    @Test
    fun `absent createdAt falls back to the current time rather than zero`() {
        val before = System.currentTimeMillis()
        val parsed = WalletMetadata.fromJson(
            """{"id":"w1","name":"n","derivationPath":"m/84h/0h/0h","hasPassphrase":false}"""
        )
        assertTrue(
            "createdAt should fall back to now, was ${parsed.createdAt}",
            parsed.createdAt >= before
        )
        // masterFingerprint is optString-backed, so an absent one is empty rather than a throw.
        assertEquals("", parsed.masterFingerprint)
    }

    @Test
    fun `unknown fields are ignored`() {
        // Forward compatibility: a record written by a newer build must still load on an older one.
        val json = JSONObject(fullyPopulated().toJson())
            .put("someFutureField", "value")
            .put("anotherFutureObject", JSONObject().put("nested", 1))
            .toString()
        assertEquals(fullyPopulated(), WalletMetadata.fromJson(json))
    }

    // ==================== v1.x legacy migration ====================
    // Migration formula: new.hasPassphrase = old.hasPassphrase && !old.savePassphraseLocally
    // The three rows below are the migration table in the WalletMetadata KDoc.

    private fun legacyJson(hasPassphrase: Boolean, saveLocally: Boolean) = """
        {"id":"w1","name":"Legacy","derivationPath":"m/84h/0h/0h",
         "masterFingerprint":"1a2b3c4d","createdAt":1,
         "hasPassphrase":$hasPassphrase,"savePassphraseLocally":$saveLocally}
    """.trimIndent()

    @Test
    fun `legacy no passphrase saved locally migrates to opens-directly`() {
        assertFalse(WalletMetadata.fromJson(legacyJson(false, true)).hasPassphrase)
    }

    @Test
    fun `legacy passphrase saved locally migrates to opens-directly`() {
        assertFalse(WalletMetadata.fromJson(legacyJson(true, true)).hasPassphrase)
    }

    @Test
    fun `legacy passphrase not saved locally migrates to prompts-for-passphrase`() {
        assertTrue(WalletMetadata.fromJson(legacyJson(true, false)).hasPassphrase)
    }

    @Test
    fun `legacy record with an absent savePassphraseLocally value defaults to saved locally`() {
        // optBoolean("savePassphraseLocally", true) — a null value present in the record reads as
        // the safe default (treated as saved locally, i.e. no prompt).
        val json = """
            {"id":"w1","name":"Legacy","derivationPath":"m/84h/0h/0h",
             "masterFingerprint":"1a2b3c4d","createdAt":1,
             "hasPassphrase":true,"savePassphraseLocally":null}
        """.trimIndent()
        assertFalse(WalletMetadata.fromJson(json).hasPassphrase)
    }

    @Test
    fun `re-saving a migrated legacy record drops the legacy field`() {
        // The migration is one-way: once re-saved, the record is in the new format and the
        // savePassphraseLocally sniff in SecureStorage must stop matching it.
        val migrated = WalletMetadata.fromJson(legacyJson(true, false))
        val resaved = migrated.toJson()
        assertFalse(JSONObject(resaved).has("savePassphraseLocally"))
        assertTrue(WalletMetadata.fromJson(resaved).hasPassphrase)
    }

    // ==================== Values that need escaping ====================

    @Test
    fun `names with JSON metacharacters survive a round-trip`() {
        // Wallet and account names are free text; they must not be able to corrupt the record.
        val nasty = """He said "hi" \ {"id":"injected"} 日本語 	tab"""
        val m = fullyPopulated().copy(name = nasty, accountNames = mapOf(0 to nasty))
        val parsed = roundTrip(m)
        assertEquals(nasty, parsed.name)
        assertEquals(nasty, parsed.accountNames[0])
    }
}
