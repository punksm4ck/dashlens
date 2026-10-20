package app.dashlens

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

data class Pnl(
    val offerIncome: Double, val offerCount: Int, val manualIncome: Double, val income: Double,
    val expenses: Map<String, Double>, val expenseTotal: Double,
    val miles: Double, val mileageDeduction: Double, val expenseDeduction: Double,
    val deductions: Double, val net: Double, val actualMethod: Boolean, val bizPct: Double
)

data class Due(val label: String, val date: LocalDate, val from: LocalDate, val toExcl: LocalDate)

data class Today(val income: Double, val accepted: Int, val onlineMs: Long, val miles: Double) {
    val perHour: Double get() = if (onlineMs > 0L) income / (onlineMs / 3_600_000.0) else 0.0
}

/** Profit, deduction and tax-set-aside estimates. Everything here is an estimate, not tax advice. */
object Finance {
    val CATS = listOf("Gas", "Tolls", "Parking", "Car wash", "Maintenance", "Insurance", "Phone", "Supplies", "Other")
    val KINDS = listOf("Dash payout", "Tips / cash", "Bonus / promo", "Other income")

    /** Costs the standard mileage rate already covers (so they are not deducted again under that method). */
    private val VEHICLE = setOf("Gas", "Car wash", "Maintenance", "Insurance")

    /** Variable cost used for the "actual cost per mile" offer analysis. */
    private val VARIABLE = setOf("Gas", "Maintenance")

    fun money(v: Double): String =
        (if (v < -0.005) "-" else "") + String.format(Locale.US, "$%,.2f", Math.abs(v))

    private fun manualOnly(c: Context) = Prefs.getBool(c, "income_manual_only", false)

    fun bizPct(c: Context, year: Int): Double {
        val s = Prefs.getString(c, "odo_start_$year").toDoubleOrNull()
        val e = Prefs.getString(c, "odo_end_$year").toDoubleOrNull()
        if (s != null && e != null && e > s) {
            val t = Db.get(c).totals(Reports.dayStart(LocalDate.of(year, 1, 1)), Reports.dayStart(LocalDate.of(year + 1, 1, 1)))
            val tracked = (if (Prefs.getBool(c, "basis_online", false)) t.onlineM else t.orderM) / 1609.344
            return (tracked / (e - s) * 100.0).coerceIn(0.0, 100.0)
        }
        return Prefs.get(c, "biz_pct").coerceIn(0.0, 100.0)
    }

    fun pnl(c: Context, from: LocalDate, toExcl: LocalDate): Pnl {
        val db = Db.get(c)
        val f = Reports.dayStart(from)
        val t = Reports.dayStart(toExcl)
        val acc = db.acceptedPay(f, t)
        val manual = db.sumEarnings(f, t)
        val income = if (manualOnly(c)) manual else acc.first + manual
        val tot = db.totals(f, t)
        val miles = (if (Prefs.getBool(c, "basis_online", false)) tot.onlineM else tot.orderM) / 1609.344
        val exp = db.expenseByCat(f, t)
        val expTotal = exp.values.sum()
        val actual = Prefs.getBool(c, "method_actual", false)
        val pct = bizPct(c, from.year)
        val vehicleExp = exp.filterKeys { it in VEHICLE }.values.sum()
        val otherExp = expTotal - vehicleExp
        val mileDed = if (actual) 0.0 else miles * Prefs.get(c, "irs_rate")
        val expDed = otherExp + (if (actual) vehicleExp * pct / 100.0 else 0.0)
        val ded = mileDed + expDed
        return Pnl(acc.first, acc.second, manual, income, exp, expTotal, miles, mileDed, expDed, ded, income - ded, actual, pct)
    }

    /** Self-employment tax: 15.3% on 92.35% of net profit, only when net is at least $400. */
    fun seTax(net: Double): Double = if (net < 400.0) 0.0 else net * 0.9235 * 0.153

    fun setAside(c: Context, net: Double): Double {
        if (net <= 0.0) return 0.0
        val se = seTax(net)
        return se + (net - se / 2.0).coerceAtLeast(0.0) * Prefs.get(c, "tax_rate") / 100.0
    }

    // ------------------------------------------------------------------ quarterly estimated-tax dates
    private fun adj(d: LocalDate): LocalDate = when (d.dayOfWeek) {
        DayOfWeek.SATURDAY -> d.plusDays(2)
        DayOfWeek.SUNDAY -> d.plusDays(1)
        else -> d
    }

    fun dues(year: Int): List<Due> = listOf(
        Due("Q1", adj(LocalDate.of(year, 4, 15)), LocalDate.of(year, 1, 1), LocalDate.of(year, 4, 1)),
        Due("Q2", adj(LocalDate.of(year, 6, 15)), LocalDate.of(year, 4, 1), LocalDate.of(year, 6, 1)),
        Due("Q3", adj(LocalDate.of(year, 9, 15)), LocalDate.of(year, 6, 1), LocalDate.of(year, 9, 1)),
        Due("Q4", adj(LocalDate.of(year + 1, 1, 15)), LocalDate.of(year, 9, 1), LocalDate.of(year + 1, 1, 1))
    )

