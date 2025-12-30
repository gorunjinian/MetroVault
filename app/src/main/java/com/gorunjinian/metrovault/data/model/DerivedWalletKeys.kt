package com.gorunjinian.metrovault.data.model

import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet

/**
 * Result of wallet key derivation operations.
 *
 * Contains the derived BIP32 keys and fingerprint needed to set up a new wallet.
 * This is the output of deriving keys from a mnemonic/seed.
 *
 * @property masterPrivateKey The BIP32 master private key derived from seed
 * @property accountPrivateKey The account-level private key (e.g., m/84'/0'/0')
 * @property accountPublicKey The account-level public key for address generation
 * @property fingerprint 8-character hex string identifying the master key (first 4 bytes of hash160)
 */
data class DerivedWalletKeys(
    val masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
    val accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
    val accountPublicKey: DeterministicWallet.ExtendedPublicKey,
    val fingerprint: String
)
