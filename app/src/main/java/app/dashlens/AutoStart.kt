package app.dashlens

/**
 * Decides whether DashLens should start a shift by itself because a driver app (DoorDash or Uber) just showed an
 * online or on-order screen. Pure logic, so it is unit-tested; the service does the actual starting.
 *
 * It never starts while a shift is already running, never on a "not dashing" screen, not for 10 minutes after you
 * stopped a shift by hand (so tapping Stop is not undone by the next screen change), and at most once a minute
 * (so a refusal from Android does not turn into a retry on every screen update).
 */
object AutoStart {
    const val COOLDOWN_AFTER_MANUAL_STOP_MS = 10 * 60_000L
    const val RETRY_MS = 60_000L

    fun shouldStart(enabled: Boolean, running: Boolean, seen: DState, now: Long, lastManualStopMs: Long, lastTryMs: Long): Boolean {
        if (!enabled || running || seen == DState.OFFLINE) return false
        if (now - lastManualStopMs < COOLDOWN_AFTER_MANUAL_STOP_MS) return false
        if (now - lastTryMs < RETRY_MS) return false
        return true
    }
}
