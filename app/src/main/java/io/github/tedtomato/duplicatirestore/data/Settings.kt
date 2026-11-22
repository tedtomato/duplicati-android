package io.github.tedtomato.duplicatirestore.data

import android.content.Context
import androidx.core.content.edit

data class Account(val url: String, val username: String, val prefix: String) {
    /** Identifies the backup; the local index is cleared when this changes. */
    val fingerprint: String get() = "${url.trim().trimEnd('/')}|$username|$prefix"
}

enum class DestinationMode { FOLDER, DOWNLOADS }

/** Non-secret settings. The password and passphrase are never stored here (see [SecretStore]). */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var account: Account?
        get() {
            val url = p.getString("url", null) ?: return null
            return Account(url, p.getString("username", "")!!, p.getString("prefix", "duplicati")!!)
        }
        set(a) = p.edit {
            if (a == null) { remove("url"); remove("username"); remove("prefix") } else {
                putString("url", a.url.trim()); putString("username", a.username.trim()); putString("prefix", a.prefix.trim())
            }
        }

    var indexedFingerprint: String?
        get() = p.getString("indexedFingerprint", null)
        set(v) = p.edit { putString("indexedFingerprint", v) }

    var cacheLimitMb: Int
        get() = p.getInt("cacheLimitMb", 500)
        set(v) = p.edit { putInt("cacheLimitMb", v.coerceIn(0, 100_000)) }

    var destinationMode: DestinationMode
        get() = runCatching { DestinationMode.valueOf(p.getString("destMode", "FOLDER")!!) }.getOrDefault(DestinationMode.FOLDER)
        set(v) = p.edit { putString("destMode", v.name) }

    var destinationTree: String?
        get() = p.getString("destTree", null)
        set(v) = p.edit { putString("destTree", v) }

    var autoUpdateCheck: Boolean
        get() = p.getBoolean("autoUpdateCheck", true)
        set(v) = p.edit { putBoolean("autoUpdateCheck", v) }

    var lastUpdateCheck: Long
        get() = p.getLong("lastUpdateCheck", 0)
        set(v) = p.edit { putLong("lastUpdateCheck", v) }

    var lastSync: Long
        get() = p.getLong("lastSync", 0)
        set(v) = p.edit { putLong("lastSync", v) }
}
