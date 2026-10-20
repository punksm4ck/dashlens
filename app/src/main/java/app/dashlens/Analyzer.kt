package app.dashlens

import android.content.Context
import java.util.Locale

enum class Verdict(val title: String) {
    TAKE("✓  TAKE"), MAYBE("~  MAYBE"), DECLINE("✕  DECLINE")
}

data class Result(
    val netHr: Double,
    val netPay: Double,
    val realMiles: Double,
    val emptyMiles: Double,
    val tripMin: Double,          // minutes on the order itself (drive + wait + handoff)
    val totalMin: Double,         // plus the empty drive back
    val waitMin: Double,
    val perMile: Double,          // $ per real mile
    val perOfferMile: Double,     // $ per mile as listed
    val verdict: Verdict,
    val source: String,           // where the time estimate came from
    val merchantLine: String,     // history at this merchant, or empty
    val reasons: List<String>
) {
    val take: Boolean get() = verdict == Verdict.TAKE
}

/** Learns minutes-per-mile from orders you actually completed (GPS stretch linked to the accepted offer). */
object Calibration {
    data class Cal(val minPerMi: Double, val n: Int) { val mph: Double get() = 60.0 / minPerMi }

    private var cached: Cal? = null
    private var cachedAt = 0L

    fun get(c: Context): Cal? {
        if (!Prefs.getBool(c, "auto_calibrate", true)) return null
        val now = System.currentTimeMillis()
        if (now - cachedAt < 60_000L) return cached
        cachedAt = now
        val s = Db.get(c).calibrationSamples(40)
        cached = if (s.size < 5) null else {
            val fixed = Prefs.get(c, "wait_min") + Prefs.get(c, "dropoff_min")
            val r = s.map { (mi, min) -> ((min - fixed).coerceAtLeast(0.5)) / mi }.sorted()
            Cal(r[r.size / 2].coerceIn(1.0, 6.0), s.size)
        }
        return cached
    }

    fun reset() { cachedAt = 0L }
}

object Analyzer {
    private fun f(fmt: String, vararg a: Any) = String.format(Locale.US, fmt, *a)

    fun analyze(c: Context, o: Offer): Result {
        fun p(k: String) = Prefs.get(c, k)

        val actual = if (Prefs.getBool(c, "use_actual_cost", false)) Finance.actualCostPerMile(c) else null
        val costMi = actual ?: p("cost_mi")

        val empty = o.miles * p("empty_pct") / 100.0
        val real = o.miles + empty
        val backMin = if (p("speed_mph") > 0) empty / p("speed_mph") * 60.0 else 0.0
        val extra = p("extra_pickup_min") * (o.pickups - 1)
        val wait = p("wait_min") * o.pickups

        // --- how long will the order take?
        val cal = Calibration.get(c)
        val driveMin: Double
        var source: String
        if (o.shownMinutes > 0) { driveMin = o.shownMinutes; source = "time shown by Dasher" }
        else if (cal != null) { driveMin = o.miles * cal.minPerMi; source = "your real pace (" + cal.n + " orders)" }
        else { driveMin = o.miles / p("offer_speed_mph").coerceAtLeast(5.0) * 60.0; source = "estimated at " + Prefs.fmt(p("offer_speed_mph")) + " mph" }
        var orderMin = driveMin + wait + extra + p("dropoff_min")

        // --- merchant history overrides the model once you have two completed orders there
        var merchantLine = ""
        val m = if (Prefs.getBool(c, "auto_calibrate", true)) Db.get(c).merchant(o.merchant) else null
        if (m != null) {
            if (m.done >= 2 && m.avgMin > 0.0) {
                val ratio = if (m.avgMi > 0.0) (o.miles / m.avgMi).coerceIn(0.7, 1.5) else 1.0
                orderMin = m.avgMin * ratio + extra
                source = "your history at " + o.merchant
                merchantLine = o.merchant + ": " + m.done + " completed orders, avg " + f("%.0f", m.avgMin) + " min, about " +
                    f("$%.0f", m.hourly(costMi)) + "/hr realised"
            } else if (m.seen > 1) {
                merchantLine = o.merchant + ": seen " + m.seen + " times, accepted " + m.accepted
            }
        }
        val total = orderMin + backMin

        val netPay = o.pay - costMi * real
        val netHr = netPay / (total / 60.0)
        val perMile = o.pay / real
        val perOfferMile = o.pay / o.miles

        val okHr = netHr >= p("min_hr")
        val okMi = perMile >= p("min_mi")
        val near = netHr >= p("min_hr") * 0.85 && perMile >= p("min_mi") * 0.85

        val hard = ArrayList<String>()
        if (p("min_pay") > 0 && o.pay < p("min_pay"))
            hard += f("Pay \$%.2f is under your \$%.2f minimum offer pay.", o.pay, p("min_pay"))
        if (p("max_mi") > 0 && o.miles > p("max_mi"))
            hard += f("%.1f mi is over your %.1f mi maximum.", o.miles, p("max_mi"))
        if (p("max_stops") > 0 && o.pickups > p("max_stops").toInt())
            hard += f("%d pickups is over your maximum of %d.", o.pickups, p("max_stops").toInt())

        val verdict = when {
            hard.isNotEmpty() -> Verdict.DECLINE
            okHr && okMi -> Verdict.TAKE
            near -> Verdict.MAYBE
            else -> Verdict.DECLINE
        }

        val reasons = ArrayList<String>()
        reasons += hard
        reasons += f("About \$%.0f/hr once the whole cycle is counted — %s your \$%.0f minimum.", netHr, if (okHr) "above" else "under", p("min_hr"))
        reasons += f("\$%.2f per real mile (%.1f incl. ~%.1f empty) — %s your \$%.2f minimum.",
            perMile, real, empty, if (okMi) "above" else "under", p("min_mi"))
        reasons += f("Order time ~%.0f min, based on %s.", orderMin, source)
        if (o.windowMin > 0 && orderMin > o.windowMin * 1.25)
            reasons += f("Deliver-by window is only %.0f min; your estimate is longer.", o.windowMin)
        if (actual != null) reasons += f("Using your actual gas + maintenance cost: \$%.2f per mile.", actual)
        if (!o.tipsIncluded) reasons += "Dasher did not say tips are included: pay may be higher."

        return Result(netHr, netPay, real, empty, orderMin, total, wait, perMile, perOfferMile, verdict, source, merchantLine, reasons)
    }
}
