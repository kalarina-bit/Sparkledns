package cc.skysparkle.sparkledns

import android.content.Context

data class DohProvider(
    val id: String,
    val name: String,
    val url: String,
    val bootstrap: List<String> = emptyList(),
    val http3: Boolean = false,
    val policy: String? = null,
    val donate: String? = null,
)

object Providers {
    const val CUSTOM_ID = "custom"
    const val DEFAULT_ID = "skysparkle"

    // Servers ship with the app; new ones arrive with app updates
    val builtIn = listOf(
        // The address is personal: Prefs.resolve() adds the access key from skysparkle.cc
        DohProvider(
            "skysparkle", "SkySparkle", SkysparkleKey.BASE_URL,
            http3 = true, policy = "https://skysparkle.cc/privacy?lang=en",
        ),
        DohProvider(
            "quad9", "Quad9", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112"),
            http3 = true,
            policy = "https://quad9.net/service/privacy/",
            donate = "https://quad9.net/donate/",
        ),
        DohProvider(
            "digitale-gesellschaft", "Digitale Gesellschaft", "https://dns.digitale-gesellschaft.ch/dns-query",
            listOf("185.95.218.42", "185.95.218.43"), http3 = true,
            policy = "https://www.digitale-gesellschaft.ch/dns/",
            donate = "https://www.digitale-gesellschaft.ch/uber-uns/unterstuetzer-werden/",
        ),
        DohProvider(
            "uncensoreddns", "UncensoredDNS", "https://anycast.uncensoreddns.org/dns-query", listOf("91.239.100.100"),
            policy = "https://blog.uncensoreddns.org/faq/",
        ),
        DohProvider(
            "ffmuc", "Freifunk München", "https://doh.ffmuc.net/dns-query",
            listOf("5.1.66.255", "185.150.99.255"), http3 = true,
            policy = "https://ffmuc.net/dns-privacy/",
            donate = "https://spende.ffmuc.net/",
        ),
        DohProvider(
            "dnsforge", "dnsforge.de", "https://blank.dnsforge.de/dns-query",
            listOf("78.47.71.194", "138.199.149.249"),
            policy = "https://adminforge.de/datenschutz/",
            donate = "https://adminforge.de/unterstuetzen/",
        ),
    )

    // The project's own server stays the default whatever order the list uses
    val default: DohProvider = builtIn.firstOrNull { it.id == DEFAULT_ID } ?: builtIn.first()

    fun byId(id: String): DohProvider = builtIn.firstOrNull { it.id == id } ?: default
}

// Personal access keys for the Skysparkle server. Each account at skysparkle.cc creates its own
// keys; the server answers only /dns/<key>, there is no shared public address.
object SkysparkleKey {
    const val BASE_URL = "https://skysparkle.cc/dns/"
    const val SIGNUP_URL = "https://skysparkle.cc/login#register"
    private val KEY = Regex("^[A-Za-z0-9_-]{24}$")
    private val IN_URL = Regex("/dns/([A-Za-z0-9_-]{24})(?:[/?#].*)?$")

    // Accepts the key itself or the whole address copied from the account page
    fun parse(input: String): String? {
        val text = input.trim()
        if (KEY.matches(text)) return text
        return IN_URL.find(text)?.groupValues?.get(1)
    }

    fun url(key: String): String = parse(key)?.let { BASE_URL + it } ?: ""
}

fun parseIps(raw: String): List<String> =
    raw.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }

fun DohProvider.displayName(context: Context): String =
    if (id == Providers.CUSTOM_ID) context.getString(R.string.custom) else name
