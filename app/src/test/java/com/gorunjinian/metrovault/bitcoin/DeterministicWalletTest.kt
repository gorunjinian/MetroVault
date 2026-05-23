package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.ByteVector
import com.gorunjinian.metrovault.lib.bitcoin.Crypto
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet.generate
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet.hardened
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet.isHardened
import com.gorunjinian.metrovault.lib.bitcoin.KeyPath
import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey
import com.gorunjinian.metrovault.lib.bitcoin.crypto.Pack
import fr.acinq.secp256k1.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

// BIP-32 reference: https://github.com/bitcoin/bips/blob/master/bip-0032.mediawiki
class DeterministicWalletTest {

    private fun deriveAndCheck(
        parent: DeterministicWallet.ExtendedPrivateKey,
        childNumber: Long,
        expectedPrivateKey: String,
        expectedPublicKey: String
    ): Pair<DeterministicWallet.ExtendedPrivateKey, DeterministicWallet.ExtendedPublicKey> {
        val priv = parent.derivePrivateKey(childNumber)
        assertEquals(expectedPrivateKey, priv.encode(testnet = false))
        // Full in-memory equality round-trip (relies on fingerprint() returning an unsigned Long;
        // before Phase B.1 the fingerprint impl produced a sign-extended negative Long that broke
        // structural equality with decoded keys).
        assertEquals(priv, DeterministicWallet.ExtendedPrivateKey.decode(expectedPrivateKey, parent.path).second)
        assertEquals(parent.fingerprint(), priv.parent)

        val pub = priv.extendedPublicKey
        assertEquals(expectedPublicKey, pub.encode(testnet = false))
        assertEquals(pub, DeterministicWallet.ExtendedPublicKey.decode(expectedPublicKey, parent.path).second)
        assertEquals(parent.fingerprint(), pub.parent)

        if (!isHardened(childNumber)) {
            assertEquals(parent.extendedPublicKey.derivePublicKey(childNumber), pub)
        } else {
            assertThrows { parent.extendedPublicKey.derivePublicKey(childNumber) }
        }
        return Pair(priv, pub)
    }

    @Test
    fun bip32Vector1() {
        val m = generate(Hex.decode("000102030405060708090a0b0c0d0e0f"))
        assertEquals(
            "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi",
            m.encode(testnet = false)
        )
        assertEquals(
            "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8",
            m.extendedPublicKey.encode(testnet = false)
        )
        // Fingerprint values from BIP-32 vector 1 (unsigned, big-endian); these would have been
        // sign-extended to negative Longs by the pre-Phase B.1 fingerprint impl.
        assertEquals(0x3442193EL, m.fingerprint())

        val (m0h, _) = deriveAndCheck(
            m, hardened(0),
            "xprv9uHRZZhk6KAJC1avXpDAp4MDc3sQKNxDiPvvkX8Br5ngLNv1TxvUxt4cV1rGL5hj6KCesnDYUhd7oWgT11eZG7XnxHrnYeSvkzY7d2bhkJ7",
            "xpub68Gmy5EdvgibQVfPdqkBBCHxA5htiqg55crXYuXoQRKfDBFA1WEjWgP6LHhwBZeNK1VTsfTFUHCdrfp1bgwQ9xv5ski8PX9rL2dZXvgGDnw"
        )
        assertEquals(0x5C1BD648L, m0h.fingerprint())

        val (m0h_1, m0h_1_pub) = deriveAndCheck(
            m0h, 1,
            "xprv9wTYmMFdV23N2TdNG573QoEsfRrWKQgWeibmLntzniatZvR9BmLnvSxqu53Kw1UmYPxLgboyZQaXwTCg8MSY3H2EU4pWcQDnRnrVA1xe8fs",
            "xpub6ASuArnXKPbfEwhqN6e3mwBcDTgzisQN1wXN9BJcM47sSikHjJf3UFHKkNAWbWMiGj7Wf5uMash7SyYq527Hqck2AxYysAA7xmALppuCkwQ"
        )
        // 0xBEF5A2F9 has the high bit set — the broken pre-Phase B.1 impl returned -1091018503L
        // (sign-extended) instead of the unsigned 3203948793L expected here.
        assertEquals(0xBEF5A2F9L, m0h_1.fingerprint())
        val (m0h_1_2h, _) = deriveAndCheck(
            m0h_1, hardened(2),
            "xprv9z4pot5VBttmtdRTWfWQmoH1taj2axGVzFqSb8C9xaxKymcFzXBDptWmT7FwuEzG3ryjH4ktypQSAewRiNMjANTtpgP4mLTj34bhnZX7UiM",
            "xpub6D4BDPcP2GT577Vvch3R8wDkScZWzQzMMUm3PWbmWvVJrZwQY4VUNgqFJPMM3No2dFDFGTsxxpG5uJh7n7epu4trkrX7x7DogT5Uv6fcLW5"
        )
        assertThrows { m0h_1_pub.derivePublicKey(hardened(2)) }

        val (m0h_1_2h_2, _) = deriveAndCheck(
            m0h_1_2h, 2,
            "xprvA2JDeKCSNNZky6uBCviVfJSKyQ1mDYahRjijr5idH2WwLsEd4Hsb2Tyh8RfQMuPh7f7RtyzTtdrbdqqsunu5Mm3wDvUAKRHSC34sJ7in334",
            "xpub6FHa3pjLCk84BayeJxFW2SP4XRrFd1JYnxeLeU8EqN3vDfZmbqBqaGJAyiLjTAwm6ZLRQUMv1ZACTj37sR62cfN7fe5JnJ7dh8zL4fiyLHV"
        )

        val (m0h_1_2h_2_1000000000, _) = deriveAndCheck(
            m0h_1_2h_2, 1000000000L,
            "xprvA41z7zogVVwxVSgdKUHDy1SKmdb533PjDz7J6N6mV6uS3ze1ai8FHa8kmHScGpWmj4WggLyQjgPie1rFSruoUihUZREPSL39UNdE3BBDu76",
            "xpub6H1LXWLaKsWFhvm6RVpEL9P4KfRZSW7abD2ttkWP3SSQvnyA8FSVqNTEcYFgJS2UaFcxupHiYkro49S8yGasTvXEYBVPamhGW6cFJodrTHy"
        )
        assertEquals(m.derivePrivateKey("0'/1/2'/2/1000000000"), m0h_1_2h_2_1000000000)
    }

