package com.gorunjinian.metrovault.feature.settings

import com.gorunjinian.metrovault.data.model.WalletKeys
import com.gorunjinian.metrovault.data.model.WalletMetadata

/** A saved key with the names of the wallets using it, in Wallets-screen order. */
internal data class SavedKeyEntry(
    val key: WalletKeys,
    val walletNames: List<String>
)

/**
 * Orders the vault's [keys] to match the Wallets screen, so a key sits where its wallet does.
 * Keys are stored in an unordered set, so without this their order is arbitrary.
 *
 * - [wallets] must be in display order. Each key goes at the position of the first wallet that
 *   uses it; a key shared by several wallets appears once.
 * - A multisig wallet places its local keys in its descriptor's cosigner order, followed by any
 *   key it references that no cosigner points to.
 * - Keys no wallet uses go last, by label: numbered default labels first ("Key 2" before
 *   "Key 10"), then custom labels alphabetically.
 */
internal fun orderKeysByWallets(keys: List<WalletKeys>, wallets: List<WalletMetadata>): List<SavedKeyEntry> {
    val keysById = keys.associateBy { it.keyId }
    val walletNamesByKey = LinkedHashMap<String, MutableList<String>>()

    for (wallet in wallets) {
        for (keyId in keyIdsInOrder(wallet)) {
            if (keyId in keysById) {
                walletNamesByKey.getOrPut(keyId) { mutableListOf() }.add(wallet.name)
            }
        }
    }

    val used = walletNamesByKey.map { (keyId, names) -> SavedKeyEntry(keysById.getValue(keyId), names) }
    val unused = keys
        .filter { it.keyId !in walletNamesByKey }
        .sortedWith(unusedKeyOrder)
        .map { SavedKeyEntry(it, emptyList()) }
    return used + unused
}

/** The wallet's own key references, multisig ones in cosigner order, without duplicates. */
private fun keyIdsInOrder(wallet: WalletMetadata): List<String> {
    val cosigners = wallet.multisigConfig?.cosigners
    if (!wallet.isMultisig || cosigners == null) return wallet.keyIds.distinct()
    val fromCosigners = cosigners.mapNotNull { it.keyId }.filter { it in wallet.keyIds }
    return (fromCosigners + wallet.keyIds).distinct()
}

private val DEFAULT_LABEL = Regex("^Key (\\d+)$")

private val unusedKeyOrder: Comparator<WalletKeys> =
    compareBy<WalletKeys> { DEFAULT_LABEL.find(it.label)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE }
        .thenBy { it.label.lowercase() }
        .thenBy { it.keyId }
