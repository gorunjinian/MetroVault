package com.gorunjinian.metrovault.lib.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet.hardened
import com.gorunjinian.metrovault.lib.bitcoin.crypto.Pack
import com.gorunjinian.metrovault.lib.bitcoin.io.ByteArrayInput
import com.gorunjinian.metrovault.lib.bitcoin.io.ByteArrayOutput
import com.gorunjinian.metrovault.lib.bitcoin.io.Output
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic

/**
 * see https://github.com/bitcoin/bips/blob/master/bip-0032.mediawiki
 */
@Suppress("LocalVariableName", "ConstPropertyName")
object DeterministicWallet {
    const val hardenedKeyIndex: Long = 0x80000000L

    @JvmStatic
    fun hardened(index: Long): Long = hardenedKeyIndex + index

    @JvmStatic
    fun isHardened(index: Long): Boolean = index >= hardenedKeyIndex

    data class ExtendedPrivateKey(
        @JvmField val secretkeybytes: ByteVector32,
        @JvmField val chaincode: ByteVector32,
        @JvmField val depth: Int,
        @JvmField val path: KeyPath,
        @JvmField val parent: Long
    ) {
        init {
            require(Crypto.isPrivKeyValid(secretkeybytes.toByteArray())) { "private key is invalid" }
            require(depth != 0 || parent == 0L) { "zero depth with non-zero parent fingerprint" }
            require(depth != 0 || path.lastChildNumber == 0L) { "zero depth with non-zero child number" }
        }

        val privateKey: PrivateKey get() = PrivateKey(secretkeybytes)
        val publicKey: PublicKey get() = privateKey.publicKey()
        val extendedPublicKey: ExtendedPublicKey get() = ExtendedPublicKey(publicKey.value, chaincode, depth = depth, path = path, parent = parent)

        /**
         * @param index  index of the child key
         * @return the derived private key at the specified index
         */
        fun derivePrivateKey(index: Long): ExtendedPrivateKey {
            val I = if (isHardened(index)) {
                val data = arrayOf(0.toByte()).toByteArray() + secretkeybytes.toByteArray() + Pack.writeInt32BE(index.toInt())
                Crypto.hmac512(chaincode.toByteArray(), data)
            } else {
                val data = extendedPublicKey.publickeybytes.toByteArray() + Pack.writeInt32BE(index.toInt())
                Crypto.hmac512(chaincode.toByteArray(), data)
            }
            val IL = I.take(32).toByteArray()
            val IR = I.takeLast(32).toByteArray()
            require(Crypto.isPrivKeyValid(IL)) { "cannot generate child private key: IL is invalid" }

            val key = PrivateKey(IL) + privateKey
            require(Crypto.isPrivKeyValid(key.value.toByteArray())) { "cannot generate child private key: resulting private key is invalid" }
            return ExtendedPrivateKey(
                secretkeybytes = key.value,
                chaincode = IR.byteVector32(),
                depth = depth + 1,
                path = path.derive(index),
                parent = fingerprint()
            )
        }

        fun derivePrivateKey(chain: List<Long>): ExtendedPrivateKey = chain.fold(this) { k, i -> k.derivePrivateKey(i) }

        fun derivePrivateKey(keyPath: KeyPath): ExtendedPrivateKey = derivePrivateKey(keyPath.path)

        fun derivePrivateKey(keyPath: String): ExtendedPrivateKey = derivePrivateKey(KeyPath.fromPath(keyPath))

        fun encode(testnet: Boolean): String = this.encode(if (testnet) tprv else xprv)

        fun encode(prefix: Int): String {
            val out = ByteArrayOutput()
            out.write(depth)
            Pack.writeInt32BE(parent.toInt(), out)
            Pack.writeInt32BE(path.lastChildNumber.toInt(), out)
            out.write(chaincode.toByteArray())
            out.write(0)
            out.write(secretkeybytes.toByteArray())
            val buffer = out.toByteArray()
            return Base58Check.encode(prefix, buffer)
        }

        fun fingerprint(): Long = extendedPublicKey.fingerprint()

        /**
         * We avoid accidentally logging extended private keys.
         * You should use an explicit method if you want to convert the extended private key to a string representation.
         */
        override fun toString(): String = "<extended_private_key>"

        companion object {
            @JvmStatic
            fun decode(input: String, parentPath: KeyPath = KeyPath.empty): Pair<Int, ExtendedPrivateKey> {
                val (prefix, bin) = Base58Check.decodeWithIntPrefix(input)
                require(prefix == xprv || prefix == yprv || prefix == zprv || prefix == tprv || prefix == uprv || prefix == vprv) { "invalid prefix" }
                val bis = ByteArrayInput(bin)
                val depth = bis.read()
                val parent = Pack.int32BE(bis).toLong() and 0xffffffff
                val childNumber = Pack.int32BE(bis).toLong() and 0xffffffff
                val chaincode = ByteArray(32)
                bis.read(chaincode, 0)
                require(bis.read() == 0)
                val secretKeyBytes = ByteArray(32)
                bis.read(secretKeyBytes, 0)
                return Pair(prefix, ExtendedPrivateKey(secretKeyBytes.byteVector32(), chaincode.byteVector32(), depth, parentPath.derive(childNumber), parent))
            }
        }
    }

