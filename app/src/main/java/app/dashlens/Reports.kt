package app.dashlens

import android.content.Context
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class Period { WEEK, MONTH, YEAR }

data class Report(
    val period: Period,
    val from: LocalDate,
    val toExcl: LocalDate,
    val title: String,
    val totals: Totals,
    val rows: List<DayRow>
)

object Reports {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val md = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    fun dayStart(d: LocalDate): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()
    fun mi(meters: Double): String = String.format(Locale.US, "%.1f", meters / 1609.344)
    fun hrs(ms: Long): String = String.format(Locale.US, "%.2f", ms / 3_600_000.0)
    fun dur(ms: Long): String {
        val m = ms / 60_000
        return String.format(Locale.US, "%dh %02dm", m / 60, m % 60)
    }

    fun totals(c: Context, fromMs: Long, toMs: Long): Totals = Db.get(c).totals(fromMs, toMs)

    fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun shift(p: Period, anchor: LocalDate, n: Long): LocalDate = when (p) {
        Period.WEEK -> anchor.plusWeeks(n)
        Period.MONTH -> anchor.plusMonths(n)
        Period.YEAR -> anchor.plusYears(n)
    }

    fun build(c: Context, p: Period, anchor: LocalDate): Report {
        val from: LocalDate
        val to: LocalDate
        when (p) {
            Period.WEEK -> { from = weekStart(anchor); to = from.plusWeeks(1) }
            Period.MONTH -> { from = anchor.withDayOfMonth(1); to = from.plusMonths(1) }
            Period.YEAR -> { from = anchor.withDayOfYear(1); to = from.plusYears(1) }
        }
        val title = when (p) {
            Period.WEEK -> "Week " + from.format(md) + " - " + to.minusDays(1).format(md) + ", " + to.minusDays(1).year
            Period.MONTH -> from.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US))
            Period.YEAR -> "Year " + from.year
        }
        val db = Db.get(c)
        val f = dayStart(from); val t = dayStart(to)
        val found = db.grouped(f, t, if (p == Period.YEAR) "%Y-%m" else "%Y-%m-%d").associateBy { it.key }

        val keys = ArrayList<String>()
        when (p) {
            Period.WEEK -> for (i in 0 until 7) keys.add(from.plusDays(i.toLong()).toString())
            Period.YEAR -> for (m in 1..12) keys.add(String.format(Locale.US, "%d-%02d", from.year, m))
            Period.MONTH -> keys.addAll(found.keys.sorted())
        }
        val rows = keys.map { found[it] ?: DayRow(it, 0.0, 0.0, 0L, 0L) }
        return Report(p, from, to, title, db.totals(f, t), rows)
    }

    private fun money(v: Double) = String.format(Locale.US, "$%,.2f", v)

    fun text(c: Context, r: Report): String {
        val t = r.totals
        val rate = Prefs.get(c, "irs_rate")
        val sb = StringBuilder()
        sb.append("DashLens - ").append(r.title).append("\n\n")
        sb.append("On order:          ").append(mi(t.orderM)).append(" mi   ").append(dur(t.orderMs)).append("\n")
        sb.append("Online, no order:  ").append(mi(t.idleM)).append(" mi   ").append(dur(t.idleMs)).append("\n")
        sb.append("Online total:      ").append(mi(t.onlineM)).append(" mi   ").append(dur(t.onlineMs))
            .append("  (includes on-order)\n\n")
        sb.append("Estimated deduction at ").append(String.format(Locale.US, "%.3f", rate)).append(" per mile:\n")
        sb.append("  on-order miles only:  ").append(money(t.orderM / 1609.344 * rate)).append("\n")
        sb.append("  all online miles:     ").append(money(t.onlineM / 1609.344 * rate)).append("\n")
        sb.append("(Estimate only. Confirm which miles you can claim with a tax professional.)\n\n")
        sb.append(Finance.section(c, r.from, r.toExcl)).append("\n")
        sb.append("By ").append(if (r.period == Period.YEAR) "month" else "day").append(" (on-order mi / h, online total mi / h):\n")
        for (d in r.rows) {
            sb.append(String.format(Locale.US, "%-8s %7s %7s %7s %7s\n", d.key.removePrefix(r.from.year.toString() + "-"),
                mi(d.orderM), hrs(d.orderMs), mi(d.orderM + d.idleM), hrs(d.orderMs + d.idleMs)))
        }
        return sb.toString()
    }

    private fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    fun csv(r: Report): String {
        val sb = StringBuilder()
        sb.append("Report,").append(q(r.title)).append("\n")
        sb.append("Period,OnOrder_Miles,OnOrder_Hours,OnlineNoOrder_Miles,OnlineNoOrder_Hours,OnlineTotal_Miles,OnlineTotal_Hours\n")
        for (d in r.rows) {
            sb.append(d.key).append(',').append(mi(d.orderM)).append(',').append(hrs(d.orderMs)).append(',')
                .append(mi(d.idleM)).append(',').append(hrs(d.idleMs)).append(',')
                .append(mi(d.orderM + d.idleM)).append(',').append(hrs(d.orderMs + d.idleMs)).append("\n")
        }
        val t = r.totals
        sb.append("TOTAL,").append(mi(t.orderM)).append(',').append(hrs(t.orderMs)).append(',')
            .append(mi(t.idleM)).append(',').append(hrs(t.idleMs)).append(',')
            .append(mi(t.onlineM)).append(',').append(hrs(t.onlineMs)).append("\n")
        return sb.toString()
    }

    /** Continuous runs (one row per stretch in the same state), the form you want for a mileage log. */
    fun tripLog(c: Context, r: Report): String {
        val segs = Db.get(c).segments(dayStart(r.from), dayStart(r.toExcl))
        val runs = ArrayList<Seg>()
        for (s in segs) {
            val l = runs.lastOrNull()
            if (l != null && l.state == s.state && s.startMs - l.endMs <= 2000L)
                runs[runs.size - 1] = Seg(l.startMs, s.endMs, l.state, l.meters + s.meters)
            else runs.add(s)
        }
        val dateF = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
        val timeF = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        val sb = StringBuilder("Date,Start,End,Type,Miles,Minutes\n")
        for (s in runs) {
            val a = Instant.ofEpochMilli(s.startMs).atZone(zone)
            val b = Instant.ofEpochMilli(s.endMs).atZone(zone)
            sb.append(a.format(dateF)).append(',').append(a.format(timeF)).append(',').append(b.format(timeF)).append(',')
                .append(if (s.state == "ORDER") "On order" else "Online (no order)").append(',')
                .append(mi(s.meters)).append(',')
                .append(String.format(Locale.US, "%.0f", (s.endMs - s.startMs) / 60_000.0)).append("\n")
        }
        return sb.toString()
    }

    fun offersCsv(c: Context, r: Report): String {
        val rows = Db.get(c).offers(dayStart(r.from), dayStart(r.toExcl)).reversed()
        val dt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder("DateTime,Verdict,Outcome,Pay,Miles,Minutes,Pickups,NetPerHour,PerRealMile,Lat,Lon\n")
        for (o in rows) {
            sb.append(Instant.ofEpochMilli(o.ts).atZone(zone).format(dt)).append(',').append(o.verdict).append(',')
                .append(o.outcome).append(',').append(String.format(Locale.US, "%.2f,%.1f,%.0f,%d,%.2f,%.2f", o.pay, o.miles, o.minutes, o.pickups, o.netHr, o.perMile))
                .append(',').append(if (o.hasLoc) String.format(Locale.US, "%.5f,%.5f", o.lat, o.lon) else ",").append("\n")
        }
        return sb.toString()
    }

    /** Earnings and expenses in one file: Type,Date,Amount,Category,Platform,Note,Receipt. */
    fun ledgerCsv(c: Context, r: Report): String {
        val db = Db.get(c)
        val f = dayStart(r.from); val t = dayStart(r.toExcl)
        val dt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
        val sb = StringBuilder("Type,Date,Amount,Category,Platform,Note,Receipt\n")
        for (e in db.earnings(f, t).reversed())
            sb.append("Earning,").append(Instant.ofEpochMilli(e.ts).atZone(zone).format(dt)).append(',')
                .append(String.format(Locale.US, "%.2f", e.amount)).append(',').append(q(e.kind)).append(',')
                .append(q(e.platform)).append(',').append(q(e.note)).append(",\n")
        for (e in db.expenses(f, t).reversed())
            sb.append("Expense,").append(Instant.ofEpochMilli(e.ts).atZone(zone).format(dt)).append(',')
                .append(String.format(Locale.US, "%.2f", e.amount)).append(',').append(q(e.category)).append(",,")
                .append(q(e.note)).append(',').append(q(e.receipt)).append("\n")
        return sb.toString()
    }
}
