package com.gorunjinian.metrovault.domain.manager

import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.data.model.AddressStartIndex
import com.gorunjinian.metrovault.data.model.WalletMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Manages wallet account operations.
 * Handles adding, removing, switching, and renaming accounts within a wallet.
 * 
 * Extracted from Wallet.kt to follow single responsibility principle.
 */
class WalletAccountManager(
    private val secureStorage: SecureStorage
) {
    companion object {
        private const val TAG = "WalletAccountManager"
    }

    /**
     * Add a new account number to a wallet.
     * 
     * @return false if already exists or storage fails
     */
    suspend fun addAccount(
        walletId: String,
        accountNumber: Int,
        isDecoyMode: Boolean,
        walletList: MutableList<WalletMetadata>,
        walletListLock: Any,
        walletsFlow: MutableStateFlow<List<WalletMetadata>>
    ): Boolean = withContext(Dispatchers.IO) {
        val metadata = synchronized(walletListLock) {
            walletList.find { it.id == walletId }
        } ?: return@withContext false

        if (metadata.accounts.contains(accountNumber)) return@withContext false

        val updated = metadata.copy(
            accounts = (metadata.accounts + accountNumber).sorted()
        )

        if (secureStorage.updateWalletMetadata(updated, isDecoyMode)) {
            synchronized(walletListLock) {
                val idx = walletList.indexOfFirst { it.id == walletId }
                if (idx >= 0) {
                    walletList[idx] = updated
                    walletsFlow.value = walletList.toList()
                }
            }
            AppLog.d(TAG) { "Added account $accountNumber" }
            true
        } else false
    }

    /**
     * Remove an account from a wallet.
     * 
     * @return false if: doesn't exist, is currently active, is the last account, or storage fails
     */
    suspend fun removeAccount(
        walletId: String,
        accountNumber: Int,
        isDecoyMode: Boolean,
        walletList: MutableList<WalletMetadata>,
        walletListLock: Any,
        walletsFlow: MutableStateFlow<List<WalletMetadata>>
    ): Boolean = withContext(Dispatchers.IO) {
        val metadata = synchronized(walletListLock) {
            walletList.find { it.id == walletId }
        } ?: return@withContext false

        // Validation checks
        if (!metadata.accounts.contains(accountNumber)) return@withContext false
        if (metadata.activeAccountNumber == accountNumber) return@withContext false
        if (metadata.accounts.size <= 1) return@withContext false

        val updated = metadata.copy(
            accounts = metadata.accounts.filter { it != accountNumber }
        )

        if (secureStorage.updateWalletMetadata(updated, isDecoyMode)) {
            synchronized(walletListLock) {
                val idx = walletList.indexOfFirst { it.id == walletId }
                if (idx >= 0) {
                    walletList[idx] = updated
                    walletsFlow.value = walletList.toList()
                }
            }
            AppLog.d(TAG) { "Removed account $accountNumber" }
            true
        } else false
    }

    /**
     * Switch the active account for a wallet.
     * This will unload and reload the wallet with the new account's derivation path.
     * 
     * @param onUnloadWallet Callback to unload wallet from memory
     * @param onOpenWallet Callback to reload wallet with new derivation path
     * @return true if switch succeeded
     */
    suspend fun switchAccount(
        walletId: String,
        accountNumber: Int,
        isDecoyMode: Boolean,
        walletList: MutableList<WalletMetadata>,
        walletListLock: Any,
        walletsFlow: MutableStateFlow<List<WalletMetadata>>,
        onUnloadWallet: (String) -> Unit,
        onOpenWallet: suspend (String) -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val metadata = synchronized(walletListLock) {
            walletList.find { it.id == walletId }
        } ?: return@withContext false

        if (!metadata.accounts.contains(accountNumber)) return@withContext false
        if (metadata.activeAccountNumber == accountNumber) return@withContext true // Already active

        val updated = metadata.copy(activeAccountNumber = accountNumber)

        if (secureStorage.updateWalletMetadata(updated, isDecoyMode)) {
            synchronized(walletListLock) {
                val idx = walletList.indexOfFirst { it.id == walletId }
                if (idx >= 0) {
                    walletList[idx] = updated
                    walletsFlow.value = walletList.toList()
                }
            }
            // Reload wallet with new account path
            onUnloadWallet(walletId)
            val reloaded = onOpenWallet(walletId)
            AppLog.d(TAG) { "Switched to account $accountNumber: reload=$reloaded" }
            reloaded
        } else false
    }

    /**
     * Rename an account's display name.
     * Pass empty string or default name ("Account N") to remove custom name.
     */
    suspend fun renameAccount(
        walletId: String,
        accountNumber: Int,
        newName: String,
        isDecoyMode: Boolean,
        walletList: MutableList<WalletMetadata>,
        walletListLock: Any,
        walletsFlow: MutableStateFlow<List<WalletMetadata>>
    ): Boolean = withContext(Dispatchers.IO) {
        val metadata = synchronized(walletListLock) {
            walletList.find { it.id == walletId }
        } ?: return@withContext false

        if (!metadata.accounts.contains(accountNumber)) return@withContext false

        val defaultName = "Account $accountNumber"
        val trimmedName = newName.trim()

        val updatedNames = if (trimmedName.isEmpty() || trimmedName == defaultName) {
            // Remove custom name, use default
            metadata.accountNames - accountNumber
        } else {
            // Store custom name
            metadata.accountNames + (accountNumber to trimmedName)
        }

        val updated = metadata.copy(accountNames = updatedNames)

        if (secureStorage.updateWalletMetadata(updated, isDecoyMode)) {
            synchronized(walletListLock) {
                val idx = walletList.indexOfFirst { it.id == walletId }
                if (idx >= 0) {
                    walletList[idx] = updated
                    walletsFlow.value = walletList.toList()
                }
            }
            AppLog.d(TAG) { "Renamed account $accountNumber" }
            true
        } else false
    }

    /**
     * Addresses-screen start indices (receive, change) for a wallet's active account.
     * Unknown wallets and unset chains start at 0.
     */
    fun getAddressStartIndices(
        walletId: String?,
        walletList: List<WalletMetadata>,
        walletListLock: Any
    ): Pair<Int, Int> {
        if (walletId == null) return 0 to 0
        val metadata = synchronized(walletListLock) {
            walletList.find { it.id == walletId }
        } ?: return 0 to 0
        val account = metadata.activeAccountNumber
        return metadata.getAddressStartIndex(account, false) to metadata.getAddressStartIndex(account, true)
    }

    /**
     * Persist the Addresses-screen start index for both chains of one account.
     *
     * Inputs are normalised with [AddressStartIndex.normalize]: a value that collapses to 0 removes
     * the key rather than storing a zero, so an untouched wallet keeps an empty map. Nothing is
     * written when the result equals what is already stored.
     */
    suspend fun setAddressStartIndices(
        walletId: String,
        accountNumber: Int,
        receiveStart: Int,
        changeStart: Int,
        isDecoyMode: Boolean,
        walletList: MutableList<WalletMetadata>,
        walletListLock: Any,
        walletsFlow: MutableStateFlow<List<WalletMetadata>>
    ): Boolean = withContext(Dispatchers.IO) {
        val metadata = synchronized(walletListLock) {
            walletList.find { it.id == walletId }
        } ?: return@withContext false

        if (!metadata.accounts.contains(accountNumber)) return@withContext false

        var indices = metadata.addressStartIndices
        listOf(false to receiveStart, true to changeStart).forEach { (isChange, raw) ->
            val key = WalletMetadata.addressStartKey(accountNumber, isChange)
            val value = AddressStartIndex.normalize(raw)
            indices = if (value == 0) indices - key else indices + (key to value)
        }
        if (indices == metadata.addressStartIndices) return@withContext true

        val updated = metadata.copy(addressStartIndices = indices)

        if (secureStorage.updateWalletMetadata(updated, isDecoyMode)) {
            synchronized(walletListLock) {
                val idx = walletList.indexOfFirst { it.id == walletId }
                if (idx >= 0) {
                    walletList[idx] = updated
                    walletsFlow.value = walletList.toList()
                }
            }
            AppLog.d(TAG) { "Updated address start indices for account $accountNumber" }
            true
        } else false
    }

    /**
     * Get active account number for a wallet.
     */
    fun getActiveAccountNumber(
        walletId: String?,
        walletList: List<WalletMetadata>,
        walletListLock: Any
    ): Int {
        if (walletId == null) return 0
        return synchronized(walletListLock) {
            walletList.find { it.id == walletId }?.activeAccountNumber ?: 0
        }
    }
}
