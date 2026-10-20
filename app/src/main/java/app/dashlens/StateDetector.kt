package app.dashlens

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Works out whether the Dasher app is showing an order, an online (waiting) screen, or an offline screen.
 * Online / offline wording below comes from real Dasher screens ("Finding offers", "You're dashing now", "Dash now to
 * check for offers"). The on-order wording is still a best guess: copy the screen log during an order to tune it.
 */
object StateDetector {
    const val DEF_ORDER = "confirm pickup,pick up from,pickup from,deliver to,drop off,dropoff,navigate to,directions,arrived at,verify order,start delivery,head to,heading to"
    const val DEF_ONLINE = "finding offers,finding orders,looking for orders,looking for offers,waiting for orders,dashing now,good place to wait,zone offer wait,end dash,pause orders"
    const val DEF_OFFLINE = "dash now,start dash,go online,schedule a dash"

    private fun list(c: Context, key: String, def: String): List<String> =
        Prefs.getString(c, key).ifBlank { def }.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    fun detect(c: Context, texts: List<String>, looksLikeOffer: Boolean): DState? {
        if (looksLikeOffer) return DState.ONLINE          // an offer popping up means you are online, not on an order yet
        val all = texts.joinToString(" | ").lowercase().replace('\u2019', '\'')
        if (list(c, "kw_order", DEF_ORDER).any { all.contains(it) }) return DState.ORDER
        if (list(c, "kw_online", DEF_ONLINE).any { all.contains(it) }) return DState.ONLINE
        if (list(c, "kw_offline", DEF_OFFLINE).any { all.contains(it) }) return DState.OFFLINE
        return null
    }

    private var lastLogged: DState? = null
    private var loggedOnce = false

    /** Keeps the last 12 detection changes with the screen text, for tuning the keywords. */
    fun log(c: Context, ds: DState?, texts: List<String>) {
        if (loggedOnce && ds == lastLogged) return
        loggedOnce = true; lastLogged = ds
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val entry = "[" + stamp + "] detected=" + (ds?.name ?: "none") + "\n" + texts.joinToString(" | ").take(500)
        val old = Prefs.getString(c, "screen_log").split("\n----\n").filter { it.isNotBlank() }
        Prefs.putString(c, "screen_log", (old + entry).takeLast(12).joinToString("\n----\n"))
    }
}
