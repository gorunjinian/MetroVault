package com.gorunjinian.metrovault.data.model

import com.gorunjinian.metrovault.core.crypto.SecureByteArray
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet

/**
 * Represents a Bitcoin wallet's in-memory runtime state.
 * All sensitive data (keys, mnemonics) exists ONLY in volatile RAM.
 * Nothing is persisted to disk except through SecureStorage.
 */
class WalletState(
    @Volatile var name: String,
    private var mnemonic: SecureByteArray?,
    val derivationPath: String,
    val fingerprint: String,
    private var masterPrivateKey: DeterministicWallet.ExtendedPrivateKey?,
    private var accountPrivateKey: DeterministicWallet.ExtendedPrivateKey?,
    private var accountPublicKey: DeterministicWallet.ExtendedPublicKey?
) {
    private val lock = Any()
    @Volatile private var isWiped = false

    fun getMasterPrivateKey(): DeterministicWallet.ExtendedPrivateKey? = synchronized(lock) {
        checkNotWiped()
        masterPrivateKey
    }

    fun getAccountPrivateKey(): DeterministicWallet.ExtendedPrivateKey? = synchronized(lock) {
        checkNotWiped()
        accountPrivateKey
    }

    fun getAccountPublicKey(): DeterministicWallet.ExtendedPublicKey? = synchronized(lock) {
        checkNotWiped()
        accountPublicKey
    }

    fun getMnemonic(): SecureByteArray? = synchronized(lock) {
        checkNotWiped()
        mnemonic
    }

    private fun checkNotWiped() {
        if (isWiped) throw IllegalStateException("WalletState has been wiped")
    }

    fun wipe() = synchronized(lock) {
        if (!isWiped) {
            mnemonic?.close()
            mnemonic = null
            masterPrivateKey = null
            accountPrivateKey = null
            accountPublicKey = null
            isWiped = true
            System.gc()
        }
    }

    fun rename(newName: String) = synchronized(lock) {
        checkNotWiped()
        this.name = newName
    }
}
