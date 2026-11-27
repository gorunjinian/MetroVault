package com.gorunjinian.metrovault.domain

import com.gorunjinian.metrovault.domain.manager.PassphraseManager

/**
 * What asking for a wallet's passphrases takes, as [Wallet] provides it. It is an interface of its
 * own so the asking can be tested, which a [Wallet] (it needs a Context) doesn't allow.
 */
interface WalletPassphrases {
    fun needsPassphraseInput(walletId: String): Boolean
    suspend fun getKeysNeedingPassphrase(walletId: String): List<PassphraseManager.KeyPassphraseInfo>
    suspend fun getMnemonicForWallet(walletId: String): List<String>?
    suspend fun setSessionPassphrase(walletId: String, passphrase: String)
    suspend fun setSessionKeyPassphrase(keyId: String, passphrase: String)
    fun calculateFingerprint(mnemonic: List<String>, passphrase: String): String?
}
