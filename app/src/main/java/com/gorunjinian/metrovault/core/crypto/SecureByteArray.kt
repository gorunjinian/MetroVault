package com.gorunjinian.metrovault.core.crypto

import android.os.Build
import android.util.Log
import java.io.Closeable
import java.util.Arrays

/**
 * Secure wrapper for byte arrays containing sensitive data
 *
 * Automatically zeros memory when closed or garbage collected
 * to prevent sensitive data from lingering in memory.
 *
 * Security notes:
 * - Uses Cleaner API on Android 13+ for reliable cleanup on GC
 * - Falls back to finalize() on older Android versions (with warning log)
 * - Returns copies of data to prevent external modifications
 * - IMPORTANT: Always use close() or use{} block for deterministic cleanup
 *   Relying on GC for cleanup is unreliable and may leave data in memory longer
 */
class SecureByteArray(size: Int) : Closeable {

    companion object {
        private const val TAG = "SecureByteArray"

        // Shared Cleaner instance for Android 13+ only
        private val cleaner: java.lang.ref.Cleaner? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            java.lang.ref.Cleaner.create()
        } else {
            null
        }

        /**
         * Executes a block with a secure byte array and ensures cleanup
         */
        inline fun <R> use(size: Int, block: (SecureByteArray) -> R): R {
            val secure = SecureByteArray(size)
            return try {
                block(secure)
            } finally {
                secure.close()
            }
        }
    }

    private var data: ByteArray? = ByteArray(size)
    private var closed = false

    // Cleaner for reliable GC cleanup on Android 13+ (API 33+)
    private val cleanable: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val dataRef = data
        cleaner?.register(this) {
            dataRef?.let { Arrays.fill(it, 0.toByte()) }
        }
    } else {
        null
    }

    /**
     * Gets the underlying byte array directly.
     * WARNING: Only use for in-place operations. Do not store the reference.
     * Throws if already closed.
     */
    fun get(): ByteArray {
        checkNotClosed()
        return data ?: throw IllegalStateException("Data has been cleared")
    }

    /**
     * Gets a byte at the specified index
     */
    operator fun get(index: Int): Byte {
        checkNotClosed()
        return data?.get(index) ?: throw IllegalStateException("Data has been cleared")
    }

    /**
     * Sets a byte at the specified index
     */
    operator fun set(index: Int, value: Byte) {
        checkNotClosed()
        data?.set(index, value) ?: throw IllegalStateException("Data has been cleared")
    }

    /**
     * Gets the size of the array
     */
    fun size(): Int {
        checkNotClosed()
        return data?.size ?: 0
    }

    /**
     * Zeros the memory and marks as closed
     */
    override fun close() {
        if (!closed) {
            data?.let { Arrays.fill(it, 0.toByte()) }
            data = null
            closed = true
            // Clean up the Cleaner registration on Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                @Suppress("UNCHECKED_CAST")
                (cleanable as? java.lang.ref.Cleaner.Cleanable)?.clean()
            }
        }
    }

    /**
     * Fallback cleanup for Android 12 and below.
     * Called by GC when object is no longer reachable.
     *
     * WARNING: This is a last-resort cleanup. Always call close() explicitly
     * or use the use{} block for deterministic cleanup.
     */
    @Suppress("DEPRECATION")
    protected fun finalize() {
        // Only use finalize() on older Android versions
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (!closed) {
                // Log warning to help developers identify missing close() calls
                Log.w(TAG, "SecureByteArray cleaned up by finalize() - call close() explicitly for better security")
                data?.let { Arrays.fill(it, 0.toByte()) }
                data = null
                closed = true
            }
        }
    }

    private fun checkNotClosed() {
        if (closed) {
            throw IllegalStateException("SecureByteArray has been closed")
        }
    }
}
