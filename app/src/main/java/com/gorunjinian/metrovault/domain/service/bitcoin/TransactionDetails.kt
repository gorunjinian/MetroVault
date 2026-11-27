package com.gorunjinian.metrovault.domain.service.bitcoin

/**
 * A complete transaction decoded for display. The amounts it spends aren't part of it, so neither
 * its fee nor its input addresses can be shown.
 *
 * @property txid In the usual display order
 * @property signalsRbf Whether an input opts in to replacement (BIP-125)
 * @property virtualSize In vbytes: weight / 4, rounded up
 */
data class TransactionDetails(
    val txid: String,
    val version: Long,
    val lockTime: TransactionLockTime,
    val signalsRbf: Boolean,
    val isSegwit: Boolean,
    val size: Int,
    val virtualSize: Int,
    val weight: Int,
    val inputs: List<TransactionInputDetails>,
    val outputs: List<TransactionOutputDetails>
) {
    val totalOutputSats: Long get() = outputs.sumOf { it.amountSats }
}

/**
 * When the transaction may be mined. A lock time is only enforced while some input's sequence is
 * below the final value, so [BlockHeight.enforced] and [Timestamp.enforced] say whether it is.
 */
sealed interface TransactionLockTime {
    data object None : TransactionLockTime

    data class BlockHeight(val height: Long, val enforced: Boolean) : TransactionLockTime

    data class Timestamp(val epochSeconds: Long, val enforced: Boolean) : TransactionLockTime
}

/**
 * @property isSigned Whether the input carries a scriptSig or witness. The signatures aren't
 *   verified: that needs the coins it spends, which the transaction doesn't include.
 */
data class TransactionInputDetails(
    val previousTxid: String,
    val previousIndex: Long,
    val sequence: Long,
    val isSigned: Boolean
)

/**
 * @property walletAddress Where the output's address sits in the open wallet, when it pays the
 *   open wallet; null otherwise, and whenever no wallet was checked
 */
data class TransactionOutputDetails(
    val amountSats: Long,
    val destination: OutputDestination,
    val walletAddress: AddressCheckResult? = null
)

/** Where an output's coins go. */
sealed interface OutputDestination {
    data class Address(val address: String) : OutputDestination

    /**
     * An OP_RETURN output, which pays no one and carries data.
     *
     * @property text The data as text, when it is valid UTF-8 without control characters
     */
    data class Data(val hex: String, val text: String?) : OutputDestination

    /** A script with no address form, as hex. */
    data class Script(val hex: String) : OutputDestination
}
