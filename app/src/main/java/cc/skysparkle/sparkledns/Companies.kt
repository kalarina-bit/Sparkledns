package cc.skysparkle.sparkledns

import java.util.TreeMap

// The position of each company is saved in StatsStore: add new ones at the end only
enum class Company(val label: String) {
    GOOGLE("Google"),
    APPLE("Apple"),
    META("Meta"),
    AMAZON("Amazon"),
    MICROSOFT("Microsoft"),
    BYTEDANCE("ByteDance"),
    X("X"),
    YANDEX("Yandex"),
    SAMSUNG("Samsung"),
    XIAOMI("Xiaomi"),
}

// Tells which big company a domain belongs to, for the Company dominance card.
// Matches the domain and every parent of it against a small built-in table.
object Companies {
    private const val GOOGLE_PREFIX = "google."

    // Hashes of the domains, sorted, with the company of each at the same index: this runs for
    // every query, and parent domains are looked up by hash without copying the name
    private val hashes: LongArray
    private val owners: Array<Company>

    init {
        val table = TreeMap<Long, Company>()
        fun add(company: Company, vararg domains: String) = domains.forEach { table[DomainHash.of(it)] = company }

        add(
            Company.GOOGLE,
            "google.com", "googleapis.com", "gstatic.com", "googleusercontent.com", "googlevideo.com",
            "youtube.com", "youtu.be", "ytimg.com", "ggpht.com", "gvt1.com", "gvt2.com", "1e100.net",
            "android.com", "googlesyndication.com", "googleadservices.com", "doubleclick.net",
            "google-analytics.com", "googletagmanager.com", "googletagservices.com", "app-measurement.com",
            "firebaseio.com", "firebase.com", "crashlytics.com", "withgoogle.com", "googlemail.com",
            "gmail.com", "blogspot.com", "admob.com", "goo.gl",
        )
        add(
            Company.APPLE,
            "apple.com", "icloud.com", "icloud-content.com", "mzstatic.com", "apple-dns.net", "aaplimg.com",
            "cdn-apple.com", "itunes.com", "me.com", "apple.news", "apple-cloudkit.com", "appleid.com",
        )
        add(
            Company.META,
            "facebook.com", "facebook.net", "fbcdn.net", "fbsbx.com", "fb.com", "instagram.com",
            "cdninstagram.com", "whatsapp.com", "whatsapp.net", "messenger.com", "meta.com", "oculus.com",
            "threads.net", "graph.facebook.com",
        )
        add(
            Company.AMAZON,
            "amazon.com", "amazonaws.com", "media-amazon.com", "ssl-images-amazon.com", "cloudfront.net",
            "amazon-adsystem.com", "a2z.com", "primevideo.com", "aiv-cdn.net", "twitch.tv", "ttvnw.net",
            "amazon.de", "amazon.co.uk", "amazontrust.com",
        )
        add(
            Company.MICROSOFT,
            "microsoft.com", "live.com", "outlook.com", "office.com", "office.net", "office365.com", "msn.com",
            "bing.com", "windows.com", "windows.net", "windowsupdate.com", "azure.com", "azureedge.net",
            "skype.com", "xbox.com", "xboxlive.com", "linkedin.com", "licdn.com", "github.com",
            "githubusercontent.com", "msftncsi.com", "msedge.net", "microsoftonline.com", "sharepoint.com",
        )
        add(
            Company.BYTEDANCE,
            "tiktok.com", "tiktokv.com", "tiktokcdn.com", "tiktokcdn-us.com", "byteoversea.com",
            "ibytedtos.com", "bytedance.com", "capcut.com", "byteimg.com",
        )
        add(Company.X, "twitter.com", "x.com", "twimg.com", "t.co")
        add(Company.YANDEX, "yandex.ru", "yandex.net", "yandex.com", "ya.ru", "yastatic.net", "yandex.by", "yandex.kz")
        add(Company.SAMSUNG, "samsung.com", "samsungapps.com", "samsungcloud.com", "samsungqbe.com", "samsungosp.com")
        add(Company.XIAOMI, "xiaomi.com", "xiaomi.net", "mi.com", "miui.com", "mi-img.com")

        hashes = table.keys.toLongArray()
        owners = table.values.toTypedArray()
    }

    fun of(name: String): Company? {
        // Names from the tunnel are lowercase already; anything else is lowercased once
        val domain = if (name.any { it.isUpperCase() }) name.lowercase() else name
        val end = nameEnd(domain)
        var start = 0
        while (start < end) {
            val index = hashes.binarySearch(DomainHash.of(domain, start, end))
            if (index >= 0) return owners[index]
            // Google's country sites: google.de, google.co.uk, google.com.br
            if (end - start - GOOGLE_PREFIX.length in 1..6 && domain.startsWith(GOOGLE_PREFIX, start)) return Company.GOOGLE
            val dot = domain.indexOf('.', start)
            if (dot < 0 || dot >= end) return null
            start = dot + 1
        }
        return null
    }
}
