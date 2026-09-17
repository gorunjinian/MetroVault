package com.gorunjinian.metrovault.feature.wallet.create

import com.gorunjinian.vaultovich.MnemonicCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MnemonicValidationTest {

    private val abandon11 = List(11) { "abandon" }
    private val abandon23 = List(23) { "abandon" }

    // ========== isValidBip39Mnemonic ==========

    @Test
    fun acceptsCanonicalVectors() {
        assertTrue(isValidBip39Mnemonic(abandon11 + "about"))
        assertTrue(isValidBip39Mnemonic(abandon23 + "art"))
    }

    @Test
    fun rejectsBadChecksumWrongLengthAndUnknownWords() {
        assertFalse("bad checksum", isValidBip39Mnemonic(abandon11 + "abandon"))
        assertFalse("11 words", isValidBip39Mnemonic(abandon11))
        assertFalse("empty", isValidBip39Mnemonic(emptyList()))
        assertFalse("unknown word", isValidBip39Mnemonic(abandon11 + "notaword"))
        assertFalse("wrong case", isValidBip39Mnemonic(abandon11 + "About"))
    }

    // ========== possibleLastWords ==========

    @Test
    fun elevenWordPrefixHasExactly128CompletionsAllValid() {
        val candidates = possibleLastWords(abandon11)

        // 7 free entropy bits + 4 checksum bits -> 2^7 candidates
        assertEquals(128, candidates.size)
        assertTrue(candidates.contains("about"))
        assertTrue(candidates.all { isValidBip39Mnemonic(abandon11 + it) })
    }

    @Test
    fun twentyThreeWordPrefixHasExactly8CompletionsAllValid() {
        val candidates = possibleLastWords(abandon23)

        // 3 free entropy bits + 8 checksum bits -> 2^3 candidates
        assertEquals(8, candidates.size)
        assertTrue(candidates.contains("art"))
        assertTrue(candidates.all { isValidBip39Mnemonic(abandon23 + it) })
    }

    @Test
    fun completionsAreInWordlistOrder() {
        val candidates = possibleLastWords(abandon11)
        val wordlistIndex = MnemonicCode.englishWordlist.withIndex().associate { it.value to it.index }
        val indices = candidates.map { wordlistIndex.getValue(it) }
        assertEquals(indices.sorted(), indices)
    }

    @Test
    fun droppedLastWordOfRandomMnemonicIsAlwaysRecovered() {
        repeat(3) {
            val entropy = ByteArray(16).also { Random.nextBytes(it) }
            val mnemonic = MnemonicCode.toMnemonics(entropy)
            val prefix = mnemonic.dropLast(1)

            val candidates = possibleLastWords(prefix)
            assertEquals(128, candidates.size)
            assertTrue(candidates.contains(mnemonic.last()))
        }
    }

    @Test
    fun invalidPrefixYieldsNoCompletions() {
        assertTrue("10 words", possibleLastWords(abandon11.dropLast(1)).isEmpty())
        assertTrue("12 words (already complete)", possibleLastWords(abandon11 + "about").isEmpty())
        assertTrue("unknown word", possibleLastWords(abandon11.dropLast(1) + "notaword").isEmpty())
        assertTrue("empty", possibleLastWords(emptyList()).isEmpty())
    }
}
