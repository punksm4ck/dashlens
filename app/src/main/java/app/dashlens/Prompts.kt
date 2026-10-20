package app.dashlens

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

object Prompts {
    private const val CH = "prompt"
    private const val ID_START = 78
    private const val ID_INFO = 79

    private fun nm(c: Context): NotificationManager {
        val m = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        m.createNotificationChannel(NotificationChannel(CH, "Shift prompts", NotificationManager.IMPORTANCE_HIGH))
        return m
    }

    /** "Dasher looks active" with a Start shift button. A button tap is allowed to start the location service. */
    fun promptStart(c: Context) {
        val pi = PendingIntent.getForegroundService(
            c, 21, Intent(c, TrackerService::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val a = Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat_dash), "Start shift", pi).build()
        val n = Notification.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat_dash)
            .setContentTitle("Dasher looks active")
            .setContentText("Start a DashLens shift to log miles?")
            .addAction(a)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .build()
        nm(c).notify(ID_START, n)
    }

    fun cancelStart(c: Context) {
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_START)
    }

    fun info(c: Context, title: String, text: String) {
        val n = Notification.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat_dash)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        nm(c).notify(ID_INFO, n)
    }
}
