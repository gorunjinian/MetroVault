package com.gorunjinian.metrovault.lib.bitcoin.silentpayments

import com.gorunjinian.metrovault.lib.bitcoin.Crypto
import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey
import com.gorunjinian.metrovault.lib.bitcoin.PublicKey
import java.math.BigInteger

/**
 * BIP-374 Discrete Log Equality (DLEQ) proofs.
 *
 * A proof demonstrates knowledge of a scalar `a` such that `A = a·G` and `C = a·B` without
 * revealing `a`. BIP-375 uses these so a watching wallet can verify that the ECDH share a signer
 * attached to a silent-payment PSBT really was computed from the keys that signed the inputs —
 * Sparrow (drongo `PSBT.validateSilentPayments`) refuses to extract or broadcast an SP
 * transaction whose shares don't carry a valid proof.
 *
 * Ported from drongo's `DLEQProof.java` and validated against the official BIP-374 test vectors,
 * so proofs we generate verify under Sparrow and vice versa. BIP-375 always uses the standard
 * secp256k1 generator and no message; the custom-generator/message parameters exist to run the
 * full BIP-374 vector set.
 */
object DleqProof {
    const val PROOF_LENGTH = 64

    private const val TAG_AUX = "BIP0374/aux"
    private const val TAG_NONCE = "BIP0374/nonce"
    private const val TAG_CHALLENGE = "BIP0374/challenge"

    /** The secp256k1 group order n. */
    private val CURVE_N: BigInteger = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)

    /**
     * Generate a 64-byte proof (`bytes(32, e) ‖ bytes(32, s)`) that [a] is the discrete log of
     * both `A = a·G` and `C = a·B`, per BIP-374 `GenerateProof`.
     *
     * @param a the secret scalar (for BIP-375: the sum of the eligible input private keys).
     * @param b the point `B` (for BIP-375: the recipient's scan key `B_scan`).
     * @param auxRand 32 bytes of fresh randomness.
     * @param message optional 32-byte message (always null for BIP-375).
     * @param generator the generator point (BIP-375 always uses the secp256k1 generator).
     * @return the proof, or null if generation fails (invalid scalar/point — negligible for honest inputs).
     */
    fun generate(
        a: PrivateKey,
        b: PublicKey,
        auxRand: ByteArray,
        message: ByteArray? = null,
        generator: PublicKey = PublicKey.Generator,
    ): ByteArray? {
        require(auxRand.size == 32) { "auxiliary random data must be 32 bytes" }
        require(message == null || message.size == 32) { "message must be 32 bytes" }

        val aInt = BigInteger(1, a.value.toByteArray())
        if (aInt.signum() == 0 || aInt >= CURVE_N) return null
        if (!b.isValid() || !generator.isValid()) return null

        val bigA = generator * a
        val bigC = b * a

        // t = bytes(32, a) xor hash_BIP0374/aux(r)
        val t = xor(a.value.toByteArray(), Crypto.taggedHash(auxRand, TAG_AUX).toByteArray())

        // rand = hash_BIP0374/nonce(t ‖ cbytes(A) ‖ cbytes(C) ‖ m')
        val mPrime = message ?: ByteArray(0)
        val rand = Crypto.taggedHash(t + bigA.value.toByteArray() + bigC.value.toByteArray() + mPrime, TAG_NONCE)

        val k = BigInteger(1, rand.toByteArray()).mod(CURVE_N)
        if (k.signum() == 0) return null
        val kKey = PrivateKey(scalarTo32(k))
        val r1 = generator * kKey
        val r2 = b * kKey

        val e = challenge(bigA, b, bigC, r1, r2, mPrime, generator)
        val s = k.add(e.multiply(aInt)).mod(CURVE_N)
        val proof = scalarTo32(e) + scalarTo32(s)

        return if (verify(bigA, b, bigC, proof, message, generator)) proof else null
    }

    /**
     * Verify a proof that `A` and `C` share the discrete log relative to `G` and `B` respectively,
     * per BIP-374 `VerifyProof`.
     */
    fun verify(
        a: PublicKey,
        b: PublicKey,
        c: PublicKey,
        proof: ByteArray,
        message: ByteArray? = null,
        generator: PublicKey = PublicKey.Generator,
    ): Boolean {
        require(message == null || message.size == 32) { "message must be 32 bytes" }
        if (proof.size != PROOF_LENGTH) return false
        if (!a.isValid() || !b.isValid() || !c.isValid() || !generator.isValid()) return false

        val e = BigInteger(1, proof.copyOfRange(0, 32))
        val s = BigInteger(1, proof.copyOfRange(32, PROOF_LENGTH))
        if (s >= CURVE_N) return false

        // R1 = s·G - e·A, R2 = s·B - e·C; fail on the point at infinity.
        val r1 = runCatching { pointLinear(generator, s, a, e) }.getOrNull() ?: return false
        val r2 = runCatching { pointLinear(b, s, c, e) }.getOrNull() ?: return false

        val mPrime = message ?: ByteArray(0)
        return e == challenge(a, b, c, r1, r2, mPrime, generator)
    }

    /**
     * `s·P - e·Q` (scalars reduced mod n). Throws if the result — or an intermediate — is the
     * point at infinity, which secp256k1 cannot represent; callers treat that as a failed proof.
     */
    private fun pointLinear(p: PublicKey, s: BigInteger, q: PublicKey, e: BigInteger): PublicKey {
        val sModN = s.mod(CURVE_N)
        val eModN = e.mod(CURVE_N)
        val sP = if (sModN.signum() == 0) null else p * PrivateKey(scalarTo32(sModN))
        val negEQ = if (eModN.signum() == 0) null else -(q * PrivateKey(scalarTo32(eModN)))
        return when {
            sP != null && negEQ != null -> sP + negEQ
            sP != null -> sP
            negEQ != null -> negEQ
            else -> throw IllegalArgumentException("result is the point at infinity")
        }
    }

    /** `e = int(hash_BIP0374/challenge(cbytes(A) ‖ cbytes(B) ‖ cbytes(C) ‖ cbytes(G) ‖ cbytes(R1) ‖ cbytes(R2) ‖ m'))`. */
    private fun challenge(
        a: PublicKey, b: PublicKey, c: PublicKey,
        r1: PublicKey, r2: PublicKey,
        mPrime: ByteArray,
        generator: PublicKey,
    ): BigInteger {
        val data = a.value.toByteArray() + b.value.toByteArray() + c.value.toByteArray() +
            generator.value.toByteArray() + r1.value.toByteArray() + r2.value.toByteArray() + mPrime
        return BigInteger(1, Crypto.taggedHash(data, TAG_CHALLENGE).toByteArray())
    }

    private fun xor(x: ByteArray, y: ByteArray): ByteArray {
        require(x.size == y.size) { "xor operands must have equal length" }
        return ByteArray(x.size) { i -> (x[i].toInt() xor y[i].toInt()).toByte() }
    }

    /** Encode a non-negative integer < 2^256 as a fixed 32-byte big-endian array. */
    private fun scalarTo32(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size == 33 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, 33)
            bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
            else -> error("scalar does not fit in 32 bytes")
        }
    }
}
