package app.dashlens

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

data class SlotStat(val key: Int, val minutes: Double, val pay: Double) {
    val perHr: Double get() = if (minutes > 0.0) pay / (minutes / 60.0) else 0.0
}

data class Hot(val lat: Double, val lon: Double, val count: Int, val avgPay: Double)

data class OfferStats(
    val total: Int, val accepted: Int, val passed: Int,
    val avgPay: Double, val avgPerMile: Double, val avgNetHr: Double,
    val passedGood: Int, val acceptedBad: Int
)

/** Personal analytics from your own logs. Nothing leaves the phone. */
object Insights {
    val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun offerStats(rows: List<OfferRow>): OfferStats {
        val acc = rows.filter { it.outcome == "ACCEPTED" }
        val passed = rows.count { it.outcome == "PASSED" }
        fun avg(l: List<Double>) = if (l.isEmpty()) 0.0 else l.sum() / l.size
        return OfferStats(
            rows.size, acc.size, passed,
            avg(acc.map { it.pay }), avg(acc.map { it.perMile }), avg(acc.map { it.netHr }),
            rows.count { it.outcome == "PASSED" && it.verdict == "TAKE" },
            acc.count { it.verdict == "DECLINE" }
        )
    }

    /** Online minutes and estimated income per (weekday * 24 + hour) slot over the last [days] days. */
    fun slots(c: Context, days: Int): Map<Int, SlotStat> {
        val now = System.currentTimeMillis()
        val from = now - days * 86_400_000L
        val db = Db.get(c)
        val mins = DoubleArray(7 * 24)
        val pay = DoubleArray(7 * 24)
        for (s in db.segments(from, now)) {
            var cur = s.startMs
            while (cur < s.endMs) {
                val z = Instant.ofEpochMilli(cur).atZone(zone)
                val nextHour = z.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
                val end = minOf(s.endMs, nextHour)
                mins[(z.dayOfWeek.value - 1) * 24 + z.hour] += (end - cur) / 60_000.0
                cur = end
            }
        }
        fun slotOf(ts: Long): Int { val z = Instant.ofEpochMilli(ts).atZone(zone); return (z.dayOfWeek.value - 1) * 24 + z.hour }
        if (!Prefs.getBool(c, "income_manual_only", false))
            for (o in db.offers(from, now)) if (o.outcome == "ACCEPTED") pay[slotOf(o.ts)] += o.pay
        for (e in db.earnings(from, now)) pay[slotOf(e.ts)] += e.amount
        val out = LinkedHashMap<Int, SlotStat>()
        for (i in 0 until 7 * 24) if (mins[i] > 0.0) out[i] = SlotStat(i, mins[i], pay[i])
        return out
    }

    fun topSlots(s: Map<Int, SlotStat>, minMinutes: Double, n: Int): List<SlotStat> =
        s.values.filter { it.minutes >= minMinutes }.sortedByDescending { it.perHr }.take(n)

    fun byWeekday(s: Map<Int, SlotStat>): List<SlotStat> =
        (0 until 7).map { d ->
            val l = s.values.filter { it.key / 24 == d }
            SlotStat(d, l.sumOf { it.minutes }, l.sumOf { it.pay })
        }

    fun slotLabel(key: Int): String {
        val h = key % 24
        val h2 = (h + 1) % 24
        return DAYS[key / 24] + String.format(Locale.US, " %02d:00-%02d:00", h, h2)
    }

    /** Where you accepted offers, in ~1 km cells (0.01 degrees), busiest first. */
    fun hotspots(rows: List<OfferRow>, n: Int): List<Hot> {
        val m = HashMap<String, ArrayList<OfferRow>>()
        for (o in rows) if (o.outcome == "ACCEPTED" && o.hasLoc) {
            val k = Math.round(o.lat * 100.0).toString() + "," + Math.round(o.lon * 100.0).toString()
            m.getOrPut(k) { ArrayList() }.add(o)
        }
        return m.values.map { l ->
            Hot(l.sumOf { it.lat } / l.size, l.sumOf { it.lon } / l.size, l.size, l.sumOf { it.pay } / l.size)
        }.sortedWith(compareByDescending<Hot> { it.count }.thenByDescending { it.avgPay }).take(n)
    }
}
