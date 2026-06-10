package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.domain.service.silentpayments.SilentPaymentReceiveSigner
import com.gorunjinian.metrovault.lib.bitcoin.Base58
import com.gorunjinian.metrovault.lib.bitcoin.Bip322
import com.gorunjinian.metrovault.lib.bitcoin.Bitcoin
import com.gorunjinian.metrovault.lib.bitcoin.Block
import com.gorunjinian.metrovault.lib.bitcoin.ByteVector
import com.gorunjinian.metrovault.lib.bitcoin.ByteVector32
import com.gorunjinian.metrovault.lib.bitcoin.DataEntry
import com.gorunjinian.metrovault.lib.bitcoin.Global
import com.gorunjinian.metrovault.lib.bitcoin.Input
import com.gorunjinian.metrovault.lib.bitcoin.Output
import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey
import com.gorunjinian.metrovault.lib.bitcoin.Psbt
import com.gorunjinian.metrovault.lib.bitcoin.Script
import com.gorunjinian.metrovault.lib.bitcoin.ScriptWitness
import com.gorunjinian.metrovault.lib.bitcoin.SigHash
import com.gorunjinian.metrovault.lib.bitcoin.byteVector
import com.gorunjinian.metrovault.lib.bitcoin.utils.Either
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BIP-322 simple-signature verification (P2TR key path), pinned to drongo's `Bip322.java` so
 * message signatures interoperate with Sparrow — including the silent-payments flow, where
 * Sparrow's Sign Message dialog exports a BIP-322 PSBT carrying `PSBT_IN_SP_TWEAK` for the
 * air-gapped signer.
 */
class Bip322Test {
    // drongo Bip322Test taproot vector: key L3VFeEujGtevx9w18HD1fhRbCH67Az2dpCymeRE1SoPK6XQtaN2k.
    private val taprootAddress = "bc1ppv609nr0vr25u07u95waq5lucwfm6tde4nydujnu8npg4q75mr5sxq8lt3"
    private val taprootSignature = "smpAUHd69PrJQEv+oKTfZ8l+WROBHuy9HKrbFCJu7U1iK2iiEy1vMU5EfMtjc+VSHM7aU0SDbak5IUZRVno2P5mjSafAQ=="
    private val mainnet = Block.LivenetGenesisBlock.hash

    @Test
    fun messageHashMatchesBip322Vectors() {
        assertEquals("c90c269c4f8fcbe6880f72a721ddfbf1914268a794cbb21cfafee13770ae19f1", Bip322.messageHash("").toHex())
        assertEquals("f0eb03b1a75ac6d9847f55c624a99169b5dccba2a31f5b23bea77ba270de0a7a", Bip322.messageHash("Hello World").toHex())
    }

    @Test
    fun verifiesDrongoTaprootVector() {
        assertTrue(Bip322.verifySimple(taprootAddress, "Hello World", taprootSignature, mainnet))
        // The bare (unprefixed) base64 form must also be accepted, as in drongo.
        assertTrue(Bip322.verifySimple(taprootAddress, "Hello World", taprootSignature.removePrefix("smp"), mainnet))
    }

    @Test
    fun rejectsWrongMessageAddressAndTamperedSignature() {
        assertFalse(Bip322.verifySimple(taprootAddress, "Hello World!", taprootSignature, mainnet))
        // A different (valid) taproot address — the BIP-86 first receive address.
        assertFalse(
            Bip322.verifySimple(
                "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr",
                "Hello World", taprootSignature, mainnet
            )
        )
        // Flip one character in the base64 payload.
        val tampered = taprootSignature.replaceRange(20..20, if (taprootSignature[20] == 'A') "B" else "A")
        assertFalse(Bip322.verifySimple(taprootAddress, "Hello World", tampered, mainnet))
        // Garbage inputs must return false, not throw.
        assertFalse(Bip322.verifySimple(taprootAddress, "Hello World", "not base64 !!!", mainnet))
        assertFalse(Bip322.verifySimple("invalid address", "Hello World", taprootSignature, mainnet))
    }

