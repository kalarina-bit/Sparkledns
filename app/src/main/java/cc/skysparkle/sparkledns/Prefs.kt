package cc.skysparkle.sparkledns

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("doh", Context.MODE_PRIVATE)

    init {
        migrateOldCustomServer()
    }

    var providerId: String
        get() = sp.getString(KEY_PROVIDER, null) ?: Providers.default.id
        set(value) = sp.edit().putString(KEY_PROVIDER, value).apply()

    var customServers: List<CustomDohServer>
        get() {
            val json = sp.getString(KEY_CUSTOM_SERVERS, null) ?: return emptyList()
            return try {
                val array = JSONArray(json)
                List(array.length()) { i ->
                    CustomDohServer.fromJson(array.getJSONObject(i))
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val array = JSONArray()
            value.forEach { array.put(it.toJson()) }
            sp.edit().putString(KEY_CUSTOM_SERVERS, array.toString()).apply()
        }

    @Deprecated("Use customServers list instead", ReplaceWith("customServers"))
    var customUrl: String
        get() = sp.getString(KEY_URL, null) ?: "https://"
        set(value) = sp.edit().putString(KEY_URL, value).apply()

    @Deprecated("Use customServers list instead", ReplaceWith("customServers"))
    var customBootstrap: String
        get() = sp.getString(KEY_BOOTSTRAP, null) ?: ""
        set(value) = sp.edit().putString(KEY_BOOTSTRAP, value).apply()

    @Deprecated("Use customServers list instead", ReplaceWith("customServers"))
    var customHttp3: Boolean
        get() = sp.getBoolean(KEY_HTTP3, false)
        set(value) = sp.edit().putBoolean(KEY_HTTP3, value).apply()

    // Access key for the Skysparkle server, created in the account at skysparkle.cc
    var skysparkleKey: String
        get() = sp.getString(KEY_SKYSPARKLE, null) ?: ""
        set(value) = sp.edit().putString(KEY_SKYSPARKLE, value).apply()

    // The first-launch guide was finished or skipped
    var onboardingDone: Boolean
        get() = sp.getBoolean(KEY_ONBOARDING, false)
        set(value) = sp.edit().putBoolean(KEY_ONBOARDING, value).apply()

    var excludedApps: Set<String>
        get() = sp.getStringSet(KEY_EXCLUDED, null)?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_EXCLUDED, value).apply()

    var updateInterval: UpdateInterval
        get() = UpdateInterval.entries.getOrNull(sp.getInt(KEY_UPDATE_INTERVAL, UpdateInterval.DAILY.ordinal))
            ?: UpdateInterval.DAILY
        set(value) = sp.edit().putInt(KEY_UPDATE_INTERVAL, value.ordinal).apply()

    var updateWifiOnly: Boolean
        get() = sp.getBoolean(KEY_UPDATE_WIFI_ONLY, true)
        set(value) = sp.edit().putBoolean(KEY_UPDATE_WIFI_ONLY, value).apply()

    // Hint cards the user closed with the X
    fun isHintDismissed(key: String): Boolean =
        sp.getStringSet(KEY_HINTS, null)?.contains(key) == true

    fun dismissHint(key: String) {
        val current = sp.getStringSet(KEY_HINTS, null)?.toSet() ?: emptySet()
        sp.edit().putStringSet(KEY_HINTS, current + key).apply()
    }

    // The Private DNS warning stays closed until the hostname changes
    var dismissedPrivateDns: String
        get() = sp.getString(KEY_PRIVATE_DNS, null) ?: ""
        set(value) = sp.edit().putString(KEY_PRIVATE_DNS, value).apply()

    fun addCustomServer(server: CustomDohServer) {
        customServers = customServers + server
    }

    fun updateCustomServer(id: String, server: CustomDohServer) {
        customServers = customServers.map { if (it.id == id) server else it }
    }

    fun deleteCustomServer(id: String): String? {
        val servers = customServers
        val toDelete = servers.find { it.id == id } ?: return null

        customServers = servers.filter { it.id != id }

        // If deleted server was active, switch to another
        if (providerId == "custom:$id") {
            val index = servers.indexOf(toDelete)
            val newProviderId = when {
                servers.size == 1 -> Providers.DEFAULT_ID // Last custom server
                index > 0 -> "custom:${servers[index - 1].id}" // Previous server
                else -> "custom:${servers[index + 1].id}" // Next server (was first)
            }
            providerId = newProviderId
            return newProviderId
        }

        return null
    }

    fun resolve(): DohProvider =
        when {
            providerId.startsWith("custom:") -> {
                val uuid = providerId.removePrefix("custom:")
                val server = customServers.find { it.id == uuid }
                server?.toDohProvider() ?: Providers.default
            }
            providerId == Providers.CUSTOM_ID -> {
                // Legacy support: old "custom" without UUID
                DohProvider(Providers.CUSTOM_ID, "Custom", customUrl.trim(), parseIps(customBootstrap), customHttp3)
            }
            else -> Providers.byId(providerId)
        }

    private fun migrateOldCustomServer() {
        if (sp.contains(KEY_URL) && !sp.contains(KEY_CUSTOM_SERVERS)) {
            val url = sp.getString(KEY_URL, "https://") ?: return
            if (url != "https://" && url.isNotBlank()) {
                val server = CustomDohServer.create(
                    name = "Свой сервер 1",
                    url = url.trim(),
                    bootstrap = parseIps(sp.getString(KEY_BOOTSTRAP, null) ?: ""),
                    http3 = sp.getBoolean(KEY_HTTP3, false)
                )
                customServers = listOf(server)
                if (providerId == Providers.CUSTOM_ID) {
                    providerId = "custom:${server.id}"
                }
            }
            sp.edit()
                .remove(KEY_URL)
                .remove(KEY_BOOTSTRAP)
                .remove(KEY_HTTP3)
                .apply()
        }
    }

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_CUSTOM_SERVERS = "custom_servers"
        const val KEY_URL = "custom_url"
        const val KEY_BOOTSTRAP = "custom_bootstrap"
        const val KEY_HTTP3 = "custom_http3"
        const val KEY_EXCLUDED = "excluded_apps"
        const val KEY_SKYSPARKLE = "skysparkle_key"
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_HINTS = "dismissed_hints"
        const val KEY_PRIVATE_DNS = "dismissed_private_dns"
        const val KEY_UPDATE_INTERVAL = "update_interval"
        const val KEY_UPDATE_WIFI_ONLY = "update_wifi_only"
    }
}
