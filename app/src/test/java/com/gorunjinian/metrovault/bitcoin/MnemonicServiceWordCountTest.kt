package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.domain.service.bitcoin.MnemonicService
import com.gorunjinian.vaultovich.MnemonicCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Mnemonic generation covers exactly the word counts vaultovich accepts. */
class MnemonicServiceWordCountTest {

    private val service = MnemonicService()

    @Test
    fun everyValidWordCountGeneratesAPhraseOfThatLength() {
        MnemonicCode.VALID_WORD_COUNTS.forEach { count ->
            val words = service.generateMnemonicWithUserEntropy(count, userEntropy = byteArrayOf(1, 2, 3))
            assertEquals(count, words.size)
            MnemonicCode.validate(words)
        }
    }

    @Test
    fun unsupportedWordCountsAreRefused() {
        // Used to fall back to a 24-word phrase without saying so
        listOf(0, 9, 13, 27).forEach { count ->
            assertThrows(IllegalArgumentException::class.java) {
                service.generateMnemonicWithUserEntropy(count, userEntropy = null)
            }
        }
    }
}
