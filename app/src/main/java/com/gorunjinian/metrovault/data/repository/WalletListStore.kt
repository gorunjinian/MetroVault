package com.gorunjinian.metrovault.data.repository

import com.gorunjinian.metrovault.data.model.WalletMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The in-memory wallet list, in display order, that the UI observes through [wallets].
 *
 * It mirrors storage but never writes to it; callers keep the two in step, normally by persisting
 * through SecureStorage first and applying the change here only once that write succeeded.
 * Each operation is a single atomic [MutableStateFlow.update], so concurrent operations never
 * lose one another's changes. A caller's find → persist → [replace] sequence is not atomic as a
 * whole, though: two such sequences racing on the same wallet can still overwrite each other.
 */
class WalletListStore {

    private val _wallets = MutableStateFlow<List<WalletMetadata>>(emptyList())
    val wallets: StateFlow<List<WalletMetadata>> = _wallets.asStateFlow()

    /** The current list. An immutable snapshot, safe to iterate while others mutate the store. */
    val current: List<WalletMetadata> get() = _wallets.value

    fun find(walletId: String): WalletMetadata? = _wallets.value.find { it.id == walletId }

    /** Replaces the whole list, e.g. after loading it from storage. */
    fun replaceAll(wallets: List<WalletMetadata>) {
        _wallets.value = wallets.toList()
    }

    fun add(metadata: WalletMetadata) {
        _wallets.update { it + metadata }
    }

    /** Swaps in [updated] for the wallet with the same id. A no-op when that wallet isn't listed. */
    fun replace(updated: WalletMetadata) {
        _wallets.update { list -> list.map { if (it.id == updated.id) updated else it } }
    }

    fun remove(walletId: String) {
        _wallets.update { list -> list.filterNot { it.id == walletId } }
    }

    /** Swaps two positions. Returns false, changing nothing, when either index is out of range. */
    fun swap(index1: Int, index2: Int): Boolean {
        var swapped = false
        _wallets.update { list ->
            swapped = index1 in list.indices && index2 in list.indices
            if (!swapped) return@update list
            list.toMutableList().apply {
                this[index1] = list[index2]
                this[index2] = list[index1]
            }
        }
        return swapped
    }

    fun clear() {
        _wallets.value = emptyList()
    }
}
