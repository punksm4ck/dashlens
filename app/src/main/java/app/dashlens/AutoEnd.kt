package app.dashlens

/**
 * Decides when DashLens should end a shift by itself: you went offline or logged out of every driver app you use.
 * Pure logic, so it is unit-tested; [OfferService] feeds it what each app last showed and does the stopping.
 *
 * "Off" means the app's last screen was an offline or logged-out screen. An app that last showed an online or on-order
 * screen counts as still driving (until a later screen says otherwise), and an app DashLens never saw does not count
 * either way. At least one app must have shown an explicit offline / logged-out screen, so a shift you start by hand
 * before opening any driver app is not ended. It never ends a shift while you are on an order.
 */
object AutoEnd {
    /** True when every enabled app DashLens has seen last showed an offline / logged-out screen, and at least one did. */
    fun allOff(seen: Map<String, Seen>, enabled: Set<String>): Boolean {
        val mine = enabled.mapNotNull { seen[it] }
        if (mine.none { it.state == DState.OFFLINE }) return false
        return mine.none { it.state != DState.OFFLINE }
    }

    /** [offSinceMs] = when [allOff] last became true (0 = not off). [minutes] <= 0 turns auto end off. */
    fun shouldEnd(allOff: Boolean, offSinceMs: Long, now: Long, minutes: Double, onOrder: Boolean): Boolean =
        minutes > 0.0 && allOff && !onOrder && offSinceMs > 0L && now - offSinceMs >= minutes * 60_000.0
}
