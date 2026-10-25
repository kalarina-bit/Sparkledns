package cc.skysparkle.sparkledns

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

private const val TUN_ADDRESS = "10.111.222.1"
private const val DNS_ADDRESS = "10.111.222.2"
private const val MTU = 4096
private const val WORKERS = 16
private const val TAG = "SparkleDNS"
private const val CHANNEL_ID = "vpn_status"
private const val NOTIFICATION_ID = 1
private const val MAX_BAD_PACKETS = 100

// Android's resolver retries after 5 s, so an older lookup no longer has anyone waiting for it
private const val QUEUE_TIMEOUT_MS = 5000L

// Closing TLS connections writes to the network, so sessions release their DoH client here
// rather than on the main thread, where the service stops them
private val closer = Executors.newSingleThreadExecutor { r -> Thread(r, "doh-close").apply { isDaemon = true } }

// Local VPN that routes only the fake DNS address into the tunnel; all other traffic is untouched
class DohVpnService : VpnService() {

    companion object {
        const val ACTION_START = "cc.skysparkle.sparkledns.START"
        const val ACTION_STOP = "cc.skysparkle.sparkledns.STOP"

        // Provider the tunnel is actually using, null when disconnected
        private val _active = MutableStateFlow<DohProvider?>(null)
        val active: StateFlow<DohProvider?> = _active

        // Apps that bypass the tunnel in the running session
        private val _activeExcluded = MutableStateFlow<Set<String>>(emptySet())
        val activeExcluded: StateFlow<Set<String>> = _activeExcluded
    }

    private var session: Session? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            stopSelf()
            return START_NOT_STICKY
        }
        // Explicit start, always-on VPN (android.net.VpnService) or a sticky restart (null intent)
        try {
            startSession()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            stopSession()
            stopSelf()
            return START_NOT_STICKY
        }
        return if (session != null) START_STICKY else START_NOT_STICKY
    }

    override fun onRevoke() {
        stopSession()
        stopSelf()
    }

    override fun onDestroy() {
        stopSession()
        super.onDestroy()
    }

    private fun startSession() {
        val prefs = Prefs(this)
        val provider = prefs.resolve()
        val excluded = prefs.excludedApps

        val builder = Builder()
            .setSession(provider.displayName(this))
            .addAddress(TUN_ADDRESS, 32)
            .addRoute(DNS_ADDRESS, 32)
            .addDnsServer(DNS_ADDRESS)
            .setMtu(MTU)
            .setBlocking(true)
            // Without this Android blocks every family the tunnel has no address for, i.e. all IPv6
            // traffic of all apps, and stops asking for AAAA records
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
        // The app itself bypasses the VPN so it can resolve and reach the DoH server
        runCatching { builder.addDisallowedApplication(packageName) }
            .onFailure { Log.e(TAG, "Cannot exclude the app itself from the VPN", it) }
        // Apps uninstalled since they were picked are simply skipped
        excluded.forEach { app ->
            runCatching { builder.addDisallowedApplication(app) }.onFailure { Log.i(TAG, "Not excluding $app: $it") }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        // The new interface replaces the old one in place, so DNS never leaks during a server switch
        val tun = runCatching { builder.establish() }.onFailure { Log.e(TAG, "Cannot establish the VPN", it) }.getOrNull()
        val previous = session
        if (tun == null) {
            stopSession()
            stopSelf()
            return
        }
        session = Session(tun, DohClient(this, provider), ::onSessionFailed).also { it.start() }
        runCatching { previous?.stop() }

        _active.value = provider
        _activeExcluded.value = excluded
        showNotification(provider)
        DnsTileService.refresh(this)
    }

    private fun stopSession() {
        runCatching { session?.stop() }
        session = null
        _active.value = null
        _activeExcluded.value = emptySet()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        DnsTileService.refresh(this)
    }

    // The tunnel reader died while the session should be running. Dropping the VPN lets Android
    // fall back to normal DNS instead of sending every lookup on the device into a dead tunnel.
    private fun onSessionFailed(failed: Session) {
        mainHandler.post {
            if (session !== failed) return@post
            Log.e(TAG, "Tunnel stopped working, disconnecting")
            stopSession()
            stopSelf()
        }
    }

    private fun showNotification(provider: DohProvider) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, DohVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title, provider.displayName(this)))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notification),
                    getString(R.string.disconnect),
                    stop,
                ).build()
            )
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Started from the background (e.g. always-on VPN): keep running with a plain notification
            Log.w(TAG, "startForeground not allowed", e)
            runCatching { manager.notify(NOTIFICATION_ID, notification) }
        }
    }
}

