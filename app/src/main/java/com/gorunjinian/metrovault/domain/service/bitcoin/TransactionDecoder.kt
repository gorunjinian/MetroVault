package com.gorunjinian.metrovault.domain.service.bitcoin

import com.gorunjinian.vaultovich.Bitcoin
import com.gorunjinian.vaultovich.BlockHash
import com.gorunjinian.vaultovich.OP_PUSHDATA
import com.gorunjinian.vaultovich.OP_RETURN
import com.gorunjinian.vaultovich.Script
import com.gorunjinian.vaultovich.Transaction
import com.gorunjinian.vaultovich.TxIn
import com.gorunjinian.vaultovich.TxOut
import com.gorunjinian.vaultovich.utils.Either
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Decodes a complete serialized transaction for display. Read-only: a scanned transaction is
 * shown, never signed, stored or broadcast.
 */
object TransactionDecoder {

    /**
     * @param hex The serialized transaction, in either case
     * @param chainHash The chain to write output addresses for, since a transaction doesn't say
     * @param findWalletAddresses Looks up every output address in the open wallet in one call and
     *   returns those it owns; the default checks no wallet
     * @return null unless [hex] is exactly one transaction with inputs and outputs
     */
    fun decode(
        hex: String,
        chainHash: BlockHash,
        findWalletAddresses: (Set<String>) -> Map<String, AddressCheckResult> = { emptyMap() }
    ): TransactionDetails? {
        val bytes = hexToBytes(hex.trim()) ?: return null
        val tx = runCatching { Transaction.read(bytes) }.getOrNull() ?: return null
        // Trailing bytes or a second transaction would otherwise go unnoticed
        if (tx.txIn.isEmpty() || tx.txOut.isEmpty() || !Transaction.write(tx).contentEquals(bytes)) return null

        val destinations = tx.txOut.map { destination(it, chainHash) }
        val addresses = destinations.mapNotNullTo(mutableSetOf()) { (it as? OutputDestination.Address)?.address }
        val owned = if (addresses.isEmpty()) emptyMap() else findWalletAddresses(addresses)
        val weight = tx.weight()

        return TransactionDetails(
            txid = tx.txid.toString(),
            version = tx.version,
            lockTime = lockTime(tx),
            signalsRbf = tx.txIn.any { it.sequence < RBF_SEQUENCE_LIMIT },
            isSegwit = tx.hasWitness,
            size = bytes.size,
            virtualSize = (weight + 3) / 4,
            weight = weight,
            inputs = tx.txIn.map { txIn ->
                TransactionInputDetails(
                    previousTxid = txIn.outPoint.txid.toString(),
                    previousIndex = txIn.outPoint.index,
                    sequence = txIn.sequence,
                    isSigned = !txIn.signatureScript.isEmpty() || txIn.hasWitness
                )
            },
            outputs = tx.txOut.mapIndexed { index, txOut ->
                val destination = destinations[index]
                TransactionOutputDetails(
                    amountSats = txOut.amount.toLong(),
                    destination = destination,
                    walletAddress = (destination as? OutputDestination.Address)?.let { owned[it.address] }
                )
            }
        )
    }

    private fun lockTime(tx: Transaction): TransactionLockTime {
        if (tx.lockTime == 0L) return TransactionLockTime.None
        val enforced = tx.txIn.any { it.sequence != TxIn.SEQUENCE_FINAL }
        return if (tx.lockTime < LOCKTIME_THRESHOLD) {
            TransactionLockTime.BlockHeight(tx.lockTime, enforced)
        } else {
            TransactionLockTime.Timestamp(tx.lockTime, enforced)
        }
    }

    /**
     * An OP_RETURN output's data is its pushes joined, skipping any other opcode (a runestone's
     * OP_13, say); it is offered as text only when nothing was skipped.
     */
    private fun destination(txOut: TxOut, chainHash: BlockHash): OutputDestination {
        val script = runCatching { Script.parse(txOut.publicKeyScript) }.getOrNull()
            ?: return OutputDestination.Script(txOut.publicKeyScript.toHex())

        if (script.firstOrNull() == OP_RETURN) {
            val elements = script.drop(1)
            val pushes = elements.filterIsInstance<OP_PUSHDATA>()
            val data = pushes.fold(ByteArray(0)) { joined, push -> joined + push.data.toByteArray() }
            val text = if (pushes.size == elements.size) readableText(data) else null
            return OutputDestination.Data(data.toHex(), text)
        }

        return when (val address = Bitcoin.addressFromPublicKeyScript(chainHash, script)) {
            is Either.Right -> OutputDestination.Address(address.value)
            is Either.Left -> OutputDestination.Script(txOut.publicKeyScript.toHex())
        }
    }

    private fun readableText(data: ByteArray): String? {
        if (data.isEmpty()) return null
        val text = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
                .toString()
        }.getOrNull() ?: return null
        return text.takeIf { decoded -> decoded.none { it.isISOControl() } }
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** At or above this a lock time is a Unix timestamp; below it, a block height. */
    private const val LOCKTIME_THRESHOLD = 500_000_000L

    /** BIP-125: a sequence below this opts the transaction in to replacement. */
    private const val RBF_SEQUENCE_LIMIT = 0xFFFFFFFEL
}
