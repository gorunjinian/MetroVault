package com.gorunjinian.metrovault.lib.bitcoin

import fr.acinq.secp256k1.Hex
import fr.acinq.secp256k1.Secp256k1
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic

/**
 * A bitcoin private key.
 * A private key is valid if it is not 0 and less than the secp256k1 curve order when interpreted as an integer (most significant byte first).
 * The probability of choosing a 32-byte string uniformly at random which is an invalid private key is negligible, so this condition is not checked by default.
 * However, if you receive a private key from an external, untrusted source, you should call `isValid()` before actually using it.
 */
data class PrivateKey(@JvmField val value: ByteVector32) {
    constructor(data: ByteArray) : this(
        when (data.size) {
            32 -> ByteVector32(data.copyOf())
            33 if data.last() == 1.toByte() -> ByteVector32(data.copyOf(32))
            else -> throw RuntimeException("invalid private key length")
        }
    )

    constructor(data: ByteVector) : this(data.toByteArray())

    /**
     * A private key is valid if it is not 0 and less than the secp256k1 curve order when interpreted as an integer (most significant byte first).
     * The probability of choosing a 32-byte string uniformly at random which is an invalid private key is negligible.
     */
    fun isValid(): Boolean = Crypto.isPrivKeyValid(value.toByteArray())

    operator fun plus(that: PrivateKey): PrivateKey = PrivateKey(Secp256k1.privKeyTweakAdd(value.toByteArray(), that.value.toByteArray()))

    operator fun unaryMinus(): PrivateKey = PrivateKey(Secp256k1.privKeyNegate(value.toByteArray()))

    operator fun minus(that: PrivateKey): PrivateKey = plus(-that)

    operator fun times(that: PrivateKey): PrivateKey =
        PrivateKey(Secp256k1.privKeyTweakMul(value.toByteArray(), that.value.toByteArray()))

    fun tweak(tweak: ByteVector32): PrivateKey {
        val key = if (publicKey().isEven()) this else -this
        return key + PrivateKey(tweak)
    }

    fun publicKey(): PublicKey {
        val pub = Secp256k1.pubkeyCreate(value.toByteArray())
        return PublicKey(PublicKey.compress(pub))
    }

    fun xOnlyPublicKey(): XonlyPublicKey = XonlyPublicKey(publicKey())

    fun compress(): ByteArray = value.toByteArray() + 1.toByte()

    fun toBase58(prefix: Byte): String = Base58Check.encode(prefix, compress())

    fun toHex(): String = value.toHex()

    /**
     * We avoid accidentally logging private keys.
     * You should use an explicit method if you want to convert the private key to a string representation.
     */
    override fun toString(): String = "<private_key>"

    companion object {
        @JvmStatic
        fun isCompressed(data: ByteArray): Boolean {
            return when (data.size) {
                32 -> false
                33 if data.last() == 1.toByte() -> true
                else -> throw IllegalArgumentException("invalid private key ${Hex.encode(data)}")
            }
        }

        @JvmStatic
        fun fromBase58(value: String, prefix: Byte): Pair<PrivateKey, Boolean> {
            require(setOf(Base58.Prefix.SecretKey, Base58.Prefix.SecretKeyTestnet).contains(prefix)) { "invalid base 58 prefix for a private key" }
            val (prefix1, data) = Base58Check.decode(value)
            require(prefix1 == prefix) { "prefix $prefix1 does not match expected prefix $prefix" }
            return Pair(PrivateKey(data), isCompressed(data))
        }

        @JvmStatic
        fun fromHex(hex: String): PrivateKey = PrivateKey(Hex.decode(hex))
    }
}