package com.gorunjinian.metrovault.domain.service

/**
 * Centralized constants for wallet and address operations.
 * 
 * Avoids magic numbers scattered across services and ensures consistency.
 */
object WalletConstants {
    
    /**
     * Number of addresses to scan when looking for a matching address.
     * Used by PsbtService for fallback address-based signing.
     * Higher value = slower but covers wallets with more transaction history.
     */
    const val SINGLE_SIG_ADDRESS_GAP = 2000
    
    /**
     * Number of addresses to scan for multisig wallets.
     * Lower than single-sig because multisig derivation is computationally heavier.
     */
    const val MULTISIG_ADDRESS_GAP = 500
}
