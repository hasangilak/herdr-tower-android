package dev.herdertower.phone.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dev.herdertower.phone.api.Endpoint
import dev.herdertower.phone.api.Hosts
import dev.herdertower.phone.api.TowerHost
import dev.herdertower.phone.api.TowerJson
import kotlinx.serialization.builtins.ListSerializer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A paired tower. [host]/[port] are its first address (as paired); [hosts]
 * is every address it listens on, in order (LAN, Tailscale), and
 * [magicDns] its Tailscale name. One certificate, pinned by [fingerprint],
 * for all of them. [toString] never shows the token.
 */
data class Credentials(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val token: String,
    val deviceId: String,
    val deviceName: String,
    val hosts: List<TowerHost> = listOf(TowerHost(Hosts.kindOf(host), host, port)),
    val magicDns: String? = null,
) {
    val endpoint: Endpoint get() = Endpoint(host, port, fingerprint)

    /** The same tower at [h]. */
    fun endpointAt(h: TowerHost): Endpoint = Endpoint(h.host, h.port, fingerprint)

    override fun toString() = "Credentials(hosts=${hosts.joinToString { it.id }}, fingerprint=${fingerprint.take(8)}…, token=<hidden>, device=$deviceId)"
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
        val port = prefs.getInt("port", 7843)
        // Paired before Tailscale: its one address.
        val hosts = prefs.getString("hosts", null)
            ?.let { runCatching { Hosts.clean(TowerJson.decodeFromString(HOSTS, it)) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(TowerHost(Hosts.kindOf(host), host, port))
        return Credentials(
            hosts = hosts,
            magicDns = prefs.getString("magic_dns", null),
            host = host,
            port = port,
            fingerprint = prefs.getString("fingerprint", null) ?: return null,
            token = token,
            deviceId = prefs.getString("device_id", "") ?: "",
            deviceName = prefs.getString("device_name", "") ?: "",
        )
    }

    override fun save(c: Credentials) {
        prefs.edit {
            putString("host", c.host)
            putInt("port", c.port)
            putString("fingerprint", c.fingerprint)
            putString("token", seal(c.token))
            putString("device_id", c.deviceId)
            putString("device_name", c.deviceName)
            putString("hosts", TowerJson.encodeToString(HOSTS, c.hosts))
            putString("magic_dns", c.magicDns)
        }
    }

    override fun clear() {
        prefs.edit { clear() }
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
        val HOSTS = ListSerializer(TowerHost.serializer())
    }
}

/** Small app settings (not secret). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** "system", "dark" or "light". */
    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(v) = prefs.edit { putString("theme", v) }

    /** What the user calls this tower (the API has no Mac name); empty: not set. */
    var towerName: String
        get() = prefs.getString("tower_name", "") ?: ""
        set(v) = prefs.edit { putString("tower_name", v) }

    /** The last known audio route ("phone" or "mac"), until the Mac says. */
    var audioRoute: String?
        get() = prefs.getString("audio_route", null)
        set(v) = prefs.edit { putString("audio_route", v) }

    /** The last reply clip this phone got (to fetch what it missed). */
    var lastClip: String?
        get() = prefs.getString("last_clip", null)
        set(v) = prefs.edit { putString("last_clip", v) }

    /** How the orb is drawn: "auto" (what the probe found) or a tier's name. */
    var orbTier: String
        get() = prefs.getString("orb_tier", "auto") ?: "auto"
        set(v) = prefs.edit { putString("orb_tier", v) }

    /** Whether saying "stop" while the tower talks on the phone stops it. */
    var hushWord: Boolean
        get() = prefs.getBoolean("hush_word", true)
        set(v) = prefs.edit { putBoolean("hush_word", v) }

    /** Whether the app asked for notifications (Android 13+) once questions came up: asked once, not on every launch. */
    var askedNotifications: Boolean
        get() = prefs.getBoolean("asked_notifications", false)
        set(v) = prefs.edit { putBoolean("asked_notifications", v) }

    /** The address (`host:port`) the tower answered on last: tried right after the LAN. */
    var lastRoute: String?
        get() = prefs.getString("last_route", null)
        set(v) = prefs.edit { putString("last_route", v) }

    /** Whether the phone should be the tower's voice (restored on launch). */
    var voice: Boolean
        get() = prefs.getBoolean("voice", false)
        set(v) = prefs.edit { putBoolean("voice", v) }
}
