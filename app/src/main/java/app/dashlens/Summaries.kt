package app.dashlens

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

object Summaries {
    private const val CH = "summaries"

    /** Schedules the next Sunday 23:59 (local time) weekly summary. Exact if the OS allows it, otherwise within a few minutes. */
    fun schedule(c: Context) {
        val now = ZonedDateTime.now()
        var t = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            .withHour(23).withMinute(59).withSecond(0).withNano(0)
        if (!t.isAfter(now)) t = t.plusWeeks(1)
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            c, 7, Intent(c, SummaryReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val at = t.toInstant().toEpochMilli()
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms())
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun saveWeek(c: Context, weekStart: LocalDate, force: Boolean): Report {
        val r = Reports.build(c, Period.WEEK, weekStart)
        if (force || r.totals.onlineMs > 0L) {
            Exporter.saveReport(c, r, "DashLens_week_" + weekStart.toString())
        }
        Prefs.putString(c, "last_week_saved", weekStart.toString())
        return r
    }

    /** Saves files for any finished week that was missed (phone off, alarm delayed, ...). */
    fun catchUp(c: Context) {
        val first = Db.get(c).firstStart() ?: return
        var w = Reports.weekStart(Instant.ofEpochMilli(first).atZone(ZoneId.systemDefault()).toLocalDate())
        val thisWeek = Reports.weekStart(LocalDate.now())
        var last = Prefs.getString(c, "last_week_saved")
        while (w.isBefore(thisWeek)) {
            if (last.isEmpty() || w.toString() > last) { saveWeek(c, w, false); last = w.toString() }
            w = w.plusWeeks(1)
        }
    }

    fun notifyWeek(c: Context, r: Report) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "Weekly summaries", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            c, 8, Intent(c, ReportsActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val t = r.totals
        val hint = Finance.taxHint(c)
        val n = Notification.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat_dash)
            .setContentTitle("Weekly summary: " + r.title)
            .setContentText(
                "On order " + Reports.mi(t.orderM) + " mi / " + Reports.dur(t.orderMs) +
                    "  |  Online " + Reports.mi(t.onlineM) + " mi / " + Reports.dur(t.onlineMs)
            )
            .setStyle(Notification.BigTextStyle().bigText(
                (if (hint.isNotEmpty()) hint + "\n\n" else "") + Reports.text(c, r)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(77, n)
    }
}

class SummaryReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Tracker.flush()
        // The alarm fires at Sunday 23:59; if it is delayed a little past midnight it still belongs to that week.
        val ref = LocalDateTime.now().minusMinutes(30).toLocalDate()
        val r = Summaries.saveWeek(c, Reports.weekStart(ref), true)
        Summaries.notifyWeek(c, r)
        Summaries.schedule(c)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) Prefs.putBool(c, "trk_running", false)
        Summaries.schedule(c)
        Summaries.catchUp(c)
    }
}
