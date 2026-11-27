package dev.krist.shieldremote.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dev.krist.shieldremote.App
import dev.krist.shieldremote.Link
import dev.krist.shieldremote.MainActivity
import dev.krist.shieldremote.R
import dev.krist.shieldremote.RemoteState
import dev.krist.shieldremote.proto.Remote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Small foreground service: keeps the process (and therefore the TLS link) alive while the app is
 * visible and for the background grace period afterwards, and hosts the notification / lock-screen
 * remote. Widget and notification buttons are delivered here as [ACTION_KEY] intents.
 */
class RemoteService : Service() {
    companion object {
        const val CHANNEL = "remote"
        private const val ID = 6466
        const val ACTION_KEY = "dev.krist.shieldremote.KEY"
        const val ACTION_STOP = "dev.krist.shieldremote.STOP"
        const val EXTRA_CODE = "code"

        /** PendingIntent that presses [code] on the TV; usable from widgets and notifications. */
        fun keyIntent(context: Context, code: Int): PendingIntent = PendingIntent.getForegroundService(
            context, 1000 + code,
            Intent(context, RemoteService::class.java).setAction(ACTION_KEY).putExtra(EXTRA_CODE, code),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val controller get() = (application as App).controller

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        runCatching { ServiceCompat.startForeground(this, ID, build(controller.state.value), type) }
            .onFailure { stopSelf(); return }
        scope.launch {
            kotlinx.coroutines.flow.combine(controller.state.map { Triple(it.link, it.device?.name, it.volume) }.distinctUntilChanged(), controller.settings) { a, b -> a to b }.collect {
                runCatching { NotificationManagerCompat.from(this@RemoteService).notify(ID, build(controller.state.value)) }
            }
        }
        // drop(1): the initial value may predate onStartCommand's wake-up for widget presses.
        scope.launch { controller.keepAlive.drop(1).collect { if (!it) stopSelf() } }
        // Master "App notifications" switch off: remove the notification by stopping the service.
        scope.launch { controller.settings.collect { if (!it.notifications) { ServiceCompat.stopForeground(this@RemoteService, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() } } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (controller.state.value.device == null || !controller.settings.value.notifications) {
            // Widget/tile presses still work while notifications are off: wake the link without a service.
            if (intent?.action == ACTION_KEY && controller.state.value.device != null) {
                controller.wakeForBackgroundCommand(); intent.getIntExtra(EXTRA_CODE, 0).takeIf { it != 0 }?.let(controller::key)
            }
            stopSelf(); return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_KEY -> {
                controller.wakeForBackgroundCommand()
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                if (code != 0) controller.key(code)
            }
            ACTION_STOP -> { controller.disconnectNow(); stopSelf() }
            else -> if (!controller.keepAlive.value) controller.wakeForBackgroundCommand()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    /** Settings › Notifications › buttons, in the user's order (max 5). */
    private fun buttonRow(keys: List<String>): RemoteViews = RemoteViews(packageName, R.layout.notif_buttons).apply {
        val slots = listOf(R.id.s1, R.id.s2, R.id.s3, R.id.s4, R.id.s5)
        slots.forEachIndexed { i, id ->
            val k = keys.getOrNull(i)?.let { NOTIF_KEYS[it] }
            if (k == null) setViewVisibility(id, android.view.View.GONE)
            else {
                setViewVisibility(id, android.view.View.VISIBLE)
                setImageViewResource(id, k.second)
                setContentDescription(id, keys[i])
                setOnClickPendingIntent(id, keyIntent(this@RemoteService, k.first))
            }
        }
    }

    private val NOTIF_KEYS = mapOf(
        "BACK" to (Remote.Key.BACK to R.drawable.ic_k_back), "HOME" to (Remote.Key.HOME to R.drawable.ic_k_home),
        "UP" to (Remote.Key.DPAD_UP to R.drawable.ic_k_up), "DOWN" to (Remote.Key.DPAD_DOWN to R.drawable.ic_k_down),
        "LEFT" to (Remote.Key.DPAD_LEFT to R.drawable.ic_k_left), "RIGHT" to (Remote.Key.DPAD_RIGHT to R.drawable.ic_k_right),
        "OK" to (Remote.Key.DPAD_CENTER to R.drawable.ic_k_ok), "PLAY_PAUSE" to (Remote.Key.MEDIA_PLAY_PAUSE to R.drawable.ic_k_playpause),
        "VOL_DOWN" to (Remote.Key.VOLUME_DOWN to R.drawable.ic_k_voldown), "VOL_UP" to (Remote.Key.VOLUME_UP to R.drawable.ic_k_volup),
        "MUTE" to (Remote.Key.VOLUME_MUTE to R.drawable.ic_k_mute), "POWER" to (Remote.Key.POWER to R.drawable.ic_k_power),
    )

    private fun remoteViews(keys: List<String>): RemoteViews = RemoteViews(packageName, R.layout.notif_remote).apply {
        removeAllViews(R.id.n_custom)
        if (keys.isNotEmpty()) addView(R.id.n_custom, buttonRow(keys))
        mapOf(
            R.id.n_back to Remote.Key.BACK, R.id.n_up to Remote.Key.DPAD_UP, R.id.n_home to Remote.Key.HOME,
            R.id.n_play to Remote.Key.MEDIA_PLAY_PAUSE, R.id.n_left to Remote.Key.DPAD_LEFT, R.id.n_ok to Remote.Key.DPAD_CENTER,
            R.id.n_right to Remote.Key.DPAD_RIGHT, R.id.n_volup to Remote.Key.VOLUME_UP, R.id.n_mute to Remote.Key.VOLUME_MUTE,
            R.id.n_down to Remote.Key.DPAD_DOWN, R.id.n_power to Remote.Key.POWER, R.id.n_voldown to Remote.Key.VOLUME_DOWN,
        ).forEach { (id, code) -> setOnClickPendingIntent(id, keyIntent(this@RemoteService, code)) }
    }

    private fun build(st: RemoteState): Notification {
        val status = when (st.link) {
            Link.CONNECTED -> "Connected"
            Link.CONNECTING, Link.RECONNECTING -> "Reconnecting…"
            Link.NO_WIFI -> "Waiting for Wi-Fi"
            Link.NEEDS_PAIRING -> "Pairing needed"
            else -> "Idle"
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RemoteService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val cfg = controller.settings.value
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentTitle(st.device?.name ?: "Shield Remote")
            .setContentText(status + volumeSuffix(st.volume) + if (cfg.notifRemote) " · expand for remote" else "")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            // Settings › Notifications › Show on lock screen
            .setVisibility(if (cfg.notifRemote && cfg.notifLockScreen) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (cfg.notifRemote) {
            b.setStyle(NotificationCompat.DecoratedCustomViewStyle())
            if (cfg.notifButtons.isNotEmpty()) b.setCustomContentView(buttonRow(cfg.notifButtons))
            b.setCustomBigContentView(remoteViews(cfg.notifButtons))
        }
        return b.addAction(0, "Disconnect", stop)
            .build()
    }
}

/**
 * The Shield reports Volume(level=0, max=0) when the TV/AVR controls volume over CEC: it doesn't know the
 * level, so only show one when max > 0.
 */
internal fun volumeSuffix(v: dev.krist.shieldremote.VolumeInfo?): String = when {
    v == null -> ""
    v.muted -> " · Muted"
    else -> "" // never a number: the Shield reports bogus levels (e.g. 15 when the TV is off, 0/0 over CEC)
}
