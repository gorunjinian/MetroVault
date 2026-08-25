package com.gorunjinian.metrovault.feature.wallet.create

import com.gorunjinian.metrovault.domain.service.bitcoin.MnemonicService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest
import kotlin.math.abs

class PhysicalEntropyTest {
    @Test
    fun `replacement entropy is draw count times log2 52`() {
        assertClose(5.700439718, PhysicalEntropy.entropyBits(EntropySource.CARDS, 1, true))
        assertClose(57.004397182, PhysicalEntropy.entropyBits(EntropySource.CARDS, 10, true))
        assertTrue(PhysicalEntropy.entropyBits(EntropySource.CARDS, 23, true) >= 128.0)
        assertTrue(PhysicalEntropy.entropyBits(EntropySource.CARDS, 45, true) >= 256.0)
    }

    @Test
    fun `full deck without replacement is about 225 point 58 bits`() {
        assertClose(225.581003124, PhysicalEntropy.entropyBits(EntropySource.CARDS, 52, false))
    }

    @Test
    fun `replacement allows duplicate cards`() {
        val state = PhysicalEntropyState(source = EntropySource.CARDS)
            .add(17)
            .add(17)
        assertEquals(listOf(17, 17), state.inputs)
    }

    @Test
    fun `without replacement rejects duplicate cards`() {
        val state = PhysicalEntropyState(source = EntropySource.CARDS, cardsWithReplacement = false)
            .add(17)
            .add(17)
        assertEquals(listOf(17), state.inputs)
    }

    @Test
    fun `source and card mode changes clear the sequence safely`() {
        val dice = PhysicalEntropyState().selectSource(EntropySource.DICE).add(6).add(1)
        val cards = dice.selectSource(EntropySource.CARDS)
        assertTrue(cards.inputs.isEmpty())
        assertTrue(cards.cardsWithReplacement)

        val withoutReplacement = cards.add(0).add(0).setCardsWithReplacement(false)
        assertFalse(withoutReplacement.cardsWithReplacement)
        assertTrue(withoutReplacement.inputs.isEmpty())

        val cardsAgain = withoutReplacement.selectSource(EntropySource.DICE)
            .selectSource(EntropySource.CARDS)
        assertTrue(cardsAgain.cardsWithReplacement)
        assertTrue(cardsAgain.inputs.isEmpty())
    }

    @Test
    fun `remove last and reset work for every source`() {
        EntropySource.entries.forEach { source ->
            val first = if (source == EntropySource.DICE) 1 else 0
            val second = if (source == EntropySource.DICE) 6 else 1
            val state = PhysicalEntropyState(source = source).add(first).add(second)
            assertEquals(listOf(first), state.removeLast().inputs)
            assertTrue(state.reset().inputs.isEmpty())
        }
    }

    @Test
    fun `canonical coin vector is stable`() {
        assertVector(
            PhysicalEntropyState(EntropySource.COIN, listOf(0, 1, 1, 0, 1)),
            "426974536177616e20706879736963616c20656e74726f707900010100000000050001010001",
            "75b5c17b34f89bcb2f655242874925f9bc63d20d38903a585f910b53532c08af"
        )
    }

    @Test
    fun `canonical dice vector is stable and keeps odd final roll`() {
        assertVector(
            PhysicalEntropyState(EntropySource.DICE, listOf(1, 6, 3, 2, 5)),
            "426974536177616e20706879736963616c20656e74726f707900010200000000050106030205",
            "6776399c985ece3c9c52bd6d12a6783b674b3fbf5fc6d7cc7545dcc282a42f1d"
        )
    }

