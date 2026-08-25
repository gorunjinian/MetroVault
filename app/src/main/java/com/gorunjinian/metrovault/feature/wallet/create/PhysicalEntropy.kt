package com.gorunjinian.metrovault.feature.wallet.create

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.log2

/** Physical entropy sources supported by the wallet-creation wizard. */
enum class EntropySource(val wireId: Byte) {
    COIN(1),
    DICE(2),
    CARDS(3)
}

enum class PhysicalEntropyMode {
    MIX_WITH_DEVICE,
    PHYSICAL_ONLY
}

enum class CardSuit(val symbol: String) {
    CLUBS("♣"),
    DIAMONDS("♦"),
    HEARTS("♥"),
    SPADES("♠");

    val isRed: Boolean get() = this == DIAMONDS || this == HEARTS

    val displayName: String get() = name.lowercase().replaceFirstChar { it.titlecase() }
}

/**
 * Canonical card IDs are suit-major in [CardSuit] order, then rank-major in [RANKS] order.
 */
data class PlayingCard(val id: Int, val suit: CardSuit, val rank: String) {
    val label: String get() = "$rank${suit.symbol}"

    companion object {
        val RANKS = listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")
        val DECK: List<PlayingCard> = CardSuit.entries.flatMapIndexed { suitIndex, suit ->
            RANKS.mapIndexed { rankIndex, rank ->
                PlayingCard(suitIndex * RANKS.size + rankIndex, suit, rank)
            }
        }

        fun fromId(id: Int): PlayingCard = DECK[id]
    }
}

/**
 * Pure state for physical entropy entry. Source or card-mode changes clear the old sequence so
 * symbols can never be interpreted under a different serialization mode.
 */
data class PhysicalEntropyState(
    val source: EntropySource? = null,
    val inputs: List<Int> = emptyList(),
    val cardsWithReplacement: Boolean = true
) {
    val bitsCollected: Double get() = PhysicalEntropy.entropyBits(source, inputs.size, cardsWithReplacement)

    fun selectSource(newSource: EntropySource): PhysicalEntropyState =
        if (source == newSource) this else copy(
            source = newSource,
            inputs = emptyList(),
            cardsWithReplacement = if (newSource == EntropySource.CARDS) true else cardsWithReplacement
        )

    fun setCardsWithReplacement(enabled: Boolean): PhysicalEntropyState =
        if (cardsWithReplacement == enabled) this
        else copy(cardsWithReplacement = enabled, inputs = emptyList())

    fun add(value: Int): PhysicalEntropyState {
        val activeSource = source ?: return this
        if (!PhysicalEntropy.isValidSymbol(activeSource, value)) return this
        if (activeSource == EntropySource.CARDS && !cardsWithReplacement && value in inputs) return this
        return copy(inputs = inputs + value)
    }

    fun removeLast(): PhysicalEntropyState = copy(inputs = inputs.dropLast(1))

    fun removeAt(index: Int): PhysicalEntropyState =
        if (index in inputs.indices) copy(inputs = inputs.filterIndexed { inputIndex, _ -> inputIndex != index })
        else this

    fun reset(): PhysicalEntropyState = copy(inputs = emptyList())
}

/**
 * Frozen physical-entropy serialization, version 1:
 *
 * `"BitSawan physical entropy" || 0x00 || version || source || mode || count_be32 || symbols`
 *
 * Source IDs: coin=1, dice=2, cards=3. Mode is 0 for coin/dice, 1 for cards with
 * replacement, and 2 for cards without replacement. Symbols are one byte each: coin 0/1, dice
 * 1..6, and canonical card ID 0..51. In the recommended mode, the serialization is
 * SHA-256-normalized before MnemonicService independently mixes it with full-size SecureRandom
 * output. The separately documented deterministic formats are used only for physical-only mode.
 */
object PhysicalEntropy {
    private val DOMAIN = "BitSawan physical entropy".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
    private const val VERSION: Byte = 1

    fun entropyBits(source: EntropySource?, count: Int, cardsWithReplacement: Boolean = true): Double =
        when (source) {
            EntropySource.COIN -> count.toDouble()
            EntropySource.DICE -> count * log2(6.0)
            EntropySource.CARDS -> if (cardsWithReplacement) {
                count * log2(52.0)
            } else {
                (0 until count.coerceAtMost(52)).sumOf { draw -> log2((52 - draw).toDouble()) }
            }
            null -> 0.0
        }

