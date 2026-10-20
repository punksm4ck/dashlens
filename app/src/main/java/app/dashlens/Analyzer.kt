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
    val reasons: List<String>,
    val hard: List<String> = emptyList(),      // hard-filter failures (they force DECLINE)
    val netPerOfferMile: Double = 0.0,         // net pay / miles as listed
    val vehicleCost: Double = 0.0,             // gas + wear (or flat cost) over the counted miles
    val mathLines: List<String> = emptyList(), // gas / wear / net pay / time / miles counted, line by line
    val assumed: String = "",                  // the assumptions the grade was based on
    val floorLine: String = "",                // "against your $18/hr floor, after vehicle costs"
    val payPlus: Boolean = false               // graded at a "+" minimum pay
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

    /** Vehicle cost per mile in force right now: your logged actual cost, gas + wear, or the flat rate. */
    fun costPerMile(c: Context): Double {
        val actual = if (Prefs.getBool(c, "use_actual_cost", false)) Finance.actualCostPerMile(c) else null
        return costPerMile({ Prefs.get(c, it) }, { k, d -> Prefs.getBool(c, k, d) }, actual)
    }

    fun costPerMile(p: (String) -> Double, flag: (String, Boolean) -> Boolean, actual: Double?): Double {
        if (actual != null) return actual
        if (!flag("itemize_cost", true)) return p("cost_mi")
        val gas = if (p("mpg") > 0.0) p("gas_price") / p("mpg") else 0.0
        return gas + p("wear_mi")
    }

    fun analyze(c: Context, o: Offer): Result {
        val actual = if (Prefs.getBool(c, "use_actual_cost", false)) Finance.actualCostPerMile(c) else null
        val cal = Calibration.get(c)
        val m = if (Prefs.getBool(c, "auto_calibrate", true)) Db.get(c).merchant(o.merchant) else null
        return grade(o, { Prefs.get(c, it) }, { k, d -> Prefs.getBool(c, k, d) }, actual, cal, m)
    }

    /** The whole grading model. Pure (no Android calls) so it can be unit-tested on a plain JVM. */
    fun grade(
        o: Offer,
        p: (String) -> Double,
        flag: (String, Boolean) -> Boolean,
        actual: Double?,
        cal: Calibration.Cal?,
        m: MerchantStat?
    ): Result {
        val itemize = actual == null && flag("itemize_cost", true)
        val gasPerMi = if (p("mpg") > 0.0) p("gas_price") / p("mpg") else 0.0
        val costMi = costPerMile(p, flag, actual)

        val empty = o.miles * p("empty_pct") / 100.0
        val real = o.miles + empty
        val backMin = if (p("speed_mph") > 0) empty / p("speed_mph") * 60.0 else 0.0
        val extra = p("extra_pickup_min") * (o.pickups - 1)

        // shop & deliver: shopping plus checkout replaces the wait at the first store
        val shop = if (o.items > 0) o.items * p("shop_min_item") + p("checkout_min") * o.pickups else 0.0
        val wait = if (o.items > 0) shop + p("wait_min") * (o.pickups - 1) else p("wait_min") * o.pickups

        // --- how long will the order take?
        val driveMin: Double
        var source: String
        if (o.shownMinutes > 0) { driveMin = o.shownMinutes; source = "time shown by Dasher" }
        else if (cal != null) { driveMin = o.miles * cal.minPerMi; source = "your real pace (" + cal.n + " orders)" }
        else { driveMin = o.miles / p("offer_speed_mph").coerceAtLeast(5.0) * 60.0; source = "estimated at " + Prefs.fmt(p("offer_speed_mph")) + " mph" }
        var orderMin = driveMin + wait + extra + p("dropoff_min")

        // --- merchant history overrides the model once you have two completed orders there
        var merchantLine = ""
        var usedHistory = false
        if (m != null) {
            if (m.done >= 2 && m.avgMin > 0.0) {
                val ratio = if (m.avgMi > 0.0) (o.miles / m.avgMi).coerceIn(0.7, 1.5) else 1.0
                orderMin = m.avgMin * ratio + extra
                source = "your history at " + o.merchant
                usedHistory = true
                merchantLine = o.merchant + ": " + m.done + " completed orders, avg " + f("%.0f", m.avgMin) + " min, about " +
                    f("$%.0f", m.hourly(costMi)) + "/hr realised"
            } else if (m.seen > 1) {
                merchantLine = o.merchant + ": seen " + m.seen + " times, accepted " + m.accepted
            }
        }
        val total = orderMin + backMin

        val gasCost = if (itemize) gasPerMi * real else 0.0
        val wearCost = if (itemize) p("wear_mi") * real else 0.0
        val vehicleCost = costMi * real
        val netPay = o.pay - vehicleCost
        val netHr = netPay / (total / 60.0)
        val perMile = o.pay / real
        val perOfferMile = o.pay / o.miles
        val netPerOfferMile = netPay / o.miles

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
        if (o.payPlus) reasons += "Pay is shown with a +: graded at that minimum, so the real pay may be higher."
        else if (!o.tipsIncluded) reasons += "Dasher did not say tips are included: pay may be higher."

        // ---- the line-by-line math (what the offer card and the share card print)
        val math = ArrayList<String>()
        if (itemize) {
            math += f("Gas: %.2f mi ÷ %s mpg × \$%.2f/gal = -\$%.2f", real, Prefs.fmt(p("mpg")), p("gas_price"), gasCost)
            math += f("Wear & tear: %.2f mi × \$%.2f/mi = -\$%.2f", real, p("wear_mi"), wearCost)
            math += f("Net pay = \$%.2f pay - \$%.2f gas - \$%.2f wear = \$%.2f", o.pay, gasCost, wearCost, netPay)
        } else {
            math += f("Vehicle: %.2f mi × \$%.2f/mi = -\$%.2f%s", real, costMi, vehicleCost,
                if (actual != null) " (your actual gas + maintenance)" else "")
            math += f("Net pay = \$%.2f pay - \$%.2f vehicle = \$%.2f", o.pay, vehicleCost, netPay)
        }
        if (usedHistory) {
            math += f("Time: ~%.0f min from your history at %s + %.0f min driving back = %.0f min", orderMin, o.merchant, backMin, total)
        } else {
            val parts = ArrayList<String>()
            val driveTot = driveMin + backMin
            parts += f("%.0f driving (%.2f mi × %.1f min/mi)", driveTot, real, if (real > 0.0) driveTot / real else 0.0)
            if (o.items > 0) {
                parts += f("%.0f shopping (%d items × %s + %d checkout × %s)", shop, o.items, Prefs.fmt(p("shop_min_item")),
                    o.pickups, Prefs.fmt(p("checkout_min")))
                if (o.pickups > 1) parts += f("%.0f waiting at the other stops", p("wait_min") * (o.pickups - 1))
            } else if (wait > 0.0) parts += f("%.0f waiting at the store", wait)
            if (extra > 0.0) parts += f("%.0f for the extra pickups", extra)
            parts += Prefs.fmt(p("dropoff_min")) + " for 1 drop-off"
            math += "Time: " + parts.joinToString(" + ") + f(" = %.0f min", total)
        }
        math += f("Miles counted: %.1f + %.2f back = %.2f mi", o.miles, empty, real)

        val ass = ArrayList<String>()
        if (itemize) { ass += f("gas \$%.2f/gal", p("gas_price")); ass += Prefs.fmt(p("mpg")) + " mpg"; ass += f("wear \$%.2f/mi", p("wear_mi")) }
        else ass += f("vehicle cost \$%.2f/mi", costMi)
        if (o.items > 0) { ass += Prefs.fmt(p("shop_min_item")) + " min per shop item"; ass += Prefs.fmt(p("checkout_min")) + " min checkout per store" }
        else ass += Prefs.fmt(p("wait_min")) + " min wait per pickup"
        ass += Prefs.fmt(p("dropoff_min")) + " min per drop-off"
        ass += f("%.0f%% of the miles driven back empty", p("empty_pct"))
        ass += source
        if (o.payPlus) ass += "graded at the \"+\" minimum"
        val assumed = "Assumed: " + ass.joinToString(" · ")

        val floor = "against your \$" + Prefs.fmt(p("min_hr")) + "/hr floor, after vehicle costs"

        return Result(netHr, netPay, real, empty, orderMin, total, wait, perMile, perOfferMile, verdict, source, merchantLine, reasons,
            hard, netPerOfferMile, vehicleCost, math, assumed, floor, o.payPlus)
    }
}