    @Test
    fun `canonical cards vectors distinguish replacement modes`() {
        assertVector(
            PhysicalEntropyState(EntropySource.CARDS, listOf(0, 51, 0), cardsWithReplacement = true),
            "426974536177616e20706879736963616c20656e74726f70790001030100000003003300",
            "28a491d6f95e7aeb9b83dc9248ff9583e6db7b7813b578b896aefa5623938740"
        )
        assertVector(
            PhysicalEntropyState(EntropySource.CARDS, listOf(0, 13, 26, 39), cardsWithReplacement = false),
            "426974536177616e20706879736963616c20656e74726f70790001030200000004000d1a27",
            "2ac1eaef9f2b0f0c23d2e0fb57a827d38e8ef0874efbaaedaccf5b883995ee21"
        )
    }

    @Test
    fun `coin and dice deterministic payloads match offline converter`() {
        assertDeterministicPayload(
            PhysicalEntropyState(EntropySource.COIN, listOf(0, 1, 1, 0, 1)),
            "COIN:10010",
            "4a6918dc3c41f732237c6bcb1fbbf5838a0bd173ff1a9df8209ba919a1424aed"
        )
        assertDeterministicPayload(
            PhysicalEntropyState(EntropySource.DICE, listOf(1, 6, 3, 2, 5)),
            "DICE:16325",
            "43ad60c536ec4ff2521f3dfc6dabf057237fa153381d9611461187e311047cbd"
        )
    }

    @Test
    fun `card deterministic payloads are frozen and domain separated`() {
        assertDeterministicPayload(
            PhysicalEntropyState(EntropySource.CARDS, listOf(0, 51, 0), cardsWithReplacement = true),
            "CARDS-WITH-REPLACEMENT-V1:00,51,00",
            "1af2168d2fe1a8a9950c69b946dd14ecd51b6f8f66d2d2b832a7e406a5f6c73c"
        )
        assertDeterministicPayload(
            PhysicalEntropyState(EntropySource.CARDS, listOf(0, 13, 26, 39), cardsWithReplacement = false),
            "CARDS-WITHOUT-REPLACEMENT-V1:00,13,26,39",
            "7fd828b6abe18800f26f7e3c0a060d45dda9c1d53f3098796f2a5e9fbd8c18cf"
        )
    }

