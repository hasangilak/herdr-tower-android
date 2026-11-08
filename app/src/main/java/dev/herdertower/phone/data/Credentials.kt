package dev.herdertower.phone.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.herdertower.phone.api.Endpoint
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A paired tower. [toString] never shows the token. */
data class Credentials(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val token: String,
    val deviceId: String,
    val deviceName: String,
) {
    val endpoint: Endpoint get() = Endpoint(host, port, fingerprint)
    override fun toString() = "Credentials(host=$host, port=$port, fingerprint=${fingerprint.take(8)}…, token=<hidden>, device=$deviceId)"
}

interface CredentialStore {
    fun load(): Credentials?
    fun save(c: Credentials)
    fun clear()
}

/**
 * Host, port, fingerprint and device in plain preferences; the token
 * encrypted with an AES-256-GCM key that lives in the Android Keystore and
 * never leaves it. Excluded from backups (see `data_extraction_rules.xml`).
 */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    override fun load(): Credentials? {
        val host = prefs.getString("host", null) ?: return null
        val sealed = prefs.getString("token", null) ?: return null
        val token = runCatching { open(sealed) }.getOrNull() ?: return null // key gone (e.g. restored to another phone)
        return Credentials(
            host = host,
            port = prefs.getInt("port", 7843),
            fingerprint = prefs.getString("fingerprint", null) ?: return null,
            token = token,
            deviceId = prefs.getString("device_id", "") ?: "",
            deviceName = prefs.getString("device_name", "") ?: "",
        )
    }

    override fun save(c: Credentials) {
        prefs.edit()
            .putString("host", c.host)
            .putInt("port", c.port)
            .putString("fingerprint", c.fingerprint)
            .putString("token", seal(c.token))
            .putString("device_id", c.deviceId)
            .putString("device_name", c.deviceName)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
        runCatching { keystore().deleteEntry(ALIAS) }
    }

    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (keystore().getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun seal(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun open(sealed: String): String {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    private companion object {
        const val ALIAS = "herder_tower_token"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

/** Small app settings (not secret). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** "system", "dark" or "light". */
    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(v) = prefs.edit().putString("theme", v).apply()

    /** What the user calls this tower (the API has no Mac name); empty: not set. */
    var towerName: String
        get() = prefs.getString("tower_name", "") ?: ""
        set(v) = prefs.edit().putString("tower_name", v).apply()

    /** Whether the phone should be the tower's voice (restored on launch). */
    var voice: Boolean
        get() = prefs.getBoolean("voice", false)
        set(v) = prefs.edit().putBoolean("voice", v).apply()
}
