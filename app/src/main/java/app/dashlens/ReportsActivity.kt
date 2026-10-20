package app.dashlens

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate

class ReportsActivity : BaseActivity() {
    override val tab = -1

    private var period = Period.WEEK
    private var anchor: LocalDate = LocalDate.now()






    private fun button(label: String, weight: Float = 0f, onClick: () -> Unit): Button =
        Ui.button(this, label, 0, onClick).also { if (weight > 0f) Ui.weighted(it, weight) }

    private fun text(s: String, size: Float, mono: Boolean = false, bold: Boolean = false): TextView =
        Ui.text(this, s, size, if (size <= 12f && !mono) Ui.MUTED else Ui.TEXT, bold, mono)

    private fun decimal(hint: String): EditText = Ui.input(this, hint)

    override fun build(root: LinearLayout) {
        Ui.add(root, Ui.title(this, "Reports"), 8)
        val r = Reports.build(this, period, anchor)
        val t = r.totals

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (p in Period.values()) {
            tabs.addView(Ui.weighted(Ui.chip(this, p.name.lowercase().replaceFirstChar { it.uppercase() }, p == period) { period = p; anchor = LocalDate.now(); render() }))
        }
        root.addView(tabs)

        root.addView(Ui.navRow(this, r.title, { anchor = Reports.shift(period, anchor, -1); render() }, { anchor = Reports.shift(period, anchor, 1); render() }))

        root.addView(text("\nOn order", 13f))
        root.addView(text(Reports.mi(t.orderM) + " mi   " + Reports.dur(t.orderMs), 22f, bold = true))
        root.addView(text("\nOnline, between orders", 13f))
        root.addView(text(Reports.mi(t.idleM) + " mi   " + Reports.dur(t.idleMs), 22f, bold = true))
        root.addView(text("\nOnline total (includes on-order)", 13f))
        root.addView(text(Reports.mi(t.onlineM) + " mi   " + Reports.dur(t.onlineMs), 22f, bold = true))

        val rate = Prefs.get(this, "irs_rate")
        root.addView(text(
            "\nEst. deduction at \$" + Prefs.fmt(rate) + "/mi:  on-order " +
                String.format(java.util.Locale.US, "$%.2f", t.orderM / 1609.344 * rate) + "  |  all online " +
                String.format(java.util.Locale.US, "$%.2f", t.onlineM / 1609.344 * rate) +
                "\nEstimate only. Ask a tax professional which miles you can claim.", 12f
        ))

 MoneyCard.add(this, root, r.from, r.toExcl)
        root.addView(button("Earnings, expenses and tax") { startActivity(android.content.Intent(this, MoneyActivity::class.java)) })

        root.addView(text("\n" + (if (period == Period.YEAR) "Month" else "Day") + "      order mi/h    online mi/h", 12f, mono = true, bold = true))
        for (d in r.rows) {
            val label = d.key.removePrefix(r.from.year.toString() + "-").padEnd(8)
            root.addView(text(
                label + String.format(java.util.Locale.US, "%6s %5s   %6s %5s", Reports.mi(d.orderM), Reports.hrs(d.orderMs),
                    Reports.mi(d.orderM + d.idleM), Reports.hrs(d.orderMs + d.idleMs)),
                12f, mono = true
            ))
        }

        root.addView(text("", 8f))
        val base = "DashLens_" + period.name.lowercase() + "_" + r.from.toString()
        root.addView(button("Save CSV + trip log + text to Downloads/DashLens") {
            val where = Exporter.saveReport(this, r, base)
            Toast.makeText(this, if (where != null) "Saved: " + where else "Could not save", Toast.LENGTH_LONG).show()
        })
        root.addView(button("Share summary text") { Exporter.share(this, r.title, Reports.text(this, r)) })
        val hint = text("Miles come from GPS and can differ slightly from your odometer. Keep your Jan 1 and Dec 31 odometer readings for your return.", 11f)
        hint.visibility = View.VISIBLE
        root.addView(hint)
    }
}