private class Session(
    private val tun: ParcelFileDescriptor,
    private val doh: DohClient,
    private val onFailure: (Session) -> Unit,
) {
    private val alive = AtomicBoolean(true)
    private val pool = Executors.newFixedThreadPool(WORKERS)
    private val cache = DnsCache()
    private val output = FileOutputStream(tun.fileDescriptor)
    private val reader = Thread(::readLoop, "tun-reader")

    fun start() = reader.start()

    fun stop() {
        if (!alive.getAndSet(false)) return
        pool.shutdownNow()
        runCatching { tun.close() }
        closer.execute {
            try {
                doh.shutdown()
            } catch (e: Exception) {
                Log.w(TAG, "DoH client shutdown failed", e)
            }
        }
    }

    private fun readLoop() {
        val input = FileInputStream(tun.fileDescriptor)
        val buf = ByteArray(MTU)
        val pollFd = StructPollfd().apply {
            fd = tun.fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }

        var badPackets = 0

        while (alive.get()) {
            val n = try {
                if (Os.poll(arrayOf(pollFd), 500) <= 0) continue
                input.read(buf)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                readerFailed(e)
                return
            } catch (e: IOException) {
                readerFailed(e)
                return
            }
            if (n < 0) {
                readerFailed(null)
                return
            }
            try {
                Packet.parseDnsQuery(buf, n)?.let(::dispatch)
                badPackets = 0
            } catch (e: RejectedExecutionException) {
                readerFailed(e)
                return
            } catch (e: RuntimeException) {
                // One packet the code cannot handle must not stop DNS for the whole device
                Log.w(TAG, "Dropped a packet", e)
                if (++badPackets >= MAX_BAD_PACKETS) {
                    readerFailed(e)
                    return
                }
            }
        }
    }

    // stop() closes the tunnel, which also ends the loop; only an unexpected end is a failure
    private fun readerFailed(cause: Throwable?) {
        if (!alive.get()) return
        Log.e(TAG, "Tunnel reader stopped", cause)
        onFailure(this)
    }

    // Blocklist and cache are answered right here; only real lookups go to the worker pool
    private fun dispatch(query: UdpPacket) {
        val question = DnsMessage.question(query.payload)
        if (question != null && Blocklist.isBlocked(question.name)) {
            DnsMessage.errorReply(query.payload, DnsMessage.RCODE_NXDOMAIN)?.let { write(query, it) }
            QueryLog.add(question, QueryStatus.BLOCKED, 0)
            return
        }
        val cached = cache.get(query.payload)
        if (cached != null) {
            write(query, cached)
            QueryLog.add(question, QueryStatus.CACHED, 0)
            return
        }
        val queued = SystemClock.elapsedRealtime()
        pool.execute { handle(query, question, queued) }
    }

    private fun handle(query: UdpPacket, question: DnsMessage.Question?, queued: Long) {
        try {
            val start = SystemClock.elapsedRealtime()
            // On a slow network lookups pile up behind the workers. Answer the stale ones with
            // SERVFAIL at once instead of spending a worker on them, so the queue drains.
            if (start - queued > QUEUE_TIMEOUT_MS) {
                if (!alive.get()) return
                QueryLog.add(question, QueryStatus.FAILED, (start - queued).toInt())
                DnsMessage.servfail(query.payload)?.let { write(query, it) }
                return
            }
            var status = QueryStatus.OK
            val answer = try {
                doh.query(query.payload).also { cache.put(query.payload, it) }
            } catch (_: Exception) {
                status = QueryStatus.FAILED
                DnsMessage.servfail(query.payload)
            }
            if (!alive.get()) return
            QueryLog.add(question, status, (SystemClock.elapsedRealtime() - start).toInt())
            answer?.let { write(query, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Query failed", e)
        }
    }

    private fun write(query: UdpPacket, answer: ByteArray) {
        if (!alive.get()) return
        // Larger answers do not fit into one IPv4 datagram; SERVFAIL lets the client move on
        val payload = if (answer.size <= Packet.MAX_UDP_PAYLOAD) answer else DnsMessage.servfail(query.payload) ?: return
        val packet = Packet.buildReply(query, DnsMessage.withId(payload, query.payload))
        try {
            synchronized(output) { output.write(packet) }
        } catch (e: IOException) {
            // Expected while the session is being stopped
            if (alive.get()) Log.w(TAG, "Cannot write an answer to the tunnel", e)
        }
    }
}
