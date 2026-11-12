package app.dashlens

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What one driver app last showed, and when (see [StateMerge]). */
data class Seen(val state: DState, val atMs: Long)

/**
 * Works out whether a driver app is showing an order, an online (waiting) screen, or an offline screen.
 * Online / offline wording below comes from real Dasher screens ("Finding offers", "You're dashing now", "Dash now to
 * check for offers"). The on-order wording is still a best guess: copy the screen log during an order to tune it.
 *
 * Uber has its own word lists. "finding trips" (online) and "you've arrived", "start shopping", "expected by"
 * (on order) come from real Uber Driver screens; "you're offline" is a guess until a screenshot confirms it.
 */
object StateDetector {
    const val DEF_ORDER = "confirm pickup,pick up from,pickup from,deliver to,drop off,dropoff,navigate to,directions,arrived at,verify order,start delivery,head to,heading to"
    const val DEF_ONLINE = "finding offers,finding orders,looking for orders,looking for offers,waiting for orders,dashing now,good place to wait,zone offer wait,end dash,pause orders"
    const val DEF_OFFLINE = "dash now,start dash,go online,schedule a dash"

    const val UBER_DEF_ORDER = "you've arrived,start shopping,expected by"
    const val UBER_DEF_ONLINE = "finding trips,you're online"
    const val UBER_DEF_OFFLINE = "you're offline"

    /** Preference keys for the order / online / offline word lists of a platform. */
    fun keys(platform: String): Triple<String, String, String> =
        if (platform == Platform.UBER) Triple("kw_uber_order", "kw_uber_online", "kw_uber_offline")
        else Triple("kw_order", "kw_online", "kw_offline")

    /** Built-in word lists (order, online, offline) of a platform. */
    fun defaults(platform: String): Triple<String, String, String> =
        if (platform == Platform.UBER) Triple(UBER_DEF_ORDER, UBER_DEF_ONLINE, UBER_DEF_OFFLINE)
        else Triple(DEF_ORDER, DEF_ONLINE, DEF_OFFLINE)

    fun words(csv: String): List<String> = csv.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    private fun list(c: Context, key: String, def: String): List<String> = words(Prefs.getString(c, key).ifBlank { def })

    fun detect(c: Context, texts: List<String>, looksLikeOffer: Boolean, platform: String = Platform.DOORDASH): DState? {
        val k = keys(platform)
        val d = defaults(platform)
        return classify(texts, looksLikeOffer, list(c, k.first, d.first), list(c, k.second, d.second), list(c, k.third, d.third))
    }

    /** The pure part of [detect] (no Android calls), so it can be unit-tested. */
    fun classify(texts: List<String>, looksLikeOffer: Boolean, order: List<String>, online: List<String>, offline: List<String>): DState? {
        if (looksLikeOffer) return DState.ONLINE          // an offer popping up means you are online, not on an order yet
        val all = texts.joinToString(" | ").lowercase().replace('’', '\'')
        if (order.any { all.contains(it) }) return DState.ORDER
        if (online.any { all.contains(it) }) return DState.ONLINE
        if (offline.any { all.contains(it) }) return DState.OFFLINE
        return null
    }

    private var lastLogged: DState? = null
    private var lastLoggedPlatform = ""
    private var loggedOnce = false

    /** Keeps the last 12 detection changes with the screen text, for tuning the keywords. */
    fun log(c: Context, ds: DState?, texts: List<String>, platform: String = Platform.DOORDASH) {
        if (loggedOnce && ds == lastLogged && platform == lastLoggedPlatform) return
        loggedOnce = true; lastLogged = ds; lastLoggedPlatform = platform
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val entry = "[" + stamp + "] " + platform + " detected=" + (ds?.name ?: "none") + "\n" + texts.joinToString(" | ").take(500)
        val old = Prefs.getString(c, "screen_log").split("\n----\n").filter { it.isNotBlank() }
        Prefs.putString(c, "screen_log", (old + entry).takeLast(12).joinToString("\n----\n"))
    }
}

/**
 * With two driver apps the shift state is the strongest state any app showed recently: ORDER beats ONLINE beats
 * OFFLINE. Without this, a glance at Uber's "Finding trips" screen in the middle of a DoorDash order would end that
 * order and the rest of its miles would be logged as idle.
 *
 * An app's ORDER is trusted for 45 minutes and its ONLINE for 30 without a fresh screen from that app, because many
 * drivers navigate with Maps or Waze. Returns null when no app has shown anything recent. With a single app this
 * always returns the state that app just showed, so DoorDash-only use behaves exactly as before.
 */
object StateMerge {
    const val ORDER_FRESH_MS = 45 * 60_000L
    const val ONLINE_FRESH_MS = 30 * 60_000L

    private fun rank(s: DState) = when (s) { DState.ORDER -> 2; DState.ONLINE -> 1; DState.OFFLINE -> 0 }

    fun combine(seen: Map<String, Seen>, now: Long): DState? {
        var best: DState? = null
        for (s in seen.values) {
            val fresh = when (s.state) {
                DState.ORDER -> now - s.atMs <= ORDER_FRESH_MS
                DState.ONLINE -> now - s.atMs <= ONLINE_FRESH_MS
                DState.OFFLINE -> true
            }
            if (!fresh) continue
            if (best == null || rank(s.state) > rank(best)) best = s.state
        }
        return best
    }
}
