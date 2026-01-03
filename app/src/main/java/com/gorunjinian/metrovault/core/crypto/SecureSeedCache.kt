package com.gorunjinian.metrovault.core.crypto

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Secure cache for BIP39 seeds that ensures proper memory wiping.
 *
 * Unlike storing seeds as String (which are immutable and can't be wiped),
 * this class stores seeds as SecureByteArray which can be zeroed out
 * when no longer needed.
 *
 * Security guarantees:
 * - Seeds are stored as byte arrays, not Strings
 * - When a seed is removed or replaced, the old data is zeroed
 * - When clear() is called, ALL seed data is zeroed immediately
 * - Thread-safe via ConcurrentHashMap
 *
 * Usage:
 * ```
 * val cache = SecureSeedCache()
 * cache.store("wallet-id", "hex-seed-string")
 * val seed = cache.get("wallet-id")  // Returns the seed or null
 * cache.clear()  // Zeros all seeds immediately
 * ```
 */
class SecureSeedCache {

    companion object {
        private const val TAG = "SecureSeedCache"
    }

    private val cache = ConcurrentHashMap<String, SecureByteArray>()

    /**
     * Stores a seed for the given wallet ID.
     * If a seed already exists for this ID, the old one is securely wiped first.
     *
     * @param walletId Unique wallet identifier
     * @param seedHex Hex-encoded BIP39 seed (128 characters for 64 bytes)
     */
    fun store(walletId: String, seedHex: String) {
        // Convert hex string to bytes for secure storage
        val seedBytes = seedHex.toByteArray(Charsets.UTF_8)

        try {
            // Create secure storage
            val secureArray = SecureByteArray(seedBytes.size)
            secureArray.copyFrom(seedBytes)

            // Wipe any existing seed for this wallet before replacing
            cache[walletId]?.close()

            // Store the new secure seed
            cache[walletId] = secureArray
        } finally {
            // Always wipe the intermediate byte array
            seedBytes.fill(0)
        }
    }

    /**
     * Retrieves a seed for the given wallet ID.
     *
     * Note: This returns a new String each time. The caller should
     * minimize how long they hold onto this reference.
     *
     * @param walletId Unique wallet identifier
     * @return Hex-encoded seed string, or null if not found
     */
    fun get(walletId: String): String? {
        val secureArray = cache[walletId] ?: return null

        return try {
            secureArray.asString(Charsets.UTF_8)
        } catch (_: IllegalStateException) {
            // SecureByteArray was already closed
            Log.w(TAG, "Attempted to read closed seed for wallet: $walletId")
            cache.remove(walletId)
            null
        }
    }

    /**
     * Checks if a seed exists for the given wallet ID.
     */
    fun containsKey(walletId: String): Boolean {
        return cache.containsKey(walletId)
    }

    /**
     * Removes and securely wipes the seed for a specific wallet.
     *
     * @param walletId Unique wallet identifier
     * @return true if a seed was removed, false if none existed
     */
    fun remove(walletId: String): Boolean {
        val removed = cache.remove(walletId)
        removed?.close()
        if (removed != null) {
            Log.d(TAG, "Removed and wiped seed for wallet: $walletId")
        }
        return removed != null
    }

    /**
     * Returns the number of cached seeds.
     */
    val size: Int
        get() = cache.size

    /**
     * Securely wipes ALL cached seeds immediately.
     * This zeros out all seed data in memory before removing references.
     */
    fun clear() {
        val count = cache.size
        if (count > 0) {
            // First, wipe all secure arrays
            cache.values.forEach { secureArray ->
                try {
                    secureArray.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing SecureByteArray during clear: ${e.message}")
                }
            }
            // Then clear the map
            cache.clear()
            Log.d(TAG, "Cleared and wiped $count seed(s) from cache")
        }
    }

    /**
     * Called during finalization to ensure cleanup.
     * Note: Always call clear() explicitly - don't rely on this.
     */
    protected fun finalize() {
        if (cache.isNotEmpty()) {
            Log.w(TAG, "SecureSeedCache finalized with ${cache.size} seeds - should call clear() explicitly")
            clear()
        }
    }
}