    @Test
    fun bip32Vector2() {
        val m = generate(
            Hex.decode(
                "fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a29f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542"
            )
        )
        assertEquals(
            "xprv9s21ZrQH143K31xYSDQpPDxsXRTUcvj2iNHm5NUtrGiGG5e2DtALGdso3pGz6ssrdK4PFmM8NSpSBHNqPqm55Qn3LqFtT2emdEXVYsCzC2U",
            m.encode(testnet = false)
        )
        assertEquals(
            "xpub661MyMwAqRbcFW31YEwpkMuc5THy2PSt5bDMsktWQcFF8syAmRUapSCGu8ED9W6oDMSgv6Zz8idoc4a6mr8BDzTJY47LJhkJ8UB7WEGuduB",
            m.extendedPublicKey.encode(testnet = false)
        )

        val (m0, _) = deriveAndCheck(
            m, 0L,
            "xprv9vHkqa6EV4sPZHYqZznhT2NPtPCjKuDKGY38FBWLvgaDx45zo9WQRUT3dKYnjwih2yJD9mkrocEZXo1ex8G81dwSM1fwqWpWkeS3v86pgKt",
            "xpub69H7F5d8KSRgmmdJg2KhpAK8SR3DjMwAdkxj3ZuxV27CprR9LgpeyGmXUbC6wb7ERfvrnKZjXoUmmDznezpbZb7ap6r1D3tgFxHmwMkQTPH"
        )
        val (m0_h, _) = deriveAndCheck(
            m0, hardened(2147483647),
            "xprv9wSp6B7kry3Vj9m1zSnLvN3xH8RdsPP1Mh7fAaR7aRLcQMKTR2vidYEeEg2mUCTAwCd6vnxVrcjfy2kRgVsFawNzmjuHc2YmYRmagcEPdU9",
            "xpub6ASAVgeehLbnwdqV6UKMHVzgqAG8Gr6riv3Fxxpj8ksbH9ebxaEyBLZ85ySDhKiLDBrQSARLq1uNRts8RuJiHjaDMBU4Zn9h8LZNnBC5y4a"
        )
        val (m0_h_1, _) = deriveAndCheck(
            m0_h, 1,
            "xprv9zFnWC6h2cLgpmSA46vutJzBcfJ8yaJGg8cX1e5StJh45BBciYTRXSd25UEPVuesF9yog62tGAQtHjXajPPdbRCHuWS6T8XA2ECKADdw4Ef",
            "xpub6DF8uhdarytz3FWdA8TvFSvvAh8dP3283MY7p2V4SeE2wyWmG5mg5EwVvmdMVCQcoNJxGoWaU9DCWh89LojfZ537wTfunKau47EL2dhHKon"
        )
        val (m0_h_1_h, _) = deriveAndCheck(
            m0_h_1, hardened(2147483646),
            "xprvA1RpRA33e1JQ7ifknakTFpgNXPmW2YvmhqLQYMmrj4xJXXWYpDPS3xz7iAxn8L39njGVyuoseXzU6rcxFLJ8HFsTjSyQbLYnMpCqE2VbFWc",
            "xpub6ERApfZwUNrhLCkDtcHTcxd75RbzS1ed54G1LkBUHQVHQKqhMkhgbmJbZRkrgZw4koxb5JaHWkY4ALHY2grBGRjaDMzQLcgJvLJuZZvRcEL"
        )
        deriveAndCheck(
            m0_h_1_h, 2,
            "xprvA2nrNbFZABcdryreWet9Ea4LvTJcGsqrMzxHx98MMrotbir7yrKCEXw7nadnHM8Dq38EGfSh6dqA9QWTyefMLEcBYJUuekgW4BYPJcr9E7j",
            "xpub6FnCn6nSzZAw5Tw7cgR9bi15UV96gLZhjDstkXXxvCLsUXBGXPdSnLFbdpq8p9HmGsApME5hQTZ3emM2rnY5agb9rXpVGyy3bdW6EEgAtqt"
        )
    }

