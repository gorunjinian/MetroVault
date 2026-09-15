package com.gorunjinian.metrovault.core.storage

import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArraySet

/**
 * A [SharedPreferences] that lives entirely in RAM and never touches disk.
 *
 * Used as the stand-in for the vault files during a duress session (see
 * [DuressSession]): every wallet the fake session shows or creates is written
 * here and vanishes with [wipe]. Semantics follow Android's implementation
 * closely enough for [SecureStorage]: `clear()` applies before the editor's
 * puts and removes, `putString(key, null)` removes, string sets are copied
 * defensively, and `commit()`/`apply()` are both synchronous.
 */
class InMemorySharedPreferences : SharedPreferences {

    private val lock = Any()
    private val values = HashMap<String, Any>()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = synchronized(lock) {
        values.mapValuesTo(HashMap<String, Any>()) { (_, value) ->
            if (value is Set<*>) HashSet(value) else value
        }
    }

    override fun getString(key: String?, defValue: String?): String? =
        read(key) as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        val stored = read(key) as? Set<String> ?: return defValues
        return HashSet(stored)
    }

    override fun getInt(key: String?, defValue: Int): Int = read(key) as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = read(key) as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = read(key) as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = read(key) as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = synchronized(lock) { values.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        if (listener != null) listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        listeners.remove(listener)
    }

    /** Drops every entry. */
    fun wipe() {
        synchronized(lock) { values.clear() }
    }

    private fun read(key: String?): Any? = synchronized(lock) { values[key] }

    private inner class Editor : SharedPreferences.Editor {
        // Insertion-ordered; a null value marks a removal. Last write per key wins.
        private val pending = LinkedHashMap<String, Any?>()
        private var clearRequested = false

        private fun stage(key: String?, value: Any?): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putString(key: String?, value: String?) = stage(key, value)

        override fun putStringSet(key: String?, values: MutableSet<String>?) =
            stage(key, values?.let { HashSet(it) })

        override fun putInt(key: String?, value: Int) = stage(key, value)

        override fun putLong(key: String?, value: Long) = stage(key, value)

        override fun putFloat(key: String?, value: Float) = stage(key, value)

        override fun putBoolean(key: String?, value: Boolean) = stage(key, value)

        override fun remove(key: String?) = stage(key, null)

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            return this
        }

        override fun commit(): Boolean {
            write()
            return true
        }

        override fun apply() {
            write()
        }

        private fun write() {
            val changedKeys: List<String>
            synchronized(lock) {
                if (clearRequested) values.clear()
                for ((key, value) in pending) {
                    if (value == null) values.remove(key) else values[key] = value
                }
                changedKeys = pending.keys.toList()
            }
            clearRequested = false
            pending.clear()
            for (listener in listeners) {
                for (key in changedKeys) {
                    listener.onSharedPreferenceChanged(this@InMemorySharedPreferences, key)
                }
            }
        }
    }
}
