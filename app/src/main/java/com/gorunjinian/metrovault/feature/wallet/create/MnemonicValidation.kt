package com.gorunjinian.metrovault.feature.wallet.create

import com.gorunjinian.vaultovich.MnemonicCode

/**
 * BIP39 mnemonic validation (wordlist membership + checksum) shared by the
 * import wizards' ViewModels and the seed phrase entry step.
 */
fun isValidBip39Mnemonic(words: List<String>): Boolean = try {
    MnemonicCode.validate(words)
    true
} catch (_: Exception) {
    false
}

/**
 * All English wordlist words that complete [incompleteWords] into a mnemonic with a
 * valid BIP39 checksum, in wordlist (alphabetical) order.
 *
 * The last word carries the checksum plus a few free entropy bits, so the count is fixed:
 * - 11 words given → 12-word mnemonic: 7 free bits + 4 checksum bits → exactly 128 candidates
 * - 23 words given → 24-word mnemonic: 3 free bits + 8 checksum bits → exactly 8 candidates
 *
 * Returns an empty list when [incompleteWords] is not a valid prefix (wrong length, or a
 * word outside the wordlist). Candidates come from the same list the validator checks
 * against, so the two can never disagree.
 */
fun possibleLastWords(incompleteWords: List<String>): List<String> =
    MnemonicCode.englishWordlist.filter { isValidBip39Mnemonic(incompleteWords + it) }
