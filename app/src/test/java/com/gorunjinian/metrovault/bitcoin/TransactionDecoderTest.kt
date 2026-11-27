package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.domain.service.bitcoin.AddressCheckResult
import com.gorunjinian.metrovault.domain.service.bitcoin.AddressService
import com.gorunjinian.metrovault.domain.service.bitcoin.OutputDestination
import com.gorunjinian.metrovault.domain.service.bitcoin.TransactionDecoder
import com.gorunjinian.metrovault.domain.service.bitcoin.TransactionDetails
import com.gorunjinian.metrovault.domain.service.bitcoin.TransactionLockTime
import com.gorunjinian.vaultovich.Block
import com.gorunjinian.vaultovich.ByteVector
import com.gorunjinian.vaultovich.ByteVector32
import com.gorunjinian.vaultovich.DeterministicWallet
import com.gorunjinian.vaultovich.KeyPath
import com.gorunjinian.vaultovich.MnemonicCode
import com.gorunjinian.vaultovich.OP_1
import com.gorunjinian.vaultovich.OP_13
import com.gorunjinian.vaultovich.OP_PUSHDATA
import com.gorunjinian.vaultovich.OP_RETURN
import com.gorunjinian.vaultovich.OutPoint
import com.gorunjinian.vaultovich.Satoshi
import com.gorunjinian.vaultovich.Script
import com.gorunjinian.vaultovich.ScriptElt
import com.gorunjinian.vaultovich.ScriptType
import com.gorunjinian.vaultovich.ScriptWitness
import com.gorunjinian.vaultovich.Transaction
import com.gorunjinian.vaultovich.TxId
import com.gorunjinian.vaultovich.TxIn
import com.gorunjinian.vaultovich.TxOut
import com.gorunjinian.vaultovich.XonlyPublicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A scanned raw transaction, decoded for its read-only view. */
class TransactionDecoderTest {

    // BIP-39 / BIP-84 test vector seed.
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    private val master = DeterministicWallet.generate(MnemonicCode.toSeed(words, ""))
    private val accountPublicKey = master.derivePrivateKey(KeyPath("m/84'/0'/0'")).extendedPublicKey
    private val mainnet = Block.LivenetGenesisBlock.hash
    private val testnet = Block.Testnet4GenesisBlock.hash
    private val addressService = AddressService()

    private fun walletKey(chain: Long, index: Long) = accountPublicKey.derivePublicKey(chain).derivePublicKey(index).publicKey
    private val externalKey = master.derivePrivateKey(KeyPath("m/86'/0'/0'/0/0")).publicKey

    private val signedInput = TxIn(
        OutPoint(TxId(ByteVector32.One), 1L),
        ByteVector.empty,
        0xfffffffdL,
        ScriptWitness(listOf(ByteVector(ByteArray(71) { 1 }), walletKey(0, 0).value))
    )
    private val unsignedInput = TxIn(OutPoint(TxId(ByteVector32.Zeroes), 0L), ByteVector.empty, TxIn.SEQUENCE_FINAL)

    private fun opReturn(vararg elements: ScriptElt) = TxOut(Satoshi(0), listOf<ScriptElt>(OP_RETURN) + elements)

    private fun transaction(
        inputs: List<TxIn> = listOf(signedInput, unsignedInput),
        outputs: List<TxOut> = listOf(
            TxOut(Satoshi(50_000), Script.pay2wpkh(walletKey(0, 3))),
            TxOut(Satoshi(20_000), Script.pay2wpkh(walletKey(1, 7))),
            TxOut(Satoshi(10_000), Script.pay2tr(XonlyPublicKey(externalKey))),
            opReturn(OP_PUSHDATA("hello".toByteArray()))
        ),
        lockTime: Long = 850_000
    ) = Transaction(2, inputs, outputs, lockTime)

    private fun Transaction.hex() = Transaction.write(this).joinToString("") { "%02x".format(it) }

    private val findInWallet: (Set<String>) -> Map<String, AddressCheckResult> = {
        addressService.findAddresses(it, accountPublicKey, ScriptType.P2WPKH, scanRange = 20)
    }

    private fun decode(tx: Transaction, chain: com.gorunjinian.vaultovich.BlockHash = mainnet): TransactionDetails {
        val details = TransactionDecoder.decode(tx.hex(), chain, findInWallet)
        assertNotNull(details)
        return details!!
    }

    @Test
    fun theSummaryComesFromTheTransactionItself() {
        val tx = transaction()
        val details = decode(tx)

        assertEquals(tx.txid.toString(), details.txid)
        assertEquals(2L, details.version)
        assertTrue(details.isSegwit)
        assertEquals(Transaction.write(tx).size, details.size)
        assertEquals(tx.weight(), details.weight)
        assertEquals((tx.weight() + 3) / 4, details.virtualSize)
        assertEquals(80_000L, details.totalOutputSats)
    }