    @Test
    fun `physical only requires the selected mnemonic entropy strength`() {
        assertFalse(PhysicalEntropy.hasRequiredEntropy(PhysicalEntropyState(), 12))
        assertFalse(PhysicalEntropy.hasRequiredEntropy(PhysicalEntropyState(EntropySource.COIN, List(127) { 0 }), 12))
        assertTrue(PhysicalEntropy.hasRequiredEntropy(PhysicalEntropyState(EntropySource.COIN, List(128) { 0 }), 12))
        assertFalse(PhysicalEntropy.hasRequiredEntropy(PhysicalEntropyState(EntropySource.CARDS, List(22) { 0 }), 12))
        assertTrue(PhysicalEntropy.hasRequiredEntropy(PhysicalEntropyState(EntropySource.CARDS, List(23) { 0 }), 12))
        assertFalse(
            PhysicalEntropy.hasRequiredEntropy(
                PhysicalEntropyState(EntropySource.CARDS, (0..51).toList(), cardsWithReplacement = false),
                24
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            PhysicalEntropy.deterministicBip39Entropy(
                PhysicalEntropyState(EntropySource.COIN, List(127) { 0 }),
                12
            )
        }
    }

    @Test
    fun `remove at deletes only the selected captured input`() {
        val state = PhysicalEntropyState(EntropySource.DICE, listOf(1, 6, 3, 6))

        assertEquals(listOf(1, 3, 6), state.removeAt(1).inputs)
        assertEquals(state, state.removeAt(-1))
        assertEquals(state, state.removeAt(4))
    }

    @Test
    fun `wallet creation defaults to device mixed and gates physical only`() {
        val defaultState = CreateWalletViewModel.UiState()
        assertEquals(PhysicalEntropyMode.MIX_WITH_DEVICE, defaultState.physicalEntropyMode)
        assertTrue(defaultState.canGenerateMnemonic)

        val insufficient = defaultState.copy(
            physicalEntropyMode = PhysicalEntropyMode.PHYSICAL_ONLY,
            physicalEntropy = PhysicalEntropyState(EntropySource.COIN, List(127) { 0 })
        )
        assertFalse(insufficient.canGenerateMnemonic)
        assertTrue(insufficient.copy(physicalEntropy = insufficient.physicalEntropy.add(0)).canGenerateMnemonic)
    }

    @Test
    fun `wallet draft detection ignores untouched defaults and catches wizard progress`() {
        assertFalse(CreateWalletViewModel.UiState().hasUnsavedDraft)
        assertTrue(CreateWalletViewModel.UiState(currentStep = 2).hasUnsavedDraft)
        assertTrue(CreateWalletViewModel.UiState(hasShownEntropyInfo = true).hasUnsavedDraft)
        assertTrue(CreateWalletViewModel.UiState(wordCount = 24).hasUnsavedDraft)
        assertTrue(
            CreateWalletViewModel.UiState(
                physicalEntropy = PhysicalEntropyState(EntropySource.DICE, listOf(6))
            ).hasUnsavedDraft
        )
    }

    @Test
    fun `converter compatible coin sequence produces stable 12 word mnemonic`() {
        val state = PhysicalEntropyState(EntropySource.COIN, List(128) { 0 })
        val entropy = PhysicalEntropy.deterministicBip39Entropy(state, 12)
        try {
            assertEquals("c66758ff0cf8badb6a03c6f81f832b6e", entropy.toHex())
            assertEquals(
                "shoe depart divert boring merry horror pool juice way winter skull syrup",
                MnemonicService().generateMnemonicFromEntropy(12, entropy).joinToString(" ")
            )
        } finally {
            entropy.fill(0)
        }
    }

    @Test
    fun `converter compatible dice sequence produces stable 24 word mnemonic`() {
        val state = PhysicalEntropyState(EntropySource.DICE, List(100) { 1 })
        val entropy = PhysicalEntropy.deterministicBip39Entropy(state, 24)
        try {
            assertEquals("9a6bf1da5140b9ab185e407aec7e558d1326c474019bee28aca1506a58482227", entropy.toHex())
            assertEquals(
                "omit garbage isolate penalty argue stereo gesture siege kit glue nice boss crash giraffe source cricket until earth choose patch pitch catch mass usual",
                MnemonicService().generateMnemonicFromEntropy(24, entropy).joinToString(" ")
            )
        } finally {
            entropy.fill(0)
        }
    }

    @Test
    fun `card replacement sequence produces stable 24 word mnemonic`() {
        val state = PhysicalEntropyState(EntropySource.CARDS, List(45) { 0 })
        val entropy = PhysicalEntropy.deterministicBip39Entropy(state, 24)
        try {
            assertEquals("f3ce156c55ea5abb9dac5ef0c08555254822d271b83ad991519b23526acc06fb", entropy.toHex())
            assertEquals(
                "video idle force profit pizza fruit issue mesh valid aerobic fetch enhance lion hard shoulder also sunset melody grocery effort chase gravity brief grit",
                MnemonicService().generateMnemonicFromEntropy(24, entropy).joinToString(" ")
            )
        } finally {
            entropy.fill(0)
        }
    }

    private fun assertVector(state: PhysicalEntropyState, serializationHex: String, hashHex: String) {
        assertArrayEquals(serializationHex.hexToBytes(), PhysicalEntropy.canonicalSerialize(state))
        assertArrayEquals(hashHex.hexToBytes(), PhysicalEntropy.normalizedHash(state))
    }

    private fun assertDeterministicPayload(state: PhysicalEntropyState, payload: String, hashHex: String) {
        val bytes = PhysicalEntropy.deterministicPayload(state)
        try {
            assertEquals(payload, bytes.toString(Charsets.US_ASCII))
            assertEquals(hashHex, MessageDigest.getInstance("SHA-256").digest(bytes).toHex())
        } finally {
            bytes.fill(0)
        }
    }

    private fun assertClose(expected: Double, actual: Double) {
        assertTrue("expected $expected, got $actual", abs(expected - actual) < 1e-9)
    }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
