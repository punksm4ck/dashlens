package app.dashlens

import android.content.Context
import android.location.Location

enum class DState(val label: String) {
    OFFLINE("Paused (not logging)"),
    ONLINE("Online"),
    ORDER("On order")
}

/** Process-wide shift tracker. Everything here runs on the main thread. */
object Tracker {
    @Volatile var state = DState.OFFLINE
    @Volatile var running = false
    @Volatile var manualUntil = 0L          // auto-detection is ignored until this time after a manual change
    @Volatile var offlineSince = 0L         // when the current Paused state began
    @Volatile var offlineAuto = false       // true when the pause came from screen auto-detection (eligible for auto-stop)
    @Volatile var lastFix: Location? = null // latest decent GPS fix, used to tag where offers were accepted
    var onChange: (() -> Unit)? = null

    private var orderStart = 0L             // when the current ON ORDER stretch began
    private var orderMeters = 0.0
    private var segStart = 0L
    private var segMeters = 0.0
    private var last: Location? = null
    private var ctx: Context? = null

    fun init(c: Context) { ctx = c.applicationContext }

    private fun persist() {
        val c = ctx ?: return
        Prefs.putBool(c, "trk_running", running)
        Prefs.putString(c, "trk_state", state.name)
    }

    /** Resume after the system restarted the service. */
    fun restore(c: Context) {
        init(c)
        if (!running && Prefs.getBool(c, "trk_running", false)) {
            running = true
            state = runCatching { DState.valueOf(Prefs.getString(c, "trk_state")) }.getOrDefault(DState.ONLINE)
            segStart = System.currentTimeMillis(); segMeters = 0.0; last = null
        }
    }

    fun begin(initial: DState) {
        running = true
        state = initial
        segStart = System.currentTimeMillis(); segMeters = 0.0; last = null
        orderStart = if (initial == DState.ORDER) segStart else 0L; orderMeters = 0.0
        persist(); onChange?.invoke()
    }

    /** Write the open chunk to the database and start a fresh one (called every minute and on state changes). */
    fun flush(now: Long = System.currentTimeMillis()) {
        val c = ctx ?: return
        if (running && state != DState.OFFLINE && now > segStart) {
            Db.get(c).insert(segStart, now, state.name, segMeters)
        }
        segStart = now; segMeters = 0.0
    }

    fun setState(s: DState, manual: Boolean = false) {
        if (!running || s == state) return
        val now = System.currentTimeMillis()
        flush(now)
        if (state == DState.ORDER) closeOrder(now)
        state = s
        if (s == DState.ORDER) { orderStart = now; orderMeters = 0.0 }
        if (manual) manualUntil = now + 5 * 60_000L
        if (s == DState.OFFLINE) { offlineSince = now; offlineAuto = !manual } else offlineSince = 0L
        if (manual && s == DState.ORDER) ctx?.let { OfferLog.manualAccepted(it) }
        persist(); onChange?.invoke()
    }

    private fun closeOrder(now: Long) {
        val c = ctx ?: return
        if (orderStart > 0L) OfferLog.linkOrder(c, orderStart, (now - orderStart) / 60_000.0, orderMeters / 1609.344)
        orderStart = 0L; orderMeters = 0.0
    }

    fun end() {
        if (state == DState.ORDER) closeOrder(System.currentTimeMillis())
        flush()
        running = false; state = DState.OFFLINE; last = null
        persist(); onChange?.invoke()
    }

    fun onLocation(loc: Location) {
        if (!running) return
        if (!loc.hasAccuracy() || loc.accuracy <= 100f) lastFix = loc
        if (loc.hasAccuracy() && loc.accuracy > 40f) return          // poor fix
        val prev = last
        if (prev == null) { last = loc; return }
        val dt = (loc.time - prev.time) / 1000.0
        if (dt <= 0.0) return
        val d = prev.distanceTo(loc).toDouble()
        if (d / dt > 55.0) { last = loc; return }                    // > ~120 mph: GPS jump, ignore
        if (d < 6.0) return                                          // jitter while stopped
        last = loc
        if (state != DState.OFFLINE) segMeters += d
        if (state == DState.ORDER) orderMeters += d
    }
}
