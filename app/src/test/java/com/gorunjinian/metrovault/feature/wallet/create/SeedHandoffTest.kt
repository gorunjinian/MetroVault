package com.gorunjinian.metrovault.feature.wallet.create

import android.app.Application
import com.gorunjinian.metrovault.domain.service.scan.CompletedScan
import com.gorunjinian.metrovault.domain.service.scan.ScannedPayload
import com.gorunjinian.metrovault.domain.service.scan.SeedFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The import wizards opened with a seed that was already scanned. Both must behave alike: step 1
 * still asks for the address type, and going back to it doesn't lose words that can't be typed
 * again without scanning.
 */
class SeedHandoffTest {

    private val words12 = List(11) { "abandon" } + "about"
    private val words24 = List(23) { "abandon" } + "art"

    private fun seed(words: List<String>) = CompletedScan(ScannedPayload.Seed(words, SeedFormat.COMPACT_SEEDQR), null)

    // ==================== Permanent import ====================

    @Test
    fun importStartsAtStepOneWithTheWordsInPlace() {
        for (words in listOf(words12, words24)) {
            val viewModel = ImportWalletViewModel(Application())
            viewModel.onScanHandedOver(seed(words))

            val state = viewModel.uiState.value
            assertEquals(1, state.currentStep)
            assertEquals(words, state.mnemonicWords)
            assertEquals(words.size, state.expectedWordCount)
            assertTrue(state.isMnemonicValid)
            assertTrue(state.isSeedHandedOver)
        }
    }

    @Test
    fun importKeepsHandedOverWordsWhenGoingBackToStepOne() {
        val viewModel = ImportWalletViewModel(Application())
        viewModel.onScanHandedOver(seed(words12))
        viewModel.goToNextStep()

        viewModel.goToPreviousStep()

        assertEquals(1, viewModel.uiState.value.currentStep)
        assertEquals(words12, viewModel.uiState.value.mnemonicWords)
    }

    @Test
    fun importTreatsEditedWordsAsTyped() {
        val viewModel = ImportWalletViewModel(Application())
        viewModel.onScanHandedOver(seed(words12))
        viewModel.goToNextStep()

        // What the word field does when a word is removed
        viewModel.clearMnemonic()
        words12.drop(1).forEach { viewModel.addWord(it) }
        assertFalse(viewModel.uiState.value.isSeedHandedOver)

        viewModel.goToPreviousStep()
        assertTrue(viewModel.uiState.value.mnemonicWords.isEmpty())
    }

    @Test
    fun importStillClearsTypedWordsWhenGoingBackToStepOne() {
        val viewModel = ImportWalletViewModel(Application())
        viewModel.goToNextStep()
        words12.forEach { viewModel.addWord(it) }

        viewModel.goToPreviousStep()

        assertTrue(viewModel.uiState.value.mnemonicWords.isEmpty())
    }

    @Test
    fun importIgnoresAScanThatIsNotASeed() {
        val viewModel = ImportWalletViewModel(Application())
        val before = viewModel.uiState.value

        viewModel.onScanHandedOver(CompletedScan(ScannedPayload.Text("hello"), "hello"))

        assertEquals(before, viewModel.uiState.value)
    }

    // ==================== Stateless import ====================

    @Test
    fun statelessStartsAtStepOneWithTheWordsInPlace() {
        for (words in listOf(words12, words24)) {
            val viewModel = ImportStatelessViewModel(Application())
            viewModel.onScanHandedOver(seed(words))

            val state = viewModel.uiState.value
            assertEquals(1, state.currentStep)
            assertEquals(words, state.mnemonicWords)
            assertEquals(words.size, state.expectedWordCount)
            assertTrue(state.isMnemonicValid)
            assertTrue(state.isSeedHandedOver)
        }
    }

    @Test
    fun statelessKeepsHandedOverWordsWhenGoingBackToStepOne() {
        val viewModel = ImportStatelessViewModel(Application())
        viewModel.onScanHandedOver(seed(words12))
        viewModel.goToNextStep()

        viewModel.goToPreviousStep()

        assertEquals(1, viewModel.uiState.value.currentStep)
        assertEquals(words12, viewModel.uiState.value.mnemonicWords)
    }

    @Test
    fun statelessTreatsEditedWordsAsTyped() {
        val viewModel = ImportStatelessViewModel(Application())
        viewModel.onScanHandedOver(seed(words12))
        viewModel.goToNextStep()

        viewModel.clearMnemonic()
        words12.drop(1).forEach { viewModel.addWord(it) }
        assertFalse(viewModel.uiState.value.isSeedHandedOver)

        viewModel.goToPreviousStep()
        assertTrue(viewModel.uiState.value.mnemonicWords.isEmpty())
    }

    @Test
    fun statelessStillClearsTypedWordsWhenGoingBackToStepOne() {
        val viewModel = ImportStatelessViewModel(Application())
        viewModel.goToNextStep()
        words12.forEach { viewModel.addWord(it) }

        viewModel.goToPreviousStep()

        assertTrue(viewModel.uiState.value.mnemonicWords.isEmpty())
    }

    @Test
    fun statelessIgnoresAScanThatIsNotASeed() {
        val viewModel = ImportStatelessViewModel(Application())
        val before = viewModel.uiState.value

        viewModel.onScanHandedOver(CompletedScan(ScannedPayload.Text("hello"), "hello"))

        assertEquals(before, viewModel.uiState.value)
    }
}
