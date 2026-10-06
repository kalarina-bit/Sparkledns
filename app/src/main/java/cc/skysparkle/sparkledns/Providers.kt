package cc.skysparkle.sparkledns

import android.content.Context

data class DohProvider(
    val id: String,
    val name: String,
    val url: String,
    val bootstrap: List<String> = emptyList(),
    val http3: Boolean = false,
)

object Providers {
    const val CUSTOM_ID = "custom"

    val list = listOf(
        DohProvider("skysparkle", "SkySparkle", "https://blog.skysparkle.cc/dns-query", http3 = true),
        DohProvider("cloudflare", "Cloudflare", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1")),
        DohProvider("google", "Google", "https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4")),
        DohProvider("quad9", "Quad9", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112")),
        DohProvider("adguard", "AdGuard", "https://dns.adguard-dns.com/dns-query", listOf("94.140.14.14", "94.140.15.15")),
        DohProvider("mullvad", "Mullvad", "https://dns.mullvad.net/dns-query", listOf("194.242.2.2")),
    )

    val default: DohProvider get() = list.first()

    fun byId(id: String): DohProvider = list.firstOrNull { it.id == id } ?: default
}

fun parseIps(raw: String): List<String> =
    raw.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }

fun DohProvider.displayName(context: Context): String =
    if (id == Providers.CUSTOM_ID) context.getString(R.string.custom) else name