    @Test
    fun signsAndVerifiesP2wpkhOfficialVectors() {
        // Official BIP-322 vectors (also drongo's signMessageBip322 test).
        val (privKey, _) = PrivateKey.fromBase58("L3VFeEujGtevx9w18HD1fhRbCH67Az2dpCymeRE1SoPK6XQtaN2k", Base58.Prefix.SecretKey)
        val address = privKey.publicKey().p2wpkhAddress(mainnet)
        assertEquals("bc1q9vza2e8x573nczrlzms0wvx3gsqjx7vavgkx0l", address)

        // Verifying the official vector signatures pins the toSpend/toSign/sighash construction.
        // (Our own signatures are valid alternates — secp256k1 doesn't low-R grind like Core —
        // so they verify but don't byte-match the vectors.)
        assertTrue(
            Bip322.verifySimple(
                address, "",
                "smpAkcwRAIgM2gBAQqvZX15ZiysmKmQpDrG83avLIT492QBzLnQIxYCIBaTpOaD20qRlEylyxFSeEA2ba9YOixpX8z46TSDtS40ASECx/EgAxlkQpQ9hYjgGu6EBCPMVPwVIVJqO4XCsMvViHI=",
                mainnet
            )
        )
        assertTrue(
            Bip322.verifySimple(
                address, "Hello World",
                "smpAkcwRAIgZRfIY3p7/DoVTty6YZbWS71bc5Vct9p9Fia83eRmw2QCICK/ENGfwLtptFluMGs2KsqoNSk89pO7F29zJLUx9a/sASECx/EgAxlkQpQ9hYjgGu6EBCPMVPwVIVJqO4XCsMvViHI=",
                mainnet
            )
        )
        assertTrue(
            Bip322.verifySimple(address, "Hello World", Bip322.signSimple(address, "Hello World", privKey, mainnet), mainnet)
        )

        // drongo's verify vectors: an alternate-nonce signature must verify; cross-message must not.
        assertTrue(
            Bip322.verifySimple(
                address, "Hello World",
                "smpAkgwRQIhAOzyynlqt93lOKJr+wmmxIens//zPzl9tqIOua93wO6MAiBi5n5EyAcPScOjf1lAqIUIQtr3zKNeavYabHyR8eGhowEhAsfxIAMZZEKUPYWI4BruhAQjzFT8FSFSajuFwrDL1Yhy",
                mainnet
            )
        )
        assertFalse(
            Bip322.verifySimple(
                address, "",
                "AkcwRAIgZRfIY3p7/DoVTty6YZbWS71bc5Vct9p9Fia83eRmw2QCICK/ENGfwLtptFluMGs2KsqoNSk89pO7F29zJLUx9a/sASECx/EgAxlkQpQ9hYjgGu6EBCPMVPwVIVJqO4XCsMvViHI=",
                mainnet
            )
        )
    }

    @Test
    fun signsAndVerifiesBip86Taproot() {
        // A BIP-86 wallet key: the address program is the taptweaked key, signSimple applies the
        // same tweak when signing (matching drongo's getOutputKey path).
        val key = PrivateKey.fromHex("0303030303030303030303030303030303030303030303030303030303030304")
        val address = key.xOnlyPublicKey().p2trAddress(mainnet)
        val signature = Bip322.signSimple(address, "MetroVault", key, mainnet)
        assertTrue(Bip322.verifySimple(address, "MetroVault", signature, mainnet))
        assertFalse(Bip322.verifySimple(address, "metrovault", signature, mainnet))
    }