    fun isValidSymbol(source: EntropySource, value: Int): Boolean = when (source) {
        EntropySource.COIN -> value in 0..1
        EntropySource.DICE -> value in 1..6
        EntropySource.CARDS -> value in 0..51
    }

    fun canonicalSerialize(state: PhysicalEntropyState): ByteArray {
        val source = requireNotNull(state.source) { "Entropy source is required" }
        require(state.inputs.all { isValidSymbol(source, it) }) { "Invalid entropy symbol" }
        require(source != EntropySource.CARDS || state.cardsWithReplacement || state.inputs.distinct().size == state.inputs.size) {
            "Duplicate card without replacement"
        }

        val mode: Byte = when {
            source != EntropySource.CARDS -> 0
            state.cardsWithReplacement -> 1
            else -> 2
        }
        val header = ByteBuffer.allocate(DOMAIN.size + 1 + 1 + 1 + Int.SIZE_BYTES)
            .put(DOMAIN)
            .put(VERSION)
            .put(source.wireId)
            .put(mode)
            .putInt(state.inputs.size)
            .array()
        return header + state.inputs.map { it.toByte() }.toByteArray()
    }

    fun normalizedHash(state: PhysicalEntropyState): ByteArray {
        val serialized = canonicalSerialize(state)
        return try {
            MessageDigest.getInstance("SHA-256").digest(serialized)
        } finally {
            serialized.fill(0)
        }
    }

    fun hasRequiredEntropy(state: PhysicalEntropyState, wordCount: Int): Boolean {
        val requiredBits = if (wordCount == 12) 128 else 256
        return state.source != null && state.bitsCollected >= requiredBits
    }

    /**
     * Deterministic physical-only BIP39 entropy.
     *
     * Coin and dice payloads deliberately match `bitcoin_seed_converter_fully_offline.html`:
     * `COIN:` followed by Heads=1/Tails=0 bits, or `DICE:` followed by roll digits. Cards use the
     * frozen ASCII formats `CARDS-WITH-REPLACEMENT-V1:` and
     * `CARDS-WITHOUT-REPLACEMENT-V1:` followed by comma-separated, zero-padded canonical IDs.
     */
    fun deterministicBip39Entropy(state: PhysicalEntropyState, wordCount: Int): ByteArray {
        require(wordCount == 12 || wordCount == 24) { "Physical-only generation supports 12 or 24 words" }
        require(hasRequiredEntropy(state, wordCount)) { "Insufficient physical entropy" }

        val payload = deterministicPayload(state)
        var digest: ByteArray? = null
        return try {
            digest = MessageDigest.getInstance("SHA-256").digest(payload)
            digest.copyOf(if (wordCount == 12) 16 else 32)
        } finally {
            payload.fill(0)
            digest?.fill(0)
        }
    }

    internal fun deterministicPayload(state: PhysicalEntropyState): ByteArray {
        val source = requireNotNull(state.source) { "Entropy source is required" }
        require(state.inputs.all { isValidSymbol(source, it) }) { "Invalid entropy symbol" }
        require(source != EntropySource.CARDS || state.cardsWithReplacement || state.inputs.distinct().size == state.inputs.size) {
            "Duplicate card without replacement"
        }

        val prefix = when (source) {
            EntropySource.COIN -> "COIN:"
            EntropySource.DICE -> "DICE:"
            EntropySource.CARDS -> if (state.cardsWithReplacement) {
                "CARDS-WITH-REPLACEMENT-V1:"
            } else {
                "CARDS-WITHOUT-REPLACEMENT-V1:"
            }
        }.toByteArray(Charsets.US_ASCII)

        val symbolSize = when (source) {
            EntropySource.COIN, EntropySource.DICE -> state.inputs.size
            EntropySource.CARDS -> state.inputs.size * 2 + (state.inputs.size - 1).coerceAtLeast(0)
        }
        return ByteArray(prefix.size + symbolSize).also { output ->
            prefix.copyInto(output)
            var offset = prefix.size
            state.inputs.forEachIndexed { index, value ->
                when (source) {
                    EntropySource.COIN -> output[offset++] = if (value == 0) '1'.code.toByte() else '0'.code.toByte()
                    EntropySource.DICE -> output[offset++] = ('0'.code + value).toByte()
                    EntropySource.CARDS -> {
                        if (index > 0) output[offset++] = ','.code.toByte()
                        output[offset++] = ('0'.code + value / 10).toByte()
                        output[offset++] = ('0'.code + value % 10).toByte()
                    }
                }
            }
        }
    }
}
