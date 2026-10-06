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
        excluded.forEach { runCatching { builder.addDisallowedApplication(it) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        // The new interface replaces the old one in place, so DNS never leaks during a server switch
        val tun = runCatching { builder.establish() }.getOrNull()
        val previous = session
        if (tun == null) {
            stopSession()
            stopSelf()
            return
        }
        session = Session(tun, DohClient(this, provider)).also { it.start() }
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
        doh.shutdown()
        runCatching { tun.close() }
    }

    private fun readLoop() {
        val input = FileInputStream(tun.fileDescriptor)
        val buf = ByteArray(MTU)
        val pollFd = StructPollfd().apply {
            fd = tun.fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }

        while (alive.get()) {
            try {
                if (Os.poll(arrayOf(pollFd), 500) <= 0) continue
                val n = input.read(buf)
                if (n < 0) break
                val query = Packet.parseDnsQuery(buf, n) ?: continue
                dispatch(query)
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.EINTR) break
            } catch (_: IOException) {
                break
            } catch (_: RejectedExecutionException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Tunnel reader failed", e)
                break
            }
        }
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
        pool.execute { handle(query, question) }
    }

    private fun handle(query: UdpPacket, question: DnsMessage.Question?) {
        try {
            val start = SystemClock.elapsedRealtime()
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
        } catch (_: IOException) {
        }
    }
}
