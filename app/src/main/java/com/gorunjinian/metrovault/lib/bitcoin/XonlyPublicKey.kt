package com.gorunjinian.metrovault.lib.bitcoin

import kotlin.jvm.JvmField

/**
 * x-only pubkey, used with Schnorr signatures (see https://github.com/bitcoin/bips/tree/master/bip-0340)
 * we only store the x coordinate of the pubkey, the y coordinate is always even
 */
data class XonlyPublicKey(@JvmField val value: ByteVector32) {
    constructor(pub: PublicKey) : this(pub.value.drop(1).toByteArray().byteVector32())

    val publicKey: PublicKey = PublicKey(byteArrayOf(2) + value.toByteArray())

    fun tweak(tapTweak: Crypto.TaprootTweak): ByteVector32 {
        return when (tapTweak) {
            Crypto.TaprootTweak.NoScriptTweak -> Crypto.taggedHash(value.toByteArray(), "TapTweak")
            is Crypto.TaprootTweak.ScriptTweak -> Crypto.taggedHash(value.toByteArray() + tapTweak.merkleRoot.toByteArray(), "TapTweak")
        }
    }

    /**
     * Tweak this key with an optional merkle root.
     *
     * @param tapTweak taproot tweak
     * @return an (x-only pubkey, parity) pair
     */
    fun outputKey(tapTweak: Crypto.TaprootTweak): Pair<XonlyPublicKey, Boolean> = this + PrivateKey(tweak(tapTweak)).publicKey()

    /** Tweak this key with the merkle root of the given script tree. */
    fun outputKey(scriptTree: ScriptTree): Pair<XonlyPublicKey, Boolean> = outputKey(Crypto.TaprootTweak.ScriptTweak(scriptTree))

    /** Tweak this key with the merkle root provided. */
    fun outputKey(merkleRoot: ByteVector32): Pair<XonlyPublicKey, Boolean> = outputKey(Crypto.TaprootTweak.ScriptTweak(merkleRoot))

    /**
     * @param chainHash chain hash (i.e. hash of the genesis block of the chain we're on)
     * @return the BIP86 address for this key (i.e. the p2tr address for this key with an explicit absence of scripts).
     */
    fun p2trAddress(chainHash: BlockHash): String {
        val (outputKey, _) = outputKey(Crypto.TaprootTweak.NoScriptTweak)
        return Bech32.encodeWitnessAddress(Bech32.hrp(chainHash), 1, outputKey.value.toByteArray())
    }

    /**
     * add a public key to this x-only key
     * @param that public key
     * @return a (key, parity) pair where `key` is the x-only-pubkey for `this` + `that` and `parity` is true if `this` + `that` is odd
     */
    operator fun plus(that: PublicKey): Pair<XonlyPublicKey, Boolean> {
        val pub = publicKey + that
        return Pair(XonlyPublicKey(pub), pub.isOdd())
    }
}
