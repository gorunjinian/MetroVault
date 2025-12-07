package com.gorunjinian.metrovault.lib.bitcoin

import android.content.Context
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Utility class for loading and accessing BIP39 wordlists.
 * Supports multiple languages, currently using English.
 */
object BIP39Wordlist {

    private var cachedEnglishWords: List<String>? = null

    /**
     * Loads the English BIP39 wordlist from assets.
     * The wordlist is cached after first load for performance.
     *
     * @param context Android context for accessing resources
     * @return List of 2048 BIP39 English words
     */
    fun getEnglishWordlist(context: Context): List<String> {
        // Return cached wordlist if available
        cachedEnglishWords?.let { return it }

        try {
            // Read the JSON file from the raw resources or assets
            val inputStream = context.assets.open("bip39Wordlists/english.json")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonString = reader.readText()
            reader.close()

            // Parse JSON array
            val jsonArray = JSONArray(jsonString)
            val wordlist = mutableListOf<String>()

            for (i in 0 until jsonArray.length()) {
                wordlist.add(jsonArray.getString(i))
            }

            // Cache the wordlist
            cachedEnglishWords = wordlist
            return wordlist

        } catch (e: Exception) {
            // If loading from assets fails, try loading from raw package path
            // This fallback tries to read directly from the source package
            android.util.Log.e("BIP39Wordlist", "Failed to load wordlist from assets: ${e.message}", e)

            // Return empty list as fallback
            return emptyList()
        }
    }

    /**
     * Clears the cached wordlist to free memory.
     * Call this if memory is constrained.
     */
    fun clearCache() {
        cachedEnglishWords = null
    }

    /**
     * Validates if a word exists in the BIP39 English wordlist.
     *
     * @param context Android context
     * @param word Word to validate
     * @return true if word exists in wordlist
     */
    fun isValidWord(context: Context, word: String): Boolean {
        return getEnglishWordlist(context).contains(word.lowercase())
    }

    /**
     * Gets words that match a given prefix (useful for autocomplete).
     *
     * @param context Android context
     * @param prefix Word prefix to match
     * @return List of matching words
     */
    fun getWordsWithPrefix(context: Context, prefix: String): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val lowercasePrefix = prefix.lowercase()
        return getEnglishWordlist(context).filter { it.startsWith(lowercasePrefix) }
    }
}
