package de.onur.aquarea

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import de.onur.aquarea.api.DayConsumption
import de.onur.aquarea.api.HeatPump
import de.onur.aquarea.api.HeatPumpStatus
import de.onur.aquarea.api.Session
import de.onur.aquarea.api.SessionStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Speichert Zugangsdaten und Tokens verschlüsselt (AES-GCM, Schlüssel im Android-Keystore)
 * und die zuletzt geladenen Werte für das Widget.
 */
class Storage(context: Context) : SessionStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("aquarea", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // --- Zugangsdaten & Session (verschlüsselt)

    var email: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(v) = prefs.edit().putString(KEY_EMAIL, v).apply()

    var password: String?
        get() = getSecret(KEY_PASSWORD)
        set(v) = putSecret(KEY_PASSWORD, v)

    override fun load(): Session? =
        getSecret(KEY_SESSION)?.let { runCatching { json.decodeFromString<Session>(it) }.getOrNull() }

    override fun save(session: Session?) =
        putSecret(KEY_SESSION, session?.let { json.encodeToString(it) })

    val isLoggedIn get() = email != null && password != null

    // --- Gerät & Cache

    var selectedPump: HeatPump?
        get() = prefs.getString(KEY_PUMP, null)?.let { runCatching { json.decodeFromString<HeatPump>(it) }.getOrNull() }
        set(v) = prefs.edit().putString(KEY_PUMP, v?.let { json.encodeToString(it) }).apply()

    var status: HeatPumpStatus?
        get() = prefs.getString(KEY_STATUS, null)?.let { runCatching { json.decodeFromString<HeatPumpStatus>(it) }.getOrNull() }
        set(v) = prefs.edit().putString(KEY_STATUS, v?.let { json.encodeToString(it) }).apply()

    fun consumption(date: String): DayConsumption? =
        prefs.getString(KEY_CONSUMPTION + date, null)
            ?.let { runCatching { json.decodeFromString<DayConsumption>(it) }.getOrNull() }

    fun saveConsumption(day: DayConsumption) {
        val editor = prefs.edit().putString(KEY_CONSUMPTION + day.date, json.encodeToString(day))
        // nur die letzten 14 Tage behalten
        prefs.all.keys.filter { it.startsWith(KEY_CONSUMPTION) }.sorted().dropLast(14).forEach { editor.remove(it) }
        editor.apply()
    }

    var lastError: String?
        get() = prefs.getString(KEY_ERROR, null)
        set(v) = prefs.edit().putString(KEY_ERROR, v).apply()

    var lastRefresh: Long
        get() = prefs.getLong(KEY_REFRESHED, 0)
        set(v) = prefs.edit().putLong(KEY_REFRESHED, v).apply()

    /** Bis wann ein Warmwasser-Befehl als „läuft“ gilt (Unix-ms). */
    var dhwPendingUntil: Long
        get() = prefs.getLong("dhw_pending", 0)
        set(v) = prefs.edit().putLong("dhw_pending", v).apply()

    /** Bis wann „Warmwasser erzwingen“ als „läuft“ gilt, und auf welchen Zustand (Unix-ms / an?). */
    var forcePendingUntil: Long
        get() = prefs.getLong("force_pending", 0)
        set(v) = prefs.edit().putLong("force_pending", v).apply()
    var forcePendingTarget: Boolean
        get() = prefs.getBoolean("force_target", false)
        set(v) = prefs.edit().putBoolean("force_target", v).apply()

    /** Die Betriebsart, auf die gerade umgeschaltet wird. */
    var pendingOperation: String?
        get() = prefs.getString("pending_op", null)
        set(v) = prefs.edit().putString("pending_op", v).apply()

    /** Wie viele Tage das Widget zurückblättert (0 = heute). */
    fun dayOffset(widgetId: Int) = prefs.getInt("offset_$widgetId", 0)
    fun setDayOffset(widgetId: Int, offset: Int) = prefs.edit().putInt("offset_$widgetId", offset).apply()

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    // --- Verschlüsselung

    private fun putSecret(key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val enc = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)
        prefs.edit().putString(key, stored).apply()
    }

    private fun getSecret(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return runCatching {
            val (iv, enc) = stored.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(enc), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "aquarea_widget_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_EMAIL = "email"
        const val KEY_PASSWORD = "password"
        const val KEY_SESSION = "session"
        const val KEY_PUMP = "pump"
        const val KEY_STATUS = "status"
        const val KEY_CONSUMPTION = "consumption_"
        const val KEY_ERROR = "error"
        const val KEY_REFRESHED = "refreshed"
    }
}
