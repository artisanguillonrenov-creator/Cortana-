package io.github.artisanguillonrenov.cortana.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.artisanguillonrenov.cortana.CortanaApp
import io.github.artisanguillonrenov.cortana.R
import io.github.artisanguillonrenov.cortana.ui.MainActivity
import io.github.artisanguillonrenov.cortana.util.CLog
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * §10 — keeps the process alive while a task runs (the owner may be in another app).
 * Type specialUse (never dataSync: capped at 6 h/24 h on Android 15).
 */
class CortanaForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val objective = intent?.getStringExtra(EXTRA_OBJECTIVE).orEmpty()
        val step = intent?.getStringExtra(EXTRA_STEP).orEmpty()
        val n = (application as CortanaApp).container.notifications.automation(objective, step)
        try {
            ServiceCompat.startForeground(this, Notifications.ID_AUTOMATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (t: Throwable) {
            CLog.w("startForeground failed", t)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val EXTRA_OBJECTIVE = "objective"
        private const val EXTRA_STEP = "step"
        @Volatile private var running = false

        /** Returns true when the service was started (may be refused by background-start rules). */
        fun start(context: Context, objective: String): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, CortanaForegroundService::class.java).putExtra(EXTRA_OBJECTIVE, objective.take(200)))
            running = true
            true
        } catch (t: Throwable) {
            CLog.w("foreground service start refused", t)
            false
        }

        @android.annotation.SuppressLint("MissingPermission") // checked through canPost()
        fun update(context: Context, objective: String, step: String) {
            if (!running) return
            val c = (context.applicationContext as CortanaApp).container
            runCatching {
                if (c.notifications.canPost()) NotificationManagerCompat.from(context).notify(Notifications.ID_AUTOMATION, c.notifications.automation(objective.take(200), step))
            }
        }

        fun stop(context: Context) {
            running = false
            runCatching { context.stopService(Intent(context, CortanaForegroundService::class.java)) }
        }
    }
}

/** STOP action from the foreground-service notification (§9.4). */
class KillSwitchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) {
            (context.applicationContext as CortanaApp).container.killSwitch.halt("notification")
        }
    }

    companion object {
        const val ACTION_STOP = "io.github.artisanguillonrenov.cortana.STOP"
    }
}

/** Quick-settings kill-switch tile (§9.4). Halting is one tap; resuming requires the device credential in the app. */
class KillSwitchTileService : TileService() {
    private var scope: kotlinx.coroutines.CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val c = (application as CortanaApp).container
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main).also { s ->
            s.launch { c.killSwitch.halted.collectLatest { render(it) } }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    private fun render(halted: Boolean) {
        val t = qsTile ?: return
        t.label = if (halted) "Cortana arrêtée" else "Stop Cortana"
        t.subtitle = if (halted) "Toucher pour reprendre" else "Autonomie active"
        t.state = if (halted) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        t.icon = Icon.createWithResource(this, R.drawable.ic_stop)
        t.updateTile()
    }

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // only used below API 34
    override fun onClick() {
        super.onClick()
        val c = (application as CortanaApp).container
        if (!c.killSwitch.isHalted()) {
            c.killSwitch.halt("tuile")
            render(true)
        } else {
            // Resume needs the device credential: open the app on the resume screen.
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_RESUME, true)
            val pi = android.app.PendingIntent.getActivity(this, 3, i, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            if (android.os.Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(pi)
            else @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }
}
