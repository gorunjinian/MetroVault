package com.gorunjinian.metrovault.data.model

/**
 * Rules for the per-chain "start the address list here" preference shown on the Addresses screen.
 *
 * The screen loads addresses in fixed batches of [BATCH_SIZE] starting at index 0. A wallet whose
 * used addresses sit far past the first batch would otherwise need many "Show More" taps on every
 * visit, so the user can pick an index per chain (receive / change) and the list opens at the
 * batch containing it, with "Show Earlier" available to walk back toward index 0.
 *
 * This is purely a display preference. It is never inferred from signed PSBTs or anything else the
 * device observes, and it says nothing about which addresses are actually used.
 */
object AddressStartIndex {
    /** Number of addresses loaded per "Show More" / "Show Earlier" step. */
    const val BATCH_SIZE = 20

    /** Largest index the user may enter. Well past any realistic gap, and still derives instantly. */
    const val MAX = 999_999

    /**
     * Normalises raw user input into the value that gets stored.
     *
     * Anything below one batch is indistinguishable from the default view (indices 0 to 19 are
     * shown either way), so it collapses to 0, which is also how the user resets the preference.
     * Values above [MAX] are clamped rather than rejected.
     */
    fun normalize(input: Int): Int = when {
        input < BATCH_SIZE -> 0
        input > MAX -> MAX
        else -> input
    }

    /** First index of the batch that contains [index], e.g. 56 -> 40, 25 -> 20, 20 -> 20. */
    fun batchStart(index: Int): Int = (index.coerceAtLeast(0) / BATCH_SIZE) * BATCH_SIZE
}
