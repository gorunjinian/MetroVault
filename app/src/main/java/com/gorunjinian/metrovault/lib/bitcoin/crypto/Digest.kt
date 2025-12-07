package com.gorunjinian.metrovault.lib.bitcoin.crypto

import kotlin.jvm.JvmStatic

interface Digest {
    /**
     * return the algorithm name
     *
     * @return the algorithm name
     */
    fun getAlgorithmName(): String

    /**
     * return the size, in bytes, of the digest produced by this message digest.
     *
     * @return the size, in bytes, of the digest produced by this message digest.
     */
    fun getDigestSize(): Int

    /**
     * update the message digest with a single byte.
     *
     * @param `in` the input byte to be entered.
     */
    fun update(input: Byte)

    /**
     * update the message digest with a block of bytes.
     *
     * @param `in` the byte array containing the data.
     * @param inputOffset the offset into the byte array where the data starts.
     * @param len the length of the data.
     */
    fun update(input: ByteArray, inputOffset: Int, len: Int)

    /**
     * close the digest, producing the final digest value. The doFinal
     * call leaves the digest reset.
     *
     * @param out the array the digest is to be copied into.
     * @param outOffset the offset into the out array the digest is to start at.
     */
    fun doFinal(out: ByteArray, outOffset: Int): Int

    /**
     * reset the digest back to it's initial state.
     */
    fun reset()

    fun hash(input: ByteArray, inputOffset: Int, len: Int): ByteArray {
        reset()
        update(input, inputOffset, len)
        val output = ByteArray(getDigestSize())
        doFinal(output, 0)
        return output
    }

    fun hash(input: ByteArray): ByteArray = hash(input, 0, input.size)

    companion object {
        @JvmStatic
        fun sha1(): Digest = Sha1()
        @JvmStatic
        fun sha256(): Digest = Sha256()
        @JvmStatic
        fun sha512(): Digest = Sha512()
        @JvmStatic
        fun ripemd160(): Digest = Ripemd160()
    }
}

internal class Sha1 : Digest {
    private val digest = java.security.MessageDigest.getInstance("SHA-1")

    override fun getAlgorithmName(): String = "SHA-1"
    override fun getDigestSize(): Int = 20
    override fun update(input: Byte) = digest.update(input)
    override fun update(input: ByteArray, inputOffset: Int, len: Int) = digest.update(input, inputOffset, len)
    override fun doFinal(out: ByteArray, outOffset: Int): Int {
        val result = digest.digest()
        System.arraycopy(result, 0, out, outOffset, result.size)
        return result.size
    }
    override fun reset() = digest.reset()
}

internal class Sha256 : Digest {
    private val digest = java.security.MessageDigest.getInstance("SHA-256")

    override fun getAlgorithmName(): String = "SHA-256"
    override fun getDigestSize(): Int = 32
    override fun update(input: Byte) = digest.update(input)
    override fun update(input: ByteArray, inputOffset: Int, len: Int) = digest.update(input, inputOffset, len)
    override fun doFinal(out: ByteArray, outOffset: Int): Int {
        val result = digest.digest()
        System.arraycopy(result, 0, out, outOffset, result.size)
        return result.size
    }
    override fun reset() = digest.reset()
}

internal class Sha512 : Digest {
    private val digest = java.security.MessageDigest.getInstance("SHA-512")

    override fun getAlgorithmName(): String = "SHA-512"
    override fun getDigestSize(): Int = 64
    override fun update(input: Byte) = digest.update(input)
    override fun update(input: ByteArray, inputOffset: Int, len: Int) = digest.update(input, inputOffset, len)
    override fun doFinal(out: ByteArray, outOffset: Int): Int {
        val result = digest.digest()
        System.arraycopy(result, 0, out, outOffset, result.size)
        return result.size
    }
    override fun reset() = digest.reset()
}
