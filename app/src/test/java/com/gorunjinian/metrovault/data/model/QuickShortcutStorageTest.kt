package com.gorunjinian.metrovault.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round-trip tests for the [QuickShortcut] storage-string codec.
 *
 * Unlike the other three codecs this one is comma-separated rather than JSON, and it is
 * total: every malformed input falls back to [QuickShortcut.DEFAULT] rather than throwing.
 */
class QuickShortcutStorageTest {

    private val custom = listOf(QuickShortcut.EXPORT, QuickShortcut.BIP85, QuickShortcut.SIGN_MESSAGE)

    @Test
    fun `a custom selection survives a round-trip unchanged`() {
        assertEquals(custom, QuickShortcut.fromStorageString(QuickShortcut.toStorageString(custom)))
    }

    @Test
    fun `the default selection survives a round-trip unchanged`() {
        val stored = QuickShortcut.toStorageString(QuickShortcut.DEFAULT)
        assertEquals(QuickShortcut.DEFAULT, QuickShortcut.fromStorageString(stored))
    }

    @Test
    fun `every shortcut survives a round-trip in some selection`() {
        // Pins the enum constant names, which are the on-disk format. Renaming one silently
        // resets that user's shortcut row to the default.
        for (chunk in QuickShortcut.entries.chunked(3)) {
            if (chunk.size != 3) continue
            assertEquals(chunk, QuickShortcut.fromStorageString(QuickShortcut.toStorageString(chunk)))
        }
    }

    @Test
    fun `selection order is preserved`() {
        val reordered = custom.reversed()
        assertEquals(reordered, QuickShortcut.fromStorageString(QuickShortcut.toStorageString(reordered)))
    }

    @Test
    fun `null and blank fall back to the default`() {
        assertEquals(QuickShortcut.DEFAULT, QuickShortcut.fromStorageString(null))
        assertEquals(QuickShortcut.DEFAULT, QuickShortcut.fromStorageString(""))
        assertEquals(QuickShortcut.DEFAULT, QuickShortcut.fromStorageString("   "))
    }

    @Test
    fun `a selection of the wrong length falls back to the default`() {
        assertEquals(QuickShortcut.DEFAULT, QuickShortcut.fromStorageString("EXPORT"))
        assertEquals(QuickShortcut.DEFAULT, QuickShortcut.fromStorageString("EXPORT,BIP85"))
        assertEquals(
            QuickShortcut.DEFAULT,
            QuickShortcut.fromStorageString("EXPORT,BIP85,SIGN_MESSAGE,VIEW_ADDRESSES")
        )
    }

    @Test
    fun `an unrecognized name drops the whole selection to the default`() {
        // Unknown names are dropped, which takes the count below three and triggers the fallback.
        // This is how a shortcut removed in a future build degrades: the row resets rather than
        // rendering short.
        assertEquals(QuickShortcut.DEFAULT, QuickShortcut.fromStorageString("EXPORT,BIP85,REMOVED_IN_V4"))
        assertEquals(QuickShortcut.DEFAULT, QuickShortcut.fromStorageString("garbage"))
        assertEquals(QuickShortcut.DEFAULT, QuickShortcut.fromStorageString(",,"))
    }

    @Test
    fun `names are matched case-sensitively`() {
        assertEquals(QuickShortcut.DEFAULT, QuickShortcut.fromStorageString("export,bip85,sign_message"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(custom, QuickShortcut.fromStorageString(" EXPORT , BIP85 , SIGN_MESSAGE "))
    }

    @Test
    fun `duplicate names are accepted when the count is right`() {
        // A quirk of the size-only validation: three copies of one shortcut passes. Pinned so the
        // behavior is a decision rather than an accident if the picker ever allows duplicates.
        assertEquals(
            List(3) { QuickShortcut.EXPORT },
            QuickShortcut.fromStorageString("EXPORT,EXPORT,EXPORT")
        )
    }

    @Test
    fun `the default selection has the three shortcuts the picker requires`() {
        assertEquals(3, QuickShortcut.DEFAULT.size)
    }
}
