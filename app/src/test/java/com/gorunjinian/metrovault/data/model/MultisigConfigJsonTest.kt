package com.gorunjinian.metrovault.data.model

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for the hand-rolled [MultisigConfig] / [CosignerInfo] JSON codecs.
 *
 * These are the most fragile of the four codecs: every field is read with a hard `getString` /
 * `getInt` / `getJSONArray`, so a record missing any of them throws rather than defaulting. That
 * behavior is pinned below on purpose — a multisig config that silently loses a cosigner would
 * generate wrong addresses.
 */
class MultisigConfigJsonTest {

    private val localCosigner = CosignerInfo(
        xpub = "Zpub6xLocalCosignerKey",
        fingerprint = "1a2b3c4d",
        derivationPath = "48h/0h/0h/2h",
        isLocal = true,
        keyId = "key-a"
    )

    private val remoteCosigner = CosignerInfo(
        xpub = "Zpub6xRemoteCosignerKey",
        fingerprint = "aabbccdd",
        derivationPath = "48h/0h/0h/2h",
        isLocal = false,
        keyId = null
    )

    private val fullyPopulated = MultisigConfig(
        m = 2,
        n = 3,
        cosigners = listOf(localCosigner, remoteCosigner),
        localKeyFingerprints = listOf("1a2b3c4d"),
        scriptType = MultisigScriptType.P2SH_P2WSH,
        rawDescriptor = "sh(wsh(sortedmulti(2,[1a2b3c4d/48h/0h/0h/2h]Zpub6xLocalCosignerKey/0/*)))"
    )

    private fun roundTrip(c: MultisigConfig) = MultisigConfig.fromJson(c.toJson())

    // ==================== MultisigConfig ====================

    @Test
    fun `fully populated config survives a round-trip unchanged`() {
        assertEquals(fullyPopulated, roundTrip(fullyPopulated))
    }

    @Test
    fun `a config JSON survives two round-trips identically`() {
        val once = fullyPopulated.toJson().toString()
        val twice = MultisigConfig.fromJson(JSONObject(once)).toJson().toString()
        assertEquals(once, twice)
    }

    @Test
    fun `every script type survives a round-trip`() {
        // scriptType is written as an enum name and read back with valueOf, so a renamed constant
        // would break every stored multisig wallet.
        for (type in MultisigScriptType.entries) {
            val parsed = roundTrip(fullyPopulated.copy(scriptType = type))
            assertEquals(type, parsed.scriptType)
        }
    }

    @Test
    fun `cosigner order is preserved`() {
        // Address derivation for a non-sorted descriptor depends on cosigner order.
        val reversed = fullyPopulated.copy(cosigners = listOf(remoteCosigner, localCosigner))
        assertEquals(
            listOf("aabbccdd", "1a2b3c4d"),
            roundTrip(reversed).cosigners.map { it.fingerprint }
        )
    }

    @Test
    fun `local key fingerprints are normalized to lowercase on read`() {
        val c = fullyPopulated.copy(localKeyFingerprints = listOf("1A2B3C4D", "AABBCCDD"))
        assertEquals(listOf("1a2b3c4d", "aabbccdd"), roundTrip(c).localKeyFingerprints)
    }

    @Test
    fun `empty cosigner and fingerprint lists survive a round-trip`() {
        // A watch-only multisig import can legitimately have no local keys.
        val c = fullyPopulated.copy(cosigners = emptyList(), localKeyFingerprints = emptyList())
        assertEquals(c, roundTrip(c))
    }

    @Test
    fun `a config missing any field fails loudly rather than defaulting`() {
        for (field in listOf("m", "n", "cosigners", "localKeyFingerprints", "scriptType", "rawDescriptor")) {
            val json = fullyPopulated.toJson().apply { remove(field) }
            assertThrows("missing $field should throw", JSONException::class.java) {
                MultisigConfig.fromJson(json)
            }
        }
    }

    @Test
    fun `an unrecognized script type throws rather than silently defaulting`() {
        // Contrast with MultisigScriptType.fromDescriptor, which defaults to P2WSH. The codec path
        // deliberately does not: a stored wallet with an unknown script type must not quietly
        // become P2WSH and derive the wrong addresses.
        val json = fullyPopulated.toJson().put("scriptType", "P2TR_MULTISIG")
        assertThrows(IllegalArgumentException::class.java) { MultisigConfig.fromJson(json) }
    }

    @Test
    fun `unknown fields are ignored`() {
        val json = fullyPopulated.toJson().put("someFutureField", "value")
        assertEquals(fullyPopulated, MultisigConfig.fromJson(json))
    }

    // ==================== CosignerInfo ====================

    @Test
    fun `cosigner with a keyId survives a round-trip unchanged`() {
        assertEquals(localCosigner, CosignerInfo.fromJson(localCosigner.toJson()))
    }

    @Test
    fun `cosigner without a keyId omits the field and reads back null`() {
        val json = remoteCosigner.toJson()
        assertTrue(!json.has("keyId"))
        assertEquals(remoteCosigner, CosignerInfo.fromJson(json))
        assertNull(CosignerInfo.fromJson(json).keyId)
    }

    @Test
    fun `an empty cosigner keyId reads back as null`() {
        // takeIf { it.isNotEmpty() } collapses "" to null, so an empty string can never be stored
        // as a key reference. Pinned because callers null-check rather than isEmpty-check.
        val json = localCosigner.toJson().put("keyId", "")
        assertNull(CosignerInfo.fromJson(json).keyId)
    }

    @Test
    fun `cosigner fingerprint is normalized to lowercase on read`() {
        val json = localCosigner.toJson().put("fingerprint", "1A2B3C4D")
        assertEquals("1a2b3c4d", CosignerInfo.fromJson(json).fingerprint)
    }

    @Test
    fun `an absent cosigner isLocal defaults to false`() {
        // optBoolean-backed: the safe default is "we do not hold this key".
        val json = localCosigner.toJson().apply { remove("isLocal") }
        assertEquals(false, CosignerInfo.fromJson(json).isLocal)
    }

    @Test
    fun `a cosigner missing xpub, fingerprint or derivationPath fails loudly`() {
        for (field in listOf("xpub", "fingerprint", "derivationPath")) {
            val json = localCosigner.toJson().apply { remove(field) }
            assertThrows("missing $field should throw", JSONException::class.java) {
                CosignerInfo.fromJson(json)
            }
        }
    }

    // ==================== Nesting ====================

    @Test
    fun `a config nested in wallet metadata survives the outer round-trip`() {
        val metadata = WalletMetadata(
            id = "ms-1",
            name = "2of3",
            derivationPath = "m/48h/0h/0h/2h",
            masterFingerprint = "1a2b3c4d",
            hasPassphrase = false,
            createdAt = 1L,
            isMultisig = true,
            multisigConfig = fullyPopulated
        )
        assertEquals(fullyPopulated, WalletMetadata.fromJson(metadata.toJson()).multisigConfig)
    }

    @Test
    fun `cosigners are stored as objects rather than as strings`() {
        // JSONArray(Collection) wraps each element; if the cosigner JSONObjects were ever
        // stringified instead, fromJson's getJSONObject would throw. Pin the on-disk shape.
        val array = fullyPopulated.toJson().get("cosigners")
        assertTrue("cosigners should be a JSONArray, was ${array::class.java}", array is JSONArray)
        assertTrue((array as JSONArray).get(0) is JSONObject)
    }
}
