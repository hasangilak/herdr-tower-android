package dev.herdertower.phone.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences

/** Preferences kept in memory: what [Settings] and the stores read and write in the JVM tests. */
class FakePrefs : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = values[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clearFirst = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { clearFirst = true }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            synchronized(values) {
                if (clearFirst) values.clear()
                changes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
            }
        }
    }
}

/**
 * A context for the JVM tests: in-memory preferences, and no system
 * services (Android's classes are stubs here, so the code under test sees
 * null and goes without them, as it does on a phone that lacks one).
 */
class FakeContext : ContextWrapper(null) {
    private val prefs = mutableMapOf<String, FakePrefs>()
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs.getOrPut(name) { FakePrefs() }
    override fun getApplicationContext(): Context = this
    override fun getPackageName(): String = "dev.herdertower.phone.test"
}

/** Credentials kept in memory. */
class FakeStore(var saved: Credentials?) : CredentialStore {
    override fun load(): Credentials? = saved
    override fun save(c: Credentials) {
        saved = c
    }
    override fun clear() {
        saved = null
    }
}
