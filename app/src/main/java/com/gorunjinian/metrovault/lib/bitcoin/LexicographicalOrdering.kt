package com.gorunjinian.metrovault.lib.bitcoin

import kotlin.jvm.JvmStatic

/**
 * Lexicographical Ordering of Transaction Inputs and Outputs.
 * See https://github.com/bitcoin/bips/blob/master/bip-0069.mediawiki
 */
object LexicographicalOrdering {
    private tailrec fun isLessThanInternal(a: ByteArray, b: ByteArray): Boolean {
        return when {
            a.isEmpty() && b.isEmpty() -> false
            a.isEmpty() -> true
            b.isEmpty() -> false
            a.first() == b.first() -> isLessThanInternal(a.drop(1).toByteArray(), b.drop(1).toByteArray())
            else -> (a.first().toInt() and 0xff) < (b.first().toInt() and 0xff)
        }
    }

    @JvmStatic
    fun isLessThan(a: ByteArray, b: ByteArray): Boolean = isLessThanInternal(a, b)

    @JvmStatic
    fun isLessThan(a: ByteVector, b: ByteVector): Boolean = isLessThan(a.toByteArray(), b.toByteArray())

    @JvmStatic
    fun isLessThan(a: ByteVector32, b: ByteVector32): Boolean = isLessThan(a.toByteArray(), b.toByteArray())

    @JvmStatic
    fun isLessThan(a: OutPoint, b: OutPoint): Boolean {
        return if (a.txid == b.txid) a.index < b.index else isLessThan(a.txid.value, b.txid.value)
    }

    @JvmStatic
    fun compare(a: OutPoint, b: OutPoint): Int = if (a == b) 0 else if (isLessThan(a, b)) -1 else 1

    @JvmStatic
    fun isLessThan(a: TxIn, b: TxIn): Boolean = isLessThan(a.outPoint, b.outPoint)

    @JvmStatic
    fun compare(a: TxIn, b: TxIn): Int = if (a == b) 0 else if (isLessThan(a, b)) -1 else 1

    @JvmStatic
    fun isLessThan(a: TxOut, b: TxOut): Boolean {
        return if (a.amount == b.amount) isLessThan(a.publicKeyScript, b.publicKeyScript) else (a.amount < b.amount)
    }

    @JvmStatic
    fun compare(a: TxOut, b: TxOut): Int = if (a == b) 0 else if (isLessThan(a, b)) -1 else 1

    @JvmStatic
    fun isLessThan(a: PublicKey, b: PublicKey): Boolean = isLessThan(a.value, b.value)

    @JvmStatic
    fun isLessThan(a: XonlyPublicKey, b: XonlyPublicKey): Boolean = isLessThan(a.value, b.value)

    @JvmStatic
    fun compare(a: PublicKey, b: PublicKey): Int = if (a == b) 0 else if (isLessThan(a, b)) -1 else 1

    @JvmStatic
    fun compare(a: XonlyPublicKey, b: XonlyPublicKey): Int = if (a == b) 0 else if (isLessThan(a, b)) -1 else 1

    /**
     * @param tx input transaction
     * @return the input tx with inputs and outputs sorted in lexicographical order
     */
    @JvmStatic
    fun sort(tx: Transaction): Transaction = tx.copy(
        txIn = tx.txIn.sortedWith { a, b -> compare(a, b) },
        txOut = tx.txOut.sortedWith { a, b -> compare(a, b) }
    )
}