    @Test
    fun bip32Vector3() {
        val m = generate(ByteVector("4b381541583be4423346c643850da4b320e46a87ae3d2a4e6da11eba819cd4acba45d239319ac14f863b8d5ab5a0d0c64d2e8a1e7d1457df2e5a3c51c73235be"))
        assertEquals(
            "xprv9s21ZrQH143K25QhxbucbDDuQ4naNntJRi4KUfWT7xo4EKsHt2QJDu7KXp1A3u7Bi1j8ph3EGsZ9Xvz9dGuVrtHHs7pXeTzjuxBrCmmhgC6",
            m.encode(testnet = false)
        )
        assertEquals(
            "xpub661MyMwAqRbcEZVB4dScxMAdx6d4nFc9nvyvH3v4gJL378CSRZiYmhRoP7mBy6gSPSCYk6SzXPTf3ND1cZAceL7SfJ1Z3GC8vBgp2epUt13",
            m.extendedPublicKey.encode(testnet = false)
        )
        deriveAndCheck(
            m, hardened(0),
            "xprv9uPDJpEQgRQfDcW7BkF7eTya6RPxXeJCqCJGHuCJ4GiRVLzkTXBAJMu2qaMWPrS7AANYqdq6vcBcBUdJCVVFceUvJFjaPdGZ2y9WACViL4L",
            "xpub68NZiKmJWnxxS6aaHmn81bvJeTESw724CRDs6HbuccFQN9Ku14VQrADWgqbhhTHBaohPX4CjNLf9fq9MYo6oDaPPLPxSb7gwQN3ih19Zm4Y"
        )
    }

    @Test
    fun bip32Vector4() {
        val m = generate(ByteVector("3ddd5602285899a946114506157c7997e5444528f3003f6134712147db19b678"))
        assertEquals(
            "xprv9s21ZrQH143K48vGoLGRPxgo2JNkJ3J3fqkirQC2zVdk5Dgd5w14S7fRDyHH4dWNHUgkvsvNDCkvAwcSHNAQwhwgNMgZhLtQC63zxwhQmRv",
            m.encode(testnet = false)
        )
        assertEquals(
            "xpub661MyMwAqRbcGczjuMoRm6dXaLDEhW1u34gKenbeYqAix21mdUKJyuyu5F1rzYGVxyL6tmgBUAEPrEz92mBXjByMRiJdba9wpnN37RLLAXa",
            m.extendedPublicKey.encode(testnet = false)
        )

        val (m_0h, _) = deriveAndCheck(
            m, hardened(0),
            "xprv9vB7xEWwNp9kh1wQRfCCQMnZUEG21LpbR9NPCNN1dwhiZkjjeGRnaALmPXCX7SgjFTiCTT6bXes17boXtjq3xLpcDjzEuGLQBM5ohqkao9G",
            "xpub69AUMk3qDBi3uW1sXgjCmVjJ2G6WQoYSnNHyzkmdCHEhSZ4tBok37xfFEqHd2AddP56Tqp4o56AePAgCjYdvpW2PU2jbUPFKsav5ut6Ch1m"
        )
        assertEquals(0, m_0h.privateKey.value[0].toInt())
        deriveAndCheck(
            m_0h, hardened(1),
            "xprv9xJocDuwtYCMNAo3Zw76WENQeAS6WGXQ55RCy7tDJ8oALr4FWkuVoHJeHVAcAqiZLE7Je3vZJHxspZdFHfnBEjHqU5hG1Jaj32dVoS6XLT1",
            "xpub6BJA1jSqiukeaesWfxe6sNK9CCGaujFFSJLomWHprUL9DePQ4JDkM5d88n49sMGJxrhpjazuXYWdMf17C9T5XnxkopaeS7jGk1GyyVziaMt"
        )
    }