    /**
     * The full Sparrow silent-payments message-signing round trip: Sparrow builds a BIP-322 PSBT
     * whose single input pays the SP receive address and carries `PSBT_IN_SP_TWEAK`
     * (drongo `Bip322.getBip322PsbtSp`); the air-gapped signer signs it like any tweaked SP input;
     * Sparrow extracts the witness as the signature. Our receive signer must produce a signature
     * that BIP-322 verification accepts for that address.
     */
    @Test
    fun signsSparrowSpMessagePsbtAndVerifies() {
        val message = "Hello World"
        val spendKey = PrivateKey.fromHex("0101010101010101010101010101010101010101010101010101010101010102")
        val tweak = ByteVector32("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        val d = spendKey + PrivateKey(tweak)
        // The SP output key is d·G *directly* — no BIP-86 taptweak — so the address must be
        // encoded from the output script, not via p2trAddress() (which tweaks an internal key).
        val outputScript = Script.write(Script.pay2tr(d.xOnlyPublicKey())).byteVector()
        val spAddress = (Bitcoin.addressFromPublicKeyScript(mainnet, outputScript.toByteArray()) as Either.Right).value

        // Build the PSBT exactly as drongo's getBip322PsbtSp does (v0, witnessUtxo, SIGHASH_ALL,
        // PSBT_GLOBAL_GENERIC_SIGNED_MESSAGE).
        val toSpend = Bip322.toSpend(outputScript, message)
        val toSign = Bip322.toSign(toSpend)
        val input = Input.WitnessInput.PartiallySignedWitnessInput(
            toSpend.txOut[0], null, SigHash.SIGHASH_ALL, emptyMap(), emptyMap(),
            null, null, emptySet(), emptySet(), emptySet(), emptySet(), null, emptyMap(), null,
            listOf(DataEntry(ByteVector("20"), ByteVector(tweak.toByteArray())))
        )
        val output = Output.UnspecifiedOutput(emptyMap(), null, emptyMap(), emptyList())
        val messageEntry = DataEntry(ByteVector(byteArrayOf(Bip322.PSBT_GLOBAL_GENERIC_SIGNED_MESSAGE)), ByteVector(message.encodeToByteArray()))
        val psbt = Psbt(Global(0, toSign, emptyList(), listOf(messageEntry)), listOf(input), listOf(output))

        // Round-trip through serialization, as the QR boundary would.
        val parsed = (Psbt.read(Psbt.write(psbt).toByteArray()) as Either.Right).value

        // The message-PSBT recognizer must accept it and recover the declared address + message...
        val request = Bip322.parseMessagePsbt(parsed, mainnet)
        assertNotNull("message PSBT must be recognized", request)

        // ...and reject a request whose input doesn't commit to the declared message.
        val tamperedEntry = DataEntry(ByteVector(byteArrayOf(Bip322.PSBT_GLOBAL_GENERIC_SIGNED_MESSAGE)), ByteVector("Pay me instead".encodeToByteArray()))
        val tampered = parsed.copy(global = parsed.global.copy(unknown = listOf(tamperedEntry)))
        var rejected = false
        try {
            Bip322.parseMessagePsbt(tampered, mainnet)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("a message PSBT whose input doesn't commit to the message must be rejected", rejected)

        // Sign with the receive signer (the same path the Sign PSBT flow uses).
        val signed = (SilentPaymentReceiveSigner.signTweakedInputs(parsed, spendKey) as Either.Right).value
        val signature = signed.inputs[0].taprootKeySignature
        assertNotNull("tweaked input must carry a taproot key-path signature", signature)

        // Sparrow extracts the signature into a single-element witness (getBip322SignatureFromPsbtSp);
        // signatureFromSignedMessagePsbt must produce the identical encoding.
        val encoded = Bip322.encodeSimpleSignature(ScriptWitness(listOf(signature!!)))
        assertEquals(encoded, Bip322.signatureFromSignedMessagePsbt(signed))
        assertEquals(spAddress, request!!.address)
        assertEquals(message, request.message)
        assertTrue(
            "receive-signer signature must verify as a BIP-322 message signature for the SP address",
            Bip322.verifySimple(spAddress, message, encoded, mainnet)
        )

        // The same signature must not verify for a different message or a different-tweak address.
        assertFalse(Bip322.verifySimple(spAddress, "Hello World!", encoded, mainnet))
        val otherTweak = ByteVector32("fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210")
        val otherScript = Script.write(Script.pay2tr((spendKey + PrivateKey(otherTweak)).xOnlyPublicKey()))
        val otherAddress = (Bitcoin.addressFromPublicKeyScript(mainnet, otherScript) as Either.Right).value
        assertFalse(Bip322.verifySimple(otherAddress, message, encoded, mainnet))
    }
}
