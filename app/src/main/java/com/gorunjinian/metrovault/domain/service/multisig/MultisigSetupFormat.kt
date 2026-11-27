package com.gorunjinian.metrovault.domain.service.multisig

/** The text format a multisig wallet setup arrives in. */
enum class MultisigSetupFormat {
    /** An output descriptor, e.g. `wsh(sortedmulti(...))`. */
    DESCRIPTOR,

    /** A BSMS (BIP-0129) descriptor record. */
    BSMS,

    /** A ColdCard multisig setup file. */
    SETUP_FILE
}