    @Test
    fun inputsShowWhatTheySpendAndWhetherTheyCarryASignature() {
        val inputs = decode(transaction()).inputs

        assertEquals(TxId(ByteVector32.One).toString(), inputs[0].previousTxid)
        assertEquals(1L, inputs[0].previousIndex)
        assertEquals(0xfffffffdL, inputs[0].sequence)
        assertEquals(listOf(true, false), inputs.map { it.isSigned })

        val legacySigned = unsignedInput.copy(signatureScript = ByteVector(ByteArray(106) { 2 }))
        val legacy = decode(transaction(inputs = listOf(legacySigned)))
        assertFalse(legacy.isSegwit)
        assertTrue(legacy.inputs.single().isSigned)
    }

    @Test
    fun outputsPayingTheWalletAreMarked() {
        val outputs = decode(transaction()).outputs

        assertEquals(AddressCheckResult(true, "m/84'/0'/0'/0/3", 3, false), outputs[0].walletAddress)
        assertEquals(AddressCheckResult(true, "m/84'/0'/0'/1/7", 7, true), outputs[1].walletAddress)
        assertNull(outputs[2].walletAddress)
        assertNull(outputs[3].walletAddress)
        assertTrue((outputs[2].destination as OutputDestination.Address).address.startsWith("bc1p"))
    }

    @Test
    fun theWalletIsAskedOnceForEveryDistinctAddress() {
        val repeated = TxOut(Satoshi(1_000), Script.pay2wpkh(walletKey(0, 3)))
        val asked = mutableListOf<Set<String>>()
        TransactionDecoder.decode(transaction(outputs = listOf(repeated, repeated, opReturn())).hex(), mainnet) {
            asked += it
            emptyMap()
        }
        assertEquals(1, asked.size)
        assertEquals(1, asked.single().size)
    }

    @Test
    fun theWalletIsntAskedWhenNoOutputHasAnAddress() {
        TransactionDecoder.decode(transaction(outputs = listOf(opReturn(OP_PUSHDATA("x".toByteArray())))).hex(), mainnet) {
            fail("no address to look up")
            emptyMap()
        }
    }

    @Test
    fun addressesFollowTheChainAsked() {
        val output = decode(transaction(), testnet).outputs[0].destination as OutputDestination.Address
        assertTrue(output.address.startsWith("tb1q"))
    }

    @Test
    fun lockTimeIsAHeightOrATimeAndEnforcedOnlyWithANonFinalInput() {
        val withRbf = decode(transaction(lockTime = 850_000))
        assertEquals(TransactionLockTime.BlockHeight(850_000, enforced = true), withRbf.lockTime)
        assertTrue(withRbf.signalsRbf)

        val timestamp = decode(transaction(inputs = listOf(unsignedInput), lockTime = 1_700_000_000))
        assertEquals(TransactionLockTime.Timestamp(1_700_000_000, enforced = false), timestamp.lockTime)
        assertFalse(timestamp.signalsRbf)

        assertEquals(TransactionLockTime.None, decode(transaction(lockTime = 0)).lockTime)
    }

    @Test
    fun opReturnDataIsShownAsTextOnlyWhenItIsText() {
        fun data(vararg elements: ScriptElt) =
            decode(transaction(outputs = listOf(opReturn(*elements)))).outputs.single().destination

        assertEquals(OutputDestination.Data("68656c6c6f", "hello"), data(OP_PUSHDATA("hello".toByteArray())))
        assertEquals(OutputDestination.Data("000102", null), data(OP_PUSHDATA(byteArrayOf(0, 1, 2))))
        // A runestone: the OP_13 tag is skipped, and what's left isn't offered as text
        assertEquals(OutputDestination.Data("6869", null), data(OP_13, OP_PUSHDATA("hi".toByteArray())))
        assertEquals(OutputDestination.Data("", null), data())
    }

    @Test
    fun aScriptWithNoAddressIsShownAsHex() {
        val anyoneCanSpend = TxOut(Satoshi(546), listOf<ScriptElt>(OP_1))
        assertEquals(OutputDestination.Script("51"), decode(transaction(outputs = listOf(anyoneCanSpend))).outputs.single().destination)
    }

    @Test
    fun onlyExactlyOneTransactionIsDecoded() {
        val hex = transaction().hex()
        assertNotNull(TransactionDecoder.decode(hex.uppercase(), mainnet))
        assertNotNull(TransactionDecoder.decode("  $hex\n", mainnet))

        assertNull(TransactionDecoder.decode(hex + "00", mainnet))
        assertNull(TransactionDecoder.decode(hex.dropLast(1), mainnet))
        assertNull(TransactionDecoder.decode(hex.dropLast(2), mainnet))
        assertNull(TransactionDecoder.decode("zz" + hex.drop(2), mainnet))
        assertNull(TransactionDecoder.decode("", mainnet))
        assertNull(TransactionDecoder.decode("00".repeat(80), mainnet))
    }
}