    @Test
    fun rejectInvalidEncodedKeys() {
        val testCases = listOf(
            "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"
        )
        for (encoded in testCases) {
            // Each malformed encoded key must be rejected by both decoders.
            assertThrows { DeterministicWallet.ExtendedPublicKey.decode(encoded) }
            assertThrows { DeterministicWallet.ExtendedPrivateKey.decode(encoded) }
        }
    }

    @Test
    fun recoverParentPrivateKeyFromMasterPubAndChildPriv() {
        val m = generate(ByteVector("000102030405060708090a0b0c0d0e0f"))
        val masterPriv = PrivateKey(m.secretkeybytes)
        val masterPub = m.extendedPublicKey

        val m42 = m.derivePrivateKey(42L)
        val I = Crypto.hmac512(
            masterPub.chaincode.toByteArray(),
            masterPub.publickeybytes.toByteArray() + Pack.writeInt32BE(42)
        )
        val IL = I.copyOfRange(0, 32)
        val recovered = PrivateKey(m42.secretkeybytes) - PrivateKey(IL)
        assertArrayEquals(masterPriv.value.toByteArray(), recovered.value.toByteArray())
    }

    @Test
    fun parseStringFormattedDerivationPaths() {
        val canonical = KeyPath(listOf(hardened(44), hardened(0), hardened(0), 0))
        assertEquals(canonical, KeyPath("m/44'/0'/0'/0"))
        assertEquals(canonical, KeyPath("m/44h/0h/0h/0"))
        assertEquals(canonical, KeyPath("/44'/0'/0'/0"))
        assertEquals(canonical, KeyPath("/44h/0h/0h/0"))
        assertEquals(canonical, KeyPath("44'/0'/0'/0"))
        assertEquals(canonical, KeyPath("44h/0h/0h/0"))

        assertEquals("m/44'/0'/0'/0", KeyPath("m/44'/0'/0'/0").asString('\''))
        assertEquals("m/44h/0h/0h/0", KeyPath("m/44'/0'/0'/0").asString('h'))
        assertEquals("m/44'/0'/0'/0", KeyPath("m/44h/0h/0h/0").asString('\''))
        assertEquals("m/44h/0h/0h/0", KeyPath("m/44h/0h/0h/0").asString('h'))

        val mixed = KeyPath(listOf(44L, hardened(0), hardened(0), 0L))
        assertEquals(mixed, KeyPath("m/44/0'/0'/0"))
        assertEquals(mixed, KeyPath("m/44/0h/0h/0"))

        assertEquals(KeyPath(listOf()), KeyPath("m"))
        assertEquals(KeyPath(listOf()), KeyPath(""))

        for (badPath in listOf("aa/1/2/3", "1/'2/3")) {
            assertThrows { KeyPath(badPath) }
        }
    }

    @Test
    fun deriveAndRoundTripRandomChildren() {
        val random = Random
        for (i in 0..50) {
            val master = generate(random.nextBytes(32))
            for (j in 0..50) {
                val index = random.nextLong()
                val priv = master.derivePrivateKey(index)
                val encodedPriv = priv.encode(DeterministicWallet.tprv)
                val (prefixPriv, decodedPriv) = DeterministicWallet.ExtendedPrivateKey.decode(encodedPriv)
                assertEquals(DeterministicWallet.tprv, prefixPriv)
                assertEquals(priv.chaincode, decodedPriv.chaincode)
                assertArrayEquals(priv.secretkeybytes.toByteArray(), decodedPriv.secretkeybytes.toByteArray())

                val pub = priv.extendedPublicKey
                val encodedPub = pub.encode(DeterministicWallet.tpub)
                val (prefixPub, decodedPub) = DeterministicWallet.ExtendedPublicKey.decode(encodedPub)
                assertEquals(DeterministicWallet.tpub, prefixPub)
                assertEquals(pub.chaincode, decodedPub.chaincode)
                assertArrayEquals(pub.publicKey.value.toByteArray(), decodedPub.publicKey.value.toByteArray())
            }
        }
    }

    @Test
    fun toStringDoesNotLeakXpriv() {
        val m = generate(ByteVector("000102030405060708090a0b0c0d0e0f"))
        assertEquals("<extended_private_key>", m.toString())
    }

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
