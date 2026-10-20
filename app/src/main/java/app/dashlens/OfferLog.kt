package app.dashlens

import android.content.Context

/** Tracks the outcome of the offer currently (or last) on screen: accepted, passed or unknown. */
object OfferLog {
    @Volatile private var currentId = -1L
    @Volatile private var open = false
    @Volatile private var goneAt = 0L
    @Volatile private var wasOnOrder = false

    fun begin(id: Long, onOrderBefore: Boolean) {
        currentId = id; open = true; goneAt = 0L; wasOnOrder = onOrderBefore
    }

    fun gone() { if (goneAt == 0L) goneAt = System.currentTimeMillis() }

    fun reappeared() { goneAt = 0L }

    private fun inWindow() = goneAt == 0L || System.currentTimeMillis() - goneAt < 25_000L

    /** The screen changed to an order screen after the offer closed. Ignored when you were already on an order (stacked offers). */
    fun autoAccepted(c: Context) {
        if (open && currentId > 0L && !wasOnOrder && inWindow()) { Db.get(c).setOutcome(currentId, "ACCEPTED"); open = false }
    }

    /** You tapped "On order" yourself right after an offer: always counts. */
    fun manualAccepted(c: Context) {
        if (open && currentId > 0L && inWindow()) { Db.get(c).setOutcome(currentId, "ACCEPTED"); open = false }
    }

    /** Offer gone for 20 s with no order detected. */
    fun resolve(c: Context) {
        if (open && currentId > 0L) {
            Db.get(c).setOutcome(currentId, if (wasOnOrder) "UNKNOWN" else "PASSED")
            open = false
        }
    }

    /**
     * An order stretch just ended (state left ON ORDER). Attach its real duration and GPS miles to the accepted
     * offer(s) it belongs to, so DashLens can learn your true order times. Stacked offers share the stretch equally.
     */
    fun linkOrder(c: Context, startMs: Long, minutes: Double, miles: Double) {
        if (minutes < 1.0) return
        val db = Db.get(c)
        val open = db.unmeasuredAccepted(startMs - 20 * 60_000L)
        if (open.isEmpty()) return
        val n = open.size
        for (o in open) db.setActual(o.id, minutes / n, miles / n)
    }
}
