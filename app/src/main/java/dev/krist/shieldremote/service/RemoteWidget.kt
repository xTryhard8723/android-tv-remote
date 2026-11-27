package dev.krist.shieldremote.service

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import dev.krist.shieldremote.R
import dev.krist.shieldremote.proto.Remote

/** Home-screen / lock-screen widget with the core keys. Presses go through [RemoteService]. */
class RemoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_remote).apply {
            mapOf(
                R.id.w_power to Remote.Key.POWER, R.id.w_up to Remote.Key.DPAD_UP, R.id.w_home to Remote.Key.HOME,
                R.id.w_volup to Remote.Key.VOLUME_UP, R.id.w_left to Remote.Key.DPAD_LEFT, R.id.w_ok to Remote.Key.DPAD_CENTER,
                R.id.w_right to Remote.Key.DPAD_RIGHT, R.id.w_mute to Remote.Key.VOLUME_MUTE, R.id.w_back to Remote.Key.BACK,
                R.id.w_down to Remote.Key.DPAD_DOWN, R.id.w_play to Remote.Key.MEDIA_PLAY_PAUSE, R.id.w_voldown to Remote.Key.VOLUME_DOWN,
            ).forEach { (id, code) -> setOnClickPendingIntent(id, RemoteService.keyIntent(context, code)) }
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}