    data class ExtendedPublicKey(
        @JvmField val publickeybytes: ByteVector,
        @JvmField val chaincode: ByteVector32,
        @JvmField val depth: Int,
        @JvmField val path: KeyPath,
        @JvmField val parent: Long
    ) {
        init {
            require(publickeybytes.size() == 33)
            require(Crypto.isPubKeyValid(publickeybytes.toByteArray())) { "public key is invalid" }
            require(depth != 0 || parent == 0L) { "zero depth with non-zero parent fingerprint" }
            require(depth != 0 || path.lastChildNumber == 0L) { "zero depth with non-zero child number" }
        }

        val publicKey: PublicKey get() = PublicKey(publickeybytes)

        /**
         * @param index  index of the child key
         * @return the derived public key at the specified index
         */
        fun derivePublicKey(index: Long): ExtendedPublicKey {
            require(!isHardened(index)) { "Cannot derive public keys from public hardened keys" }

            val I = Crypto.hmac512(
                chaincode.toByteArray(), publickeybytes.toByteArray() + Pack.writeInt32BE(index.toInt())
            )
            val IL = I.take(32).toByteArray()
            val IR = I.takeLast(32).toByteArray()
            require(Crypto.isPrivKeyValid(IL)) { "cannot generate child public key: IL is invalid" }

            val Ki = PrivateKey(IL).publicKey() + publicKey
            require(Crypto.isPubKeyValid(Ki.value.toByteArray())) { "cannot generate child public key: resulting public key is invalid" }
            return ExtendedPublicKey(
                publickeybytes = Ki.value,
                chaincode = IR.byteVector32(),
                depth = depth + 1,
                path = path.derive(index),
                parent = fingerprint()
            )
        }

        fun derivePublicKey(chain: List<Long>): ExtendedPublicKey = chain.fold(this) { k, i -> k.derivePublicKey(i) }

        fun derivePublicKey(keyPath: KeyPath): ExtendedPublicKey = derivePublicKey(keyPath.path)

        fun derivePublicKey(keyPath: String): ExtendedPublicKey = derivePublicKey(KeyPath.fromPath(keyPath))

        fun fingerprint(): Long = Pack.int32LE(ByteArrayInput(Crypto.hash160(publickeybytes).take(4).reversed().toByteArray())).toLong()

        fun encode(prefix: Int): String {
            val out = ByteArrayOutput()
            this.write(out)
            val buffer = out.toByteArray()
            return Base58Check.encode(prefix, buffer)
        }

        fun encode(testnet: Boolean): String = encode(if (testnet) tpub else xpub)

        fun write(out: Output) {
            out.write(depth)
            Pack.writeInt32BE(parent.toInt(), out)
            Pack.writeInt32BE(path.lastChildNumber.toInt(), out)
            out.write(chaincode.toByteArray())
            out.write(publickeybytes.toByteArray())
        }

        companion object {
            @JvmStatic
            fun decode(input: String, parentPath: KeyPath = KeyPath.empty): Pair<Int, ExtendedPublicKey> {
                val (prefix, bin) = Base58Check.decodeWithIntPrefix(input)
                require(prefix == xpub || prefix == ypub || prefix == zpub || prefix == tpub || prefix == upub || prefix == vpub) { "invalid prefix" }
                val bis = ByteArrayInput(bin)
                val depth = bis.read()
                val parent = Pack.int32BE(bis).toLong() and 0xffffffff
                val childNumber = Pack.int32BE(bis).toLong() and 0xffffffff
                val chaincode = ByteArray(32)
                bis.read(chaincode, 0)
                val publicKeyBytes = ByteArray(33)
                bis.read(publicKeyBytes, 0)
                return Pair(prefix, ExtendedPublicKey(publicKeyBytes.byteVector(), chaincode.byteVector32(), depth, parentPath.derive(childNumber), parent))
            }
        }
    }

