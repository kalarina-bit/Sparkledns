package cc.skysparkle.sparkledns

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// Base for every OkHttp client in the app. Clients made with newBuilder() share its dispatcher
// and thread pools, and its connection pool unless they bring their own.
internal object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
