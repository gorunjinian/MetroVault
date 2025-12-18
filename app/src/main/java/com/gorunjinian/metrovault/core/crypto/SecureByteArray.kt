package com.gorunjinian.metrovault.core.crypto

import android.os.Build
import android.util.Log
import java.io.Closeable
import java.util.Arrays

/**
 * Secure wrapper for byte arrays containing sensitive data.
 *
 * Automatically zeros memory when closed or garbage collected
 * to prevent sensitive data from lingering in memory.
 *
 * ## Security Contract
 *
 * **DO:**
 * - Use [copyFrom] to write data into this array
 * - Use [copyTo] to read data out to a destination you control
 * - Use [asString] when you need string conversion (understand the implications)
 * - Always call [close] or use [use] block for deterministic cleanup
 *
 * **DON'T:**
 * - Store references obtained from [getUnsafe] - they become invalid after [close]
 * - Rely on GC for cleanup - it's unpredictable and may leave data in memory longer
 *
 * ## Platform Notes
 * - Uses Cleaner API on Android 13+ for reliable cleanup on GC
 * - Falls back to finalize() on older Android versions (with warning log)
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

    // ==================== Safe Access Methods ====================

    /**
     * Copies data FROM a source array INTO this SecureByteArray.
     * This is the preferred way to populate a SecureByteArray.
     *
     * @param source The source byte array to copy from
     * @param sourceOffset Starting position in source array (default 0)
     * @param destOffset Starting position in this array (default 0)
     * @param length Number of bytes to copy (default: source.size)
     * @throws IllegalStateException if this array is closed
     * @throws IndexOutOfBoundsException if offsets/length are invalid
     */
    fun copyFrom(source: ByteArray, sourceOffset: Int = 0, destOffset: Int = 0, length: Int = source.size) {
        checkNotClosed()
        val dest = data ?: throw IllegalStateException("Data has been cleared")
        System.arraycopy(source, sourceOffset, dest, destOffset, length)
    }

    /**
     * Copies data FROM this SecureByteArray TO a destination array.
     * Caller is responsible for wiping the destination array when done.
     *
     * @param dest The destination byte array
     * @param sourceOffset Starting position in this array (default 0)
     * @param destOffset Starting position in destination (default 0)
     * @param length Number of bytes to copy (default: this array's size)
     * @throws IllegalStateException if this array is closed
     */
    fun copyTo(dest: ByteArray, sourceOffset: Int = 0, destOffset: Int = 0, length: Int = size()) {
        checkNotClosed()
        val source = data ?: throw IllegalStateException("Data has been cleared")
        System.arraycopy(source, sourceOffset, dest, destOffset, length)
    }

    /**
     * Converts the contents to a String.
     *
     * **SECURITY WARNING:** Strings are immutable in the JVM and cannot be wiped.
     * The returned string will remain in memory until garbage collected.
     * Only use this when you genuinely need a String (e.g., for display or API calls).
     *
     * @param charset Character encoding (default UTF-8)
     * @return String representation of the data
     */
    fun asString(charset: java.nio.charset.Charset = Charsets.UTF_8): String {
        checkNotClosed()
        val source = data ?: throw IllegalStateException("Data has been cleared")
        return String(source, charset)
    }

    /**
     * Gets a byte at the specified index.
     */
    operator fun get(index: Int): Byte {
        checkNotClosed()
        return data?.get(index) ?: throw IllegalStateException("Data has been cleared")
    }

    /**
     * Sets a byte at the specified index.
     */
    operator fun set(index: Int, value: Byte) {
        checkNotClosed()
        data?.set(index, value) ?: throw IllegalStateException("Data has been cleared")
    }

    /**
     * Gets the size of the array.
     */
    fun size(): Int {
        checkNotClosed()
        return data?.size ?: 0
    }

    // ==================== Unsafe Access (Deprecated) ====================

    /**
     * Gets the underlying byte array directly.
     *
     * **DEPRECATED:** This method exposes the internal array reference, which
     * undermines the security guarantees of SecureByteArray. External code
     * holding this reference can access data even after [close] is called.
     *
     * **Migration guide:**
     * - For writing data in: use [copyFrom] instead
     * - For reading data out: use [copyTo] instead
     * - For string conversion: use [asString] instead
     * - For System.arraycopy destination: use [copyFrom]
     *
     * @return Direct reference to internal array (DO NOT STORE THIS REFERENCE)
     */
    @Deprecated(
        message = "Direct array access is unsafe. Use copyFrom(), copyTo(), or asString() instead.",
        replaceWith = ReplaceWith("copyFrom(source) or copyTo(dest) or asString()")
    )
    fun get(): ByteArray {
        checkNotClosed()
        return data ?: throw IllegalStateException("Data has been cleared")
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
