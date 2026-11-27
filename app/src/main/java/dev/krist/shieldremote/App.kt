package dev.krist.shieldremote

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.krist.shieldremote.service.RemoteService
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class App : Application() {
    lateinit var controller: RemoteController; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        controller = RemoteController(this)
        controller.settings.value.let { st -> dev.krist.shieldremote.ui.Haptics.init(this, st.haptics, st.sound) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(RemoteService.CHANNEL, "Remote connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the Shield connection alive briefly after you leave the app"
                setShowBadge(false)
            }
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ALERTS, "Connection alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Connection to the Shield lost or restored (Settings › Notifications)"
            }
        )
        watchConnection()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                controller.onForeground()
                if (controller.state.value.device != null && controller.settings.value.notifications) startKeepAliveService()
            }
            override fun onStop(owner: LifecycleOwner) { controller.onBackground() }
        })
    }

    /** Started only while the app is visible (allowed on Android 12+); it then outlives the UI for the grace period. */
    fun startKeepAliveService() {
        if (!controller.settings.value.notifications) return
        runCatching { ContextCompat.startForegroundService(this, Intent(this, RemoteService::class.java)) }
    }

    /** Settings › Notifications › Connection alerts: lost (after 5 s of trying) and restored. */
    private fun watchConnection() {
        val scope = kotlinx.coroutines.MainScope()
        scope.launch {
            var lostPosted = false
            var lostJob: kotlinx.coroutines.Job? = null
            controller.state.map { it.link to (it.device?.name ?: "Shield") }.distinctUntilChanged().collect { (link, name) ->
                val nm = androidx.core.app.NotificationManagerCompat.from(this@App)
                when (link) {
                    Link.CONNECTED -> {
                        lostJob?.cancel(); lostJob = null
                        if (lostPosted) { lostPosted = false; post(nm, "Reconnected to $name", "The remote is ready again.") }
                    }
                    Link.RECONNECTING, Link.NO_WIFI -> if (lostJob == null && !lostPosted) lostJob = scope.launch {
                        kotlinx.coroutines.delay(5_000)
                        if (controller.settings.value.notifications && controller.settings.value.connectionAlerts) {
                            lostPosted = true
                            post(nm, "Lost connection to $name", if (link == Link.NO_WIFI) "Waiting for Wi-Fi…" else "Trying to reconnect…")
                        }
                    }
                    else -> { lostJob?.cancel(); lostJob = null } // paused / pairing: not a connection problem
                }
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun post(nm: androidx.core.app.NotificationManagerCompat, title: String, text: String) {
        val st = controller.settings.value
        if (!st.notifications || !st.connectionAlerts || !nm.areNotificationsEnabled()) return
        val open = android.app.PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            nm.notify(ALERT_ID, androidx.core.app.NotificationCompat.Builder(this, ALERTS)
                .setSmallIcon(R.drawable.ic_stat_remote).setContentTitle(title).setContentText(text)
                .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(60_000).build())
        }
    }

    companion object {
        lateinit var instance: App; private set
        const val ALERTS = "alerts"
        private const val ALERT_ID = 6467
    }
}
