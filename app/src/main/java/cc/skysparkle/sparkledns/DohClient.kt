package cc.skysparkle.sparkledns

import android.content.Context
import android.net.http.HttpEngine
import android.net.http.HttpException
import android.net.http.UploadDataProvider
import android.net.http.UploadDataSink
import android.net.http.UrlRequest
import android.net.http.UrlResponseInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val DNS_MESSAGE = "application/dns-message"
private const val MAX_DNS_SIZE = 65535
private const val TAG = "SparkleDNS"

// RFC 8484 client. HTTP/3 goes through the platform HttpEngine (Android 14+, no extra libraries),
// everything else and older devices use OkHttp with HTTP/2.
class DohClient(context: Context, provider: DohProvider) {

    private val transport: Transport = createTransport(context.applicationContext, provider)

    val lastProtocol: String? get() = transport.lastProtocol

    fun query(message: ByteArray): ByteArray {
        val body = transport.query(message)
        if (!DnsMessage.isResponse(body)) throw IOException("Invalid DNS response")
        return body
    }

    fun shutdown() = transport.shutdown()

    private fun createTransport(context: Context, provider: DohProvider): Transport {
        if (provider.http3 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                return Http3Transport(context, provider)
            } catch (e: Throwable) {
                Log.w(TAG, "HttpEngine unavailable, falling back to HTTP/2", e)
            }
        }
        return OkHttpTransport(provider)
    }
}

private interface Transport {
    val lastProtocol: String?
    fun query(message: ByteArray): ByteArray
    fun shutdown()
}

private class OkHttpTransport(private val provider: DohProvider) : Transport {

    private val url by lazy { provider.url.toHttpUrl() }

    private val client = OkHttpClient.Builder()
        .dns(BootstrapDns(provider.bootstrap))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    @Volatile
    override var lastProtocol: String? = null

    override fun query(message: ByteArray): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Accept", DNS_MESSAGE)
            .post(message.toRequestBody(MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            lastProtocol = response.protocol.toString()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response")
            if (body.contentLength() > MAX_DNS_SIZE) throw IOException("Response too large")
            return body.bytes()
        }
    }

    override fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private companion object {
        val MEDIA_TYPE = DNS_MESSAGE.toMediaType()
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private class Http3Transport(context: Context, private val provider: DohProvider) : Transport {

    private val engine: HttpEngine = HttpEngine.Builder(context)
        .setEnableQuic(true)
        .setEnableHttp2(true)
        .apply {
            // QUIC hint lets the very first request go straight over HTTP/3 instead of waiting for Alt-Svc
            val uri = runCatching { URI(provider.url) }.getOrNull()
            val host = uri?.host
            if (host != null) {
                val port = if (uri.port > 0) uri.port else 443
                addQuicHint(host, port, port)
            }
        }
        .build()

    @Volatile
    override var lastProtocol: String? = null

    override fun query(message: ByteArray): ByteArray {
        val result = CompletableFuture<ByteArray>()
        val body = ByteArrayOutputStream()

        val callback = object : UrlRequest.Callback {
            override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
                request.followRedirect()
            }

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                lastProtocol = info.negotiatedProtocol
                if (info.httpStatusCode != 200) {
                    result.completeExceptionally(IOException("HTTP ${info.httpStatusCode}"))
                    request.cancel()
                    return
                }
                readNext(request, ByteBuffer.allocateDirect(4096))
            }

            override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, buffer: ByteBuffer) {
                buffer.flip()
                val chunk = ByteArray(buffer.remaining())
                buffer.get(chunk)
                body.write(chunk, 0, chunk.size)
                if (body.size() > MAX_DNS_SIZE) {
                    result.completeExceptionally(IOException("Response too large"))
                    request.cancel()
                    return
                }
                buffer.clear()
                readNext(request, buffer)
            }

            private fun readNext(request: UrlRequest, buffer: ByteBuffer) {
                try {
                    request.read(buffer)
                } catch (e: Exception) {
                    result.completeExceptionally(IOException(e))
                }
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                result.complete(body.toByteArray())
            }

            override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: HttpException) {
                result.completeExceptionally(IOException(error.message, error))
            }

            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                result.completeExceptionally(IOException("Canceled"))
            }
        }

        val request = engine.newUrlRequestBuilder(provider.url, executor, callback)
            .setHttpMethod("POST")
            .addHeader("Content-Type", DNS_MESSAGE)
            .addHeader("Accept", DNS_MESSAGE)
            .setUploadDataProvider(BytesUploadProvider(message), executor)
            .build()
        try {
            request.start()
        } catch (e: Exception) {
            throw IOException(e)
        }

        return try {
            result.get(8, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            request.cancel()
            throw IOException("Timeout")
        } catch (e: ExecutionException) {
            throw e.cause as? IOException ?: IOException(e.cause)
        }
    }

    // HttpEngine refuses to shut down while requests are in flight, so retry for a few seconds
    override fun shutdown() {
        executor.execute {
            repeat(20) {
                if (runCatching { engine.shutdown() }.isSuccess) return@execute
                Thread.sleep(500)
            }
        }
    }

    private companion object {
        // Shared and never shut down: a late callback must not hit a rejected executor
        val executor: Executor = Executors.newCachedThreadPool { r ->
            Thread(r, "doh-h3").apply { isDaemon = true }
        }
    }
}

// The platform API has no UploadDataProviders helper (that one exists only in Cronet)
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private class BytesUploadProvider(private val data: ByteArray) : UploadDataProvider() {
    private var position = 0

    override fun getLength(): Long = data.size.toLong()

    override fun read(sink: UploadDataSink, buffer: ByteBuffer) {
        val count = minOf(buffer.remaining(), data.size - position)
        buffer.put(data, position, count)
        position += count
        sink.onReadSucceeded(false)
    }

    override fun rewind(sink: UploadDataSink) {
        position = 0
        sink.onRewindSucceeded()
    }
}

// Uses fixed IPs for the DoH host when given, so the server is reachable even if system DNS blocks it
private class BootstrapDns(ips: List<String>) : Dns {
    private val addresses by lazy {
        ips.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
    }

    override fun lookup(hostname: String): List<InetAddress> =
        addresses.ifEmpty { Dns.SYSTEM.lookup(hostname) }
}
