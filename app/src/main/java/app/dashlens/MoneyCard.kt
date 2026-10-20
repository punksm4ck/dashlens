package app.dashlens

import android.content.Context
import android.widget.LinearLayout
import java.time.LocalDate
import java.util.Locale

/** Profit / deduction / tax set-aside as a readable card (replaces the old monospace text block). */
object MoneyCard {
    fun add(c: Context, parent: LinearLayout, from: LocalDate, toExcl: LocalDate) {
        val p = Finance.pnl(c, from, toExcl)
        val card = Ui.card(c)
        card.addView(Ui.text(c, "Profit (estimates)", 16f, Ui.TEXT, true))

        val manualOnly = Prefs.getBool(c, "income_manual_only", false)
        card.addView(Ui.kv(c, "Income", Finance.money(p.income),
            note = if (manualOnly) "logged by hand only"
            else Finance.money(p.offerIncome) + " from " + p.offerCount + " accepted offers + " + Finance.money(p.manualIncome) + " logged"))

        if (p.actualMethod) {
            card.addView(Ui.kv(c, "Vehicle + other expenses", Finance.money(p.expenseDeduction),
                note = "actual-expense method, " + String.format(Locale.US, "%.0f", p.bizPct) + "% business use"))
        } else {
            card.addView(Ui.kv(c, "Mileage deduction", Finance.money(p.mileageDeduction),
                note = String.format(Locale.US, "%.1f mi at $%s per mile", p.miles, Prefs.fmt(Prefs.get(c, "irs_rate")))))
            card.addView(Ui.kv(c, "Other expenses", Finance.money(p.expenseDeduction), note = "tolls, parking, phone, supplies"))
        }
        card.addView(Ui.kv(c, "Net profit", Finance.money(p.net), if (p.net >= 0.0) Ui.GREEN else Ui.RED))
        card.addView(Ui.kv(c, "Tax set-aside", Finance.money(Finance.setAside(c, p.net)), Ui.AMBER,
            "self-employment tax plus your income-tax %, rough"))

        if (p.expenses.isNotEmpty()) {
            card.addView(Ui.caption(c, "Logged expenses by category").apply { setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 2)) })
            for ((k, v) in p.expenses) card.addView(Ui.kv(c, k, Finance.money(v)))
        }
        val hint = Finance.taxHint(c)
        if (hint.isNotEmpty()) card.addView(Ui.text(c, hint, 12f, Ui.AMBER).apply { setPadding(0, Ui.dp(c, 8), 0, 0) })
        card.addView(Ui.caption(c, "Estimates only. Not tax advice. Confirm with a tax professional.").apply { setPadding(0, Ui.dp(c, 8), 0, 0) })
        Ui.add(parent, card)
    }
}