    @JvmStatic
    fun encode(input: ExtendedPrivateKey, testnet: Boolean): String = encode(input, if (testnet) tprv else xprv)

    @JvmStatic
    fun encode(input: ExtendedPrivateKey, prefix: Int): String = input.encode(prefix)

    @JvmStatic
    fun encode(input: ExtendedPublicKey, testnet: Boolean): String = encode(input, if (testnet) tpub else xpub)

    @JvmStatic
    fun encode(input: ExtendedPublicKey, prefix: Int): String = input.encode(prefix)

    @JvmStatic
    fun write(input: ExtendedPublicKey, out: Output): Unit = input.write(out)

    /**
     * @param seed random seed
     * @return a "master" private key
     */
    @JvmStatic
    fun generate(seed: ByteArray): ExtendedPrivateKey {
        val I = Crypto.hmac512("Bitcoin seed".encodeToByteArray(), seed)
        val IL = I.take(32).toByteArray().byteVector32()
        val IR = I.takeLast(32).toByteArray().byteVector32()
        return ExtendedPrivateKey(IL, IR, depth = 0, path = KeyPath.empty, parent = 0L)
    }

    /**
     * @param seed random seed
     * @return a "master" private key
     */
    @JvmStatic
    fun generate(seed: ByteVector): ExtendedPrivateKey = generate(seed.toByteArray())

    /**
     * @param input extended private key
     * @return the public key for this private key
     */
    @JvmStatic
    fun publicKey(input: ExtendedPrivateKey): ExtendedPublicKey = input.extendedPublicKey

    /**
     * @param input extended public key
     * @return the fingerprint for this public key
     */
    @JvmStatic
    fun fingerprint(input: ExtendedPublicKey): Long = input.fingerprint()

    /**
     * @param input extended private key
     * @return the fingerprint for this private key (which is based on the corresponding public key)
     */
    @JvmStatic
    fun fingerprint(input: ExtendedPrivateKey): Long = fingerprint(publicKey(input))

    // p2pkh mainnet
    const val xprv: Int = 0x0488ade4
    const val xpub: Int = 0x0488b21e

    // p2sh-of-p2wpkh mainnet
    const val yprv: Int = 0x049d7878
    const val ypub: Int = 0x049d7cb2

    // p2wpkh mainnet
    const val zprv: Int = 0x04b2430c
    const val zpub: Int = 0x04b24746

    // p2pkh testnet
    const val tprv: Int = 0x04358394
    const val tpub: Int = 0x043587cf

    // p2sh-of-p2wpkh testnet
    const val uprv: Int = 0x044a4e28
    const val upub: Int = 0x044a5262

    // p2wpkh testnet
    const val vprv: Int = 0x045f18bc
    const val vpub: Int = 0x045f1cf6
}

data class KeyPath(@JvmField val path: List<Long>) {
    constructor(path: String) : this(computePath(path))

    val lastChildNumber: Long get() = if (path.isEmpty()) 0L else path.last()

    fun derive(number: Long): KeyPath = KeyPath(path + listOf(number))

    fun append(index: Long): KeyPath {
        return KeyPath(path + listOf(index))
    }

    fun append(indexes: List<Long>): KeyPath {
        return KeyPath(path + indexes)
    }

    fun append(that: KeyPath): KeyPath {
        return KeyPath(path + that.path)
    }

    override fun toString(): String = asString('\'')

    fun asString(hardenedSuffix: Char): String = path.map { childNumberToString(it, hardenedSuffix) }.fold("m") { a, b -> "$a/$b" }

    companion object {
        val empty: KeyPath = KeyPath(listOf())

        @JvmStatic
        fun computePath(path: String): List<Long> {
            fun toNumber(value: String): Long = if (value.last() == '\'' || value.last() == 'h') hardened(value.dropLast(1).toLong()) else value.toLong()

            val path1 = path.removePrefix("m").removePrefix("/")
            return if (path1.isEmpty()) {
                listOf()
            } else {
                path1.split('/').map { toNumber(it) }
            }
        }

        @JvmStatic
        fun fromPath(path: String): KeyPath = KeyPath(path)

        fun childNumberToString(childNumber: Long, hardenedSuffix: Char = '\''): String = if (DeterministicWallet.isHardened(childNumber)) {
            ((childNumber - DeterministicWallet.hardenedKeyIndex).toString() + hardenedSuffix)
        } else {
            childNumber.toString()
        }
    }
}