package com.gorunjinian.metrovault.lib.bitcoin

import android.content.Context

/**
 * Utility class for accessing BIP39 wordlist.
 * 
 * Note: Context parameter is kept for backward compatibility with existing callers,
 * but the wordlist is now loaded from the compiled BIP39EnglishWords constant.
 */
@Suppress("UNUSED_PARAMETER")
object BIP39Wordlist {

    /**
     * Gets the English BIP39 wordlist.
     * 
     * @param context Android context (unused, kept for API compatibility)
     * @return List of 2048 BIP39 English words
     */
    fun getEnglishWordlist(context: Context): List<String> = BIP39_ENGLISH_WORDLIST

    /**
     * Gets words that match a given prefix (useful for autocomplete).
     *
     * @param context Android context (unused, kept for API compatibility)
     * @param prefix Word prefix to match
     * @return List of matching words
     */
    fun getWordsWithPrefix(context: Context, prefix: String): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val lowercasePrefix = prefix.lowercase()
        return BIP39_ENGLISH_WORDLIST.filter { it.startsWith(lowercasePrefix) }
    }
}
