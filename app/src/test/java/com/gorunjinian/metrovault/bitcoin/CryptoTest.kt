package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.Base58
import com.gorunjinian.metrovault.lib.bitcoin.Base58Check
import com.gorunjinian.metrovault.lib.bitcoin.ByteVector
import com.gorunjinian.metrovault.lib.bitcoin.ByteVector32
import com.gorunjinian.metrovault.lib.bitcoin.ByteVector64
import com.gorunjinian.metrovault.lib.bitcoin.Crypto
import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey
import com.gorunjinian.metrovault.lib.bitcoin.PublicKey
import fr.acinq.secp256k1.Hex
import fr.acinq.secp256k1.Secp256k1
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CryptoTest {

    @Test
    fun importPrivateKeysFromTestnetWif() {
        val address = "mhW1BQDyhbTsnHEuB1n7yuj9V81TbeRfTY"
        val wif = "cRp4uUnreGMZN8vB7nQFX6XWMHU5Lc73HMAhmcDEwHfbgRS66Cqp"
        val (version, _) = Base58Check.decode(wif)
        assertEquals(Base58.Prefix.SecretKeyTestnet, version)

        val (priv, compressed) = PrivateKey.fromBase58(wif, Base58.Prefix.SecretKeyTestnet)
        assertTrue(compressed)
        assertEquals(ByteVector32("7e39cf5faec688ce096d40726ec9322fb1f29ea7254f547dad788f9160753587"), priv.value)
        assertArrayEquals(
            Hex.decode("7e39cf5faec688ce096d40726ec9322fb1f29ea7254f547dad788f916075358701"),
            priv.compress()
        )

        val computedAddress = Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, priv.publicKey().hash160())
        assertEquals(address, computedAddress)
    }

    @Test
    fun toStringDoesNotLeakPrivateKey() {
        val priv = PrivateKey.fromHex("BCF69F7AFF3273B864F9DD76896FACE8E3D3CF69A133585C8177816F14FC9B55")
        assertEquals("<private_key>", priv.toString())
    }

    @Test
    fun validatePrivateKeyBounds() {
        assertTrue(PrivateKey.fromHex("BCF69F7AFF3273B864F9DD76896FACE8E3D3CF69A133585C8177816F14FC9B55").isValid())
        assertFalse(PrivateKey.fromHex("0000000000000000000000000000000000000000000000000000000000000000").isValid())
        assertTrue(PrivateKey.fromHex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364140").isValid())
        // Curve order itself is invalid.
        assertFalse(PrivateKey.fromHex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141").isValid())
        assertFalse(PrivateKey.fromHex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF").isValid())
    }

    @Test
    fun checkGenerator() {
        val check = Secp256k1.pubkeyCreate(Hex.decode("0000000000000000000000000000000000000000000000000000000000000001"))
        assertEquals(PublicKey.Generator, PublicKey.parse(check))
    }

    @Test
    fun generatePublicKeysFromPrivateKeys() {
        val priv1 = PrivateKey.fromHex("18E14A7B6A307F426A94F8114701E7C8E774E7F9A47E2C2035DB29A206321725")
        assertEquals(
            ByteVector("0250863ad64a87ae8a2fe83c1af1a8403cb53f53e486d8511dad8a04887e5b2352"),
            priv1.publicKey().value
        )

        val priv2 = PrivateKey.fromHex("BCF69F7AFF3273B864F9DD76896FACE8E3D3CF69A133585C8177816F14FC9B55")
        val pub2 = priv2.publicKey()
        assertEquals(
            ByteVector("03D7E9DD0C618C65DC2E3972E2AA406CCD34E5E77895C96DC48AF0CB16A1D9B8CE"),
            pub2.value
        )
        val address = Base58Check.encode(Base58.Prefix.PubkeyAddress, Crypto.hash160(pub2.toUncompressedBin()))
        assertEquals("19FgFQGZy47NcGTJ4hfNdGMwS8EATqoa1X", address)
    }

    @Test
    fun compressAndDecompressPublicKeys() {
        val pub1 = PrivateKey.fromHex("18E14A7B6A307F426A94F8114701E7C8E774E7F9A47E2C2035DB29A206321725").publicKey()
        assertTrue(Crypto.isPubKeyCompressedOrUncompressed(pub1.value.toByteArray()))
        assertTrue(Crypto.isPubKeyCompressed(pub1.value.toByteArray()))
        assertFalse(Crypto.isPubKeyUncompressed(pub1.value.toByteArray()))

        val uncompressed = pub1.toUncompressedBin()
        assertTrue(Crypto.isPubKeyCompressedOrUncompressed(uncompressed))
        assertFalse(Crypto.isPubKeyCompressed(uncompressed))
        assertTrue(Crypto.isPubKeyUncompressed(uncompressed))

        val compressed = PublicKey.compress(uncompressed)
        assertArrayEquals(pub1.value.toByteArray(), compressed)

        val pub2 = PublicKey(compressed)
        assertEquals(pub1.value, pub2.value)

        // PublicKey constructor rejects uncompressed input.
        assertThrows { PublicKey(uncompressed) }

        // PublicKey.parse accepts uncompressed and returns a compressed key.
        val pub3 = PublicKey.parse(uncompressed)
        assertTrue(Crypto.isPubKeyCompressed(pub3.value.toByteArray()))
        assertEquals(pub1.value, pub3.value)
    }

    @Test
    fun detectInvalidPublicKeys() {
        val pub = PrivateKey.fromHex("BCF69F7AFF3273B864F9DD76896FACE8E3D3CF69A133585C8177816F14FC9B55").publicKey()
        assertTrue(pub.isValid())
        val negated = PublicKey.parse(Secp256k1.pubKeyNegate(pub.value.toByteArray()))
        assertTrue(negated.isValid())

        // pub + (-pub) and pub - pub both produce point at infinity, which secp256k1 rejects.
        assertThrows { pub - pub }
        assertThrows { pub + negated }

        // No public key for an out-of-range private key.
        val invalidPriv = PrivateKey.fromHex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141")
        assertThrows { invalidPriv.publicKey() }

        // Construct a syntactically valid but off-curve compressed key and detect it.
        val offCurve = PublicKey.fromHex("020000000000000000000000000000000000000000000000000000000000000007")
        assertFalse(offCurve.isValid())
    }

    @Test
    fun signAndVerifySignatures() {
        val priv = PrivateKey.fromBase58(
            "cRp4uUnreGMZN8vB7nQFX6XWMHU5Lc73HMAhmcDEwHfbgRS66Cqp",
            Base58.Prefix.SecretKeyTestnet
        ).first
        val pub = priv.publicKey()
        val data = Crypto.sha256("this is a test".encodeToByteArray())
        val sig = Crypto.sign(data, priv)
        assertEquals(
            ByteVector64(
                "fb36b33afe9308f9eebfcdb0f50cb9c51c72e98a578ee26cabf4a26b5aba1fbf" +
                    "2429e5f5081488190fb01c5165189f2c70e619a3b667e6f1e0fc861d5a8a25d1"
            ),
            sig
        )
        assertTrue(Crypto.verifySignature(data, sig, pub))
    }

    @Test
    fun deterministicSignaturesAgainstReferenceVectors() {
        // RFC6979 deterministic ECDSA reference vectors.
        val cases = listOf(
            Triple(
                "0000000000000000000000000000000000000000000000000000000000000001",
                "Satoshi Nakamoto",
                "3045022100934b1ea10a4b3c1757e2b0c017d0b6143ce3c9a7e6a4a49860d7a6ab210ee3d802202442ce9d2b916064108014783e923ec36b49743e2ffa1c4496f01a512aafd9e5"
            ),
            Triple(
                "0000000000000000000000000000000000000000000000000000000000000001",
                "Everything should be made as simple as possible, but not simpler.",
                "3044022033a69cd2065432a30f3d1ce4eb0d59b8ab58c74f27c41a7fdb5696ad4e6108c902206f807982866f785d3f6418d24163ddae117b7db4d5fdf0071de069fa54342262"
            ),
            Triple(
                "0000000000000000000000000000000000000000000000000000000000000001",
                "All those moments will be lost in time, like tears in rain. Time to die...",
                "30450221008600dbd41e348fe5c9465ab92d23e3db8b98b873beecd930736488696438cb6b0220547fe64427496db33bf66019dacbf0039c04199abb0122918601db38a72cfc21"
            ),
            Triple(
                "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364140",
                "Satoshi Nakamoto",
                "3045022100fd567d121db66e382991534ada77a6bd3106f0a1098c231e47993447cd6af2d002206b39cd0eb1bc8603e159ef5c20a5c8ad685a45b06ce9bebed3f153d10d93bed5"
            ),
            Triple(
                "f8b8af8ce3c7cca5e300d33939540c10d45ce001b8f252bfbc57ba0342904181",
                "Alan Turing",
                "304402207063ae83e7f62bbb171798131b4a0564b956930092b33b07b395615d9ec7e15c022058dfcc1e00a35e1572f366ffe34ba0fc47db1e7189759b9fb233c5b05ab388ea"
            ),
            Triple(
                "e91671c46231f833a6406ccbea0e3e392c76c167bac1cb013f6f1013980455c2",
                "There is a computer disease that anybody who works with computers knows about. It's a very serious disease and it interferes completely with the work. The trouble with computers is that you 'play' with them!",
                "3045022100b552edd27580141f3b2a5463048cb7cd3e047b97c9f98076c32dbdf85a68718b0220279fa72dd19bfae05577e06c7c0c1900c371fcd5893f7e1d56a37d30174671f6"
            )
        )
        for ((k, message, expectedDer) in cases) {
            val compact = Crypto.sign(Crypto.sha256(message.encodeToByteArray()), PrivateKey.fromHex(k))
            val der = Secp256k1.compact2der(compact.toByteArray())
            assertEquals(expectedDer, Hex.encode(der))
        }
    }

    @Test
    fun ecdhSharedSecrets() {
        val priv = PrivateKey.fromHex("BCF69F7AFF3273B864F9DD76896FACE8E3D3CF69A133585C8177816F14FC9B55")
        val pub = priv.publicKey()
        val shared = Crypto.ecdh(priv, pub)
        assertEquals("56bc84cffc7db1ca04046fc04ec8f84232c340be789bc4779d221fe8b978af06", Hex.encode(shared))

        // Symmetry: a·B == b·A
        val random = Random
        val priv1 = PrivateKey(random.nextBytes(32))
        val priv2 = PrivateKey(random.nextBytes(32))
        assertArrayEquals(
            Crypto.ecdh(priv1, priv2.publicKey()),
            Crypto.ecdh(priv2, priv1.publicKey())
        )
    }

    @Test
    fun recoverPublicKeyFromSignatureRandom() {
        val random = Random
        val privBytes = ByteArray(32)
        val message = ByteArray(32)
        repeat(100) {
            random.nextBytes(privBytes)
            random.nextBytes(message)
            val priv = PrivateKey(privBytes)
            val pub = priv.publicKey()
            val sig = Crypto.sign(message, priv)
            val (recovered1, recovered2) = Crypto.recoverPublicKey(sig, message)
            assertTrue(Crypto.verifySignature(message, sig, recovered1))
            assertTrue(Crypto.verifySignature(message, sig, recovered2))
            assertTrue(pub == recovered1 || pub == recovered2)
        }
    }

    @Test
    fun taggedHashMatchesBip340Spec() {
        // hash_BIP0340/aux(0x00..0x1F) — known sample to lock down the tagged hash construction
        // that BIP-340 and BIP-352 both depend on.
        val input = ByteArray(32) { it.toByte() }
        val expected = sha256Hash(sha256Hash("TapTweak".encodeToByteArray()).let { it + it } + input)
        val actual = Crypto.taggedHash(input, "TapTweak")
        assertArrayEquals(expected, actual.toByteArray())
    }

    @Test
    fun schnorrKeyPathSignAndVerify() {
        val priv = PrivateKey.fromHex("BCF69F7AFF3273B864F9DD76896FACE8E3D3CF69A133585C8177816F14FC9B55")
        val msg = ByteVector32(Crypto.sha256("schnorr test".encodeToByteArray()))
        val sig = Crypto.signSchnorr(msg, priv, Crypto.TaprootTweak.NoScriptTweak)
        assertNotNull(sig)
        val tweakedXOnly = priv.xOnlyPublicKey().outputKey(Crypto.TaprootTweak.NoScriptTweak).first
        assertTrue(Crypto.verifySignatureSchnorr(msg, sig, tweakedXOnly))
    }

    private fun sha256Hash(b: ByteArray): ByteArray = Crypto.sha256(b)

    private inline fun assertThrows(block: () -> Unit) {
        var threw = false
        try {
            block()
        } catch (_: Throwable) {
            threw = true
        }
        assertTrue("expected an exception", threw)
    }
}