    fun nextDue(today: LocalDate): Due? {
        for (y in listOf(today.year - 1, today.year, today.year + 1))
            for (d in dues(y)) if (!d.date.isBefore(today)) return d
        return null
    }

    private val dfmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    /** One line when an estimated payment is due within 21 days, otherwise empty. */
    fun taxHint(c: Context): String {
        val d = nextDue(LocalDate.now()) ?: return ""
        val days = ChronoUnit.DAYS.between(LocalDate.now(), d.date)
        if (days > 21) return ""
        val p = pnl(c, d.from, d.toExcl)
        return "Estimated tax payment for " + d.label + " is due " + d.date.format(dfmt) + " (" + days +
            " days). Rough set-aside for that period: " + money(setAside(c, p.net)) + "."
    }

    fun quartersText(c: Context, year: Int): String {
        val sb = StringBuilder()
        for (d in dues(year)) {
            val p = pnl(c, d.from, d.toExcl)
            sb.append(String.format(Locale.US, "%s  due %-13s  net %10s  set-aside %9s\n",
                d.label, d.date.format(dfmt), money(p.net), money(setAside(c, p.net))))
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ text block used by reports
    fun section(c: Context, from: LocalDate, toExcl: LocalDate): String {
        val p = pnl(c, from, toExcl)
        val sb = StringBuilder()
        sb.append("Money (estimates)\n")
        sb.append("  Income:            ").append(money(p.income))
        if (manualOnly(c)) sb.append("  (logged by hand only)\n")
        else sb.append("  (").append(money(p.offerIncome)).append(" from ").append(p.offerCount)
            .append(" accepted offers + ").append(money(p.manualIncome)).append(" logged)\n")
        if (p.actualMethod) {
            sb.append("  Vehicle + other expenses: ").append(money(p.expenseDeduction))
                .append("  (actual method, ").append(String.format(Locale.US, "%.0f", p.bizPct)).append("% business use)\n")
        } else {
            sb.append("  Mileage deduction: ").append(money(p.mileageDeduction))
                .append("  (").append(String.format(Locale.US, "%.1f", p.miles)).append(" mi at ")
                .append(Prefs.fmt(Prefs.get(c, "irs_rate"))).append(")\n")
            sb.append("  Other expenses:    ").append(money(p.expenseDeduction)).append("  (tolls, parking, phone, supplies...)\n")
        }
        sb.append("  Net profit:        ").append(money(p.net)).append("\n")
        sb.append("  Tax set-aside:     ").append(money(setAside(c, p.net))).append("  (self-employment tax + your income-tax %, rough)\n")
        if (p.expenses.isNotEmpty()) {
            sb.append("  Logged expenses by category:\n")
            for ((k, v) in p.expenses) sb.append("    ").append(String.format(Locale.US, "%-12s %s\n", k, money(v)))
        }
        val hint = taxHint(c)
        if (hint.isNotEmpty()) sb.append("  ").append(hint).append("\n")
        sb.append("(Estimates only. Not tax advice. Confirm with a tax professional.)\n")
        return sb.toString()
    }

    // ------------------------------------------------------------------ live numbers (notification, overlay footer)
    fun today(c: Context): Today {
        val db = Db.get(c)
        val s = Reports.dayStart(LocalDate.now())
        val e = Reports.dayStart(LocalDate.now().plusDays(1))
        val acc = db.acceptedPay(s, e)
        val income = if (manualOnly(c)) db.sumEarnings(s, e) else acc.first + db.sumEarnings(s, e)
        val t = db.totals(s, e)
        return Today(income, acc.second, t.onlineMs, t.onlineM / 1609.344)
    }

    fun incomeBetween(c: Context, from: LocalDate, toExcl: LocalDate): Double {
        val db = Db.get(c)
        val f = Reports.dayStart(from); val t = Reports.dayStart(toExcl)
        return if (manualOnly(c)) db.sumEarnings(f, t) else db.acceptedPay(f, t).first + db.sumEarnings(f, t)
    }

    fun goalLine(c: Context, t: Today): String {
        val g = Prefs.get(c, "goal_day")
        if (g <= 0.0) return ""
        return String.format(Locale.US, "Goal: %s of %s (%.0f%%)", money(t.income), money(g), t.income / g * 100.0)
    }

    /** Gas + maintenance dollars per tracked mile over the last 90 days, or null when there is too little data. */
    fun actualCostPerMile(c: Context): Double? {
        val now = System.currentTimeMillis()
        val from = now - 90L * 86_400_000L
        val db = Db.get(c)
        val miles = db.totals(from, now).onlineM / 1609.344
        val cost = db.expenseByCat(from, now).filterKeys { it in VARIABLE }.values.sum()
        return if (miles >= 100.0 && cost >= 20.0) cost / miles else null
    }
}
