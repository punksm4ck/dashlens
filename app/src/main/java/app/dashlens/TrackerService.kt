package app.dashlens

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.time.LocalDate

class TrackerService : Service(), LocationListener {

    companion object {
        const val ACTION_STOP = "app.dashlens.STOP"
        const val ACTION_ONLINE = "app.dashlens.ONLINE"
        const val ACTION_ORDER = "app.dashlens.ORDER"
        const val ACTION_PAUSE = "app.dashlens.PAUSE"
        private const val CHANNEL = "tracking"
        private const val NOTIF_ID = 42

        fun start(c: Context) {
            val i = Intent(c, TrackerService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun send(c: Context, action: String) {
            c.startService(Intent(c, TrackerService::class.java).setAction(action))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var lm: LocationManager
    private var subscribed = false

    private val tick = object : Runnable {
        override fun run() {
            Tracker.flush()
            val lim = Prefs.get(this@TrackerService, "auto_stop_min")
            if (lim > 0.0 && Tracker.state == DState.OFFLINE && Tracker.offlineAuto && Tracker.offlineSince > 0L &&
                System.currentTimeMillis() - Tracker.offlineSince >= lim * 60_000.0
            ) {
                Prompts.info(this@TrackerService, "Shift stopped",
                    "Dasher was not active for " + Prefs.fmt(lim) + " min. Tap Start shift when you go online again.")
                stopTracking()
                return
            }
            refreshNotification()
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Tracker.init(this)
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Shift tracking", NotificationManager.IMPORTANCE_LOW))
        Tracker.onChange = { refreshNotification() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Tracker.restore(this)
        try {
            goForeground()                               // must happen quickly after startForegroundService
        } catch (e: SecurityException) {
            stopSelf()                                   // location permission missing
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> { stopTracking(); return START_NOT_STICKY }
            ACTION_ONLINE -> Tracker.setState(DState.ONLINE, true)
            ACTION_ORDER -> Tracker.setState(DState.ORDER, true)
            ACTION_PAUSE -> Tracker.setState(DState.OFFLINE, true)
            else -> if (!Tracker.running) Tracker.begin(DState.ONLINE)
        }
        if (!Tracker.running) { stopTracking(); return START_NOT_STICKY }

        Prompts.cancelStart(this)
        subscribe()
        Summaries.schedule(this)
        return START_STICKY
    }

    private fun subscribe() {
        if (!subscribed) {
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 10f, this)
                subscribed = true
            } catch (e: SecurityException) {
                // location permission missing: UI asks for it before starting
            } catch (e: IllegalArgumentException) {
                // no GPS provider
            }
        }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 60_000L)
    }

    private fun stopTracking() {
        handler.removeCallbacks(tick)
        if (subscribed) { try { lm.removeUpdates(this) } catch (_: Exception) {}; subscribed = false }
        Tracker.end()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        if (subscribed) { try { lm.removeUpdates(this) } catch (_: Exception) {} }
        Tracker.flush()
        Tracker.onChange = null
        super.onDestroy()
    }

    // ---- location callbacks
    override fun onLocationChanged(location: Location) { Tracker.onLocation(location) }
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    // ---- notification
    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIF_ID, n)
    }

    private fun refreshNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun action(label: String, action: String, code: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, code, Intent(this, TrackerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat_dash), label, pi
        ).build()
    }

    private fun buildNotification(): Notification {
        val now = System.currentTimeMillis()
        val t = Reports.totals(this, Reports.dayStart(LocalDate.now()), now)
        val td = Finance.today(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val st = Tracker.state
        val head = Finance.money(td.income) + " est. | " + td.accepted + " accepted | " +
            (if (td.onlineMs > 0L) String.format(java.util.Locale.US, "$%.0f/hr | ", td.perHour) else "") +
            Reports.mi(t.onlineM) + " mi"
        val goal = Finance.goalLine(this, td)
        val big = head + "\nOn orders: " + Reports.mi(t.orderM) + " mi / " + Reports.dur(t.orderMs) +
            "\nOnline total: " + Reports.mi(t.onlineM) + " mi / " + Reports.dur(t.onlineMs) +
            (if (goal.isNotEmpty()) "\n" + goal else "")
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_dash)
            .setContentTitle("DashLens: " + st.label)
            .setContentText(head)
            .setStyle(Notification.BigTextStyle().bigText(big))
            .setContentIntent(open)
            .setOngoing(true)
        if (st == DState.ORDER) b.addAction(action("Order done", ACTION_ONLINE, 1))
        else b.addAction(action("On order", ACTION_ORDER, 2))
        if (st == DState.OFFLINE) b.addAction(action("Resume", ACTION_ONLINE, 3))
        else b.addAction(action("Pause", ACTION_PAUSE, 4))
        b.addAction(action("Stop shift", ACTION_STOP, 5))
        return b.build()
    }
}
