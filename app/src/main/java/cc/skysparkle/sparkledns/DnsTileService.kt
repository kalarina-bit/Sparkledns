package cc.skysparkle.sparkledns

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class DnsTileService : TileService() {

    companion object {
        fun refresh(context: Context) {
            runCatching {
                TileService.requestListeningState(context, ComponentName(context, DnsTileService::class.java))
            }
        }
    }

    private var listening: CoroutineScope? = null

    // Follows the VPN state while the panel is open, so the tile flips as soon as the tunnel is up
    override fun onStartListening() {
        listening?.cancel()
        listening = MainScope().also { scope ->
            scope.launch { DohVpnService.active.collect { updateTile() } }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    override fun onClick() {
        val running = DohVpnService.active.value != null
        if (!running) {
            val provider = Prefs(this).resolve()
            val urlValid = provider.url.toHttpUrlOrNull()?.isHttps == true
            // VPN consent or a broken custom URL can only be handled in the app
            if (VpnService.prepare(this) != null || !urlValid) {
                openApp()
                return
            }
        }
        val action = if (running) DohVpnService.ACTION_STOP else DohVpnService.ACTION_START
        try {
            startService(Intent(this, DohVpnService::class.java).setAction(action))
        } catch (e: Exception) {
            Log.w("SparkleDNS", "Tile could not reach the service", e)
            openApp()
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val active = DohVpnService.active.value
        tile.state = if (active != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = (active ?: Prefs(this).resolve()).displayName(this)
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
