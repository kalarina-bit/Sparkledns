package cc.skysparkle.sparkledns

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("doh", Context.MODE_PRIVATE)

    var providerId: String
        get() = sp.getString(KEY_PROVIDER, null) ?: Providers.default.id
        set(value) = sp.edit().putString(KEY_PROVIDER, value).apply()

    var customUrl: String
        get() = sp.getString(KEY_URL, null) ?: "https://"
        set(value) = sp.edit().putString(KEY_URL, value).apply()

    var customBootstrap: String
        get() = sp.getString(KEY_BOOTSTRAP, null) ?: ""
        set(value) = sp.edit().putString(KEY_BOOTSTRAP, value).apply()

    var customHttp3: Boolean
        get() = sp.getBoolean(KEY_HTTP3, false)
        set(value) = sp.edit().putBoolean(KEY_HTTP3, value).apply()

    var excludedApps: Set<String>
        get() = sp.getStringSet(KEY_EXCLUDED, null)?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_EXCLUDED, value).apply()

    fun resolve(): DohProvider =
        if (providerId == Providers.CUSTOM_ID) {
            DohProvider(Providers.CUSTOM_ID, "Custom", customUrl.trim(), parseIps(customBootstrap), customHttp3)
        } else {
            Providers.byId(providerId)
        }

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_URL = "custom_url"
        const val KEY_BOOTSTRAP = "custom_bootstrap"
        const val KEY_HTTP3 = "custom_http3"
        const val KEY_EXCLUDED = "excluded_apps"
    }
}
