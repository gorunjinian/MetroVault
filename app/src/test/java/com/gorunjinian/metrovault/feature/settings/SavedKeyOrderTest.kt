package com.gorunjinian.metrovault.feature.settings

import com.gorunjinian.metrovault.data.model.CosignerInfo
import com.gorunjinian.metrovault.data.model.MultisigConfig
import com.gorunjinian.metrovault.data.model.MultisigScriptType
import com.gorunjinian.metrovault.data.model.WalletKeys
import com.gorunjinian.metrovault.data.model.WalletMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedKeyOrderTest {

    private fun key(id: String, label: String = id) =
        WalletKeys(keyId = id, mnemonic = "", bip39Seed = "", fingerprint = "00000000", label = label)

    private fun singleSig(name: String, keyId: String) = WalletMetadata(
        id = name, name = name, derivationPath = "m/84'/0'/0'", masterFingerprint = "00000000",
        hasPassphrase = false, createdAt = 0, keyIds = listOf(keyId)
    )

    private fun multisig(name: String, keyIds: List<String>, cosignerKeyIds: List<String?>) = WalletMetadata(
        id = name, name = name, derivationPath = "multisig/2of3", masterFingerprint = "00000000",
        hasPassphrase = false, createdAt = 0, isMultisig = true, keyIds = keyIds,
        multisigConfig = MultisigConfig(
            m = 2,
            n = cosignerKeyIds.size,
            cosigners = cosignerKeyIds.map { CosignerInfo("xpub", "00000000", "m/48'/0'/0'/2'", it != null, it) },
            localKeyFingerprints = emptyList(),
            scriptType = MultisigScriptType.entries.first(),
            rawDescriptor = ""
        )
    )

    private fun List<SavedKeyEntry>.ids() = map { it.key.keyId }

    @Test
    fun followsWalletOrderNotStorageOrder() {
        val keys = listOf(key("a"), key("b"), key("c"))
        val wallets = listOf(singleSig("W3", "c"), singleSig("W1", "a"), singleSig("W2", "b"))

        assertEquals(listOf("c", "a", "b"), orderKeysByWallets(keys, wallets).ids())
    }

    @Test
    fun sharedKeyAppearsOnceAtItsFirstWalletWithAllNames() {
        val keys = listOf(key("a"), key("b"))
        val wallets = listOf(singleSig("Spending", "b"), singleSig("Savings", "a"), singleSig("Hidden", "b"))

        val entries = orderKeysByWallets(keys, wallets)

        assertEquals(listOf("b", "a"), entries.ids())
        assertEquals(listOf("Spending", "Hidden"), entries[0].walletNames)
        assertEquals(listOf("Savings"), entries[1].walletNames)
    }

    @Test
    fun multisigPlacesItsLocalKeysInCosignerOrder() {
        val keys = listOf(key("a"), key("b"), key("c"))
        // keyIds lists b before a, but the descriptor has a's cosigner first
        val wallets = listOf(
            multisig("Vault", keyIds = listOf("b", "a"), cosignerKeyIds = listOf("a", null, "b")),
            singleSig("Spending", "c")
        )

        val entries = orderKeysByWallets(keys, wallets)

        assertEquals(listOf("a", "b", "c"), entries.ids())
        assertEquals(listOf("Vault"), entries[0].walletNames)
    }

    @Test
    fun multisigKeyWithoutCosignerReferenceStillCounts() {
        val keys = listOf(key("a"), key("b"))
        val wallets = listOf(multisig("Vault", keyIds = listOf("b", "a"), cosignerKeyIds = listOf("a", null)))

        assertEquals(listOf("a", "b"), orderKeysByWallets(keys, wallets).ids())
    }

    @Test
    fun unusedKeysGoLastWithNumberedLabelsInNumericOrder() {
        val keys = listOf(
            key("x", "Key 10"), key("y", "cold backup"), key("used", "Key 3"),
            key("z", "Key 2"), key("w", "Alpha")
        )
        val wallets = listOf(singleSig("W", "used"))

        val entries = orderKeysByWallets(keys, wallets)

        assertEquals(listOf("used", "z", "x", "w", "y"), entries.ids())
        assertEquals(emptyList<String>(), entries.last().walletNames)
    }

    @Test
    fun referencesToMissingKeysAreIgnored() {
        val keys = listOf(key("a"))
        val wallets = listOf(singleSig("Gone", "deleted"), singleSig("W", "a"))

        val entries = orderKeysByWallets(keys, wallets)

        assertEquals(listOf("a"), entries.ids())
        assertEquals(listOf("W"), entries[0].walletNames)
    }
}
