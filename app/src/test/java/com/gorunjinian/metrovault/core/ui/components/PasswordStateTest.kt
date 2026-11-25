package com.gorunjinian.metrovault.core.ui.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A password field's text must be gone once it is wiped, undo history included. */
class PasswordStateTest {

    private fun typed(text: String) = TextFieldState().apply {
        text.forEach { char -> edit { append(char.toString()) } }
    }

    @Test
    fun wipeLeavesNothingToUndo() {
        val state = typed("correct horse")
        assertTrue("typing is recorded", state.undoState.canUndo)

        state.wipe()

        assertEquals("", state.text.toString())
        assertFalse(state.undoState.canUndo)
        assertFalse(state.undoState.canRedo)
    }

    @Test
    fun clearingAloneWouldKeepThePasswordInTheHistory() {
        // Why wipe exists: with a hardware keyboard, Ctrl+Z would bring the password back.
        val state = typed("secret")

        state.clearText()
        state.undoState.undo()

        assertEquals("secret", state.text.toString())
    }
}
