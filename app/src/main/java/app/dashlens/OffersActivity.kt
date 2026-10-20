package app.dashlens

import android.widget.LinearLayout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Every offer DashLens has seen, plus what you have learned about each merchant. */
class OffersActivity : BaseActivity() {
    override val tab = 1
    private var merchants = false

    private fun next(o: String) = when (o) { "ACCEPTED" -> "PASSED"; else -> "ACCEPTED" }

    override fun build(root: LinearLayout) {
        Ui.add(root, Ui.title(this, "Offers"), 12)
        val now = System.currentTimeMillis()
        val db = Db.get(this)

        // today summary
        val s = Reports.dayStart(LocalDate.now())
        val today = db.offers(s, now + 1000L)
        val acc = today.count { it.outcome == "ACCEPTED" }
        val sum = Ui.card(this)
        val r = Ui.hrow(this)
        r.addView(Ui.stat(this, "Seen today", today.size.toString()))
        r.addView(Ui.stat(this, "Accepted", acc.toString(), Ui.GREEN))
        r.addView(Ui.stat(this, "Passed", today.count { it.outcome == "PASSED" }.toString()))
        sum.addView(r)
        Ui.add(root, sum)

        val tabs = Ui.hrow(this)
        tabs.addView(Ui.weighted(Ui.chip(this, "History", !merchants) { merchants = false; render() }))
        tabs.addView(Ui.weighted(Ui.chip(this, "Merchants", merchants) { merchants = true; render() }))
        Ui.add(root, tabs, 12)

        if (merchants) buildMerchants(root, now) else buildHistory(root, now)
    }

    private fun buildHistory(root: LinearLayout, now: Long) {
        val rows = Db.get(this).offers(now - 365L * 86_400_000L, now + 1000L, 200)
        if (rows.isEmpty()) {
            Ui.add(root, Ui.caption(this, "Nothing logged yet. Every offer the overlay analyses shows up here."))
            return
        }
        Ui.add(root, Ui.caption(this, "Accepted or passed is DashLens's guess from the Dasher screens. Tap the tag on a row to correct it."), 10)
        val fmt = DateTimeFormatter.ofPattern("EEE MMM d, HH:mm", Locale.US)
        for (o in rows) {
            val c = Ui.card(this)
            val col = when (o.verdict) { "TAKE" -> Ui.GREEN; "MAYBE" -> Ui.AMBER; else -> Ui.RED }
            val head = Ui.hrow(this)
            head.addView(Ui.pill(this, o.verdict, col))
            head.addView(Ui.text(this, if (o.merchant.isNotEmpty()) o.merchant else "Offer", 15f, Ui.TEXT, true).apply {
                setPadding(dp(10), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val oc = when (o.outcome) { "ACCEPTED" -> Ui.GREEN; "UNKNOWN" -> Ui.AMBER; else -> Ui.MUTED }
            head.addView(Ui.pill(this, o.outcome, oc).apply {
                setOnClickListener { Db.get(this@OffersActivity).setOutcome(o.id, next(o.outcome)); render() }
            })
            c.addView(head)
            c.addView(Ui.text(this, String.format(Locale.US, "$%.2f · %.1f mi · ~$%.0f/hr net · $%.2f per real mile", o.pay, o.miles, o.netHr, o.perMile),
                13f, Ui.TEXT).apply { setPadding(0, dp(8), 0, 0) })
            val extra = StringBuilder(Instant.ofEpochMilli(o.ts).atZone(ZoneId.systemDefault()).format(fmt))
            if (o.actualMin > 0.0) extra.append(String.format(Locale.US, " · took %.0f min, %.1f mi", o.actualMin, o.actualMi))
            c.addView(Ui.caption(this, extra.toString()).apply { setPadding(0, dp(2), 0, 0) })
            Ui.add(root, c, 10)
        }
    }

    private fun buildMerchants(root: LinearLayout, now: Long) {
        val list = Db.get(this).merchants(now - 365L * 86_400_000L, now + 1000L)
        if (list.isEmpty()) {
            Ui.add(root, Ui.caption(this, "Merchant history builds up as you take offers. After two completed orders at a place, DashLens uses your real times there."))
            return
        }
        val cost = Prefs.get(this, "cost_mi")
        val minHr = Prefs.get(this, "min_hr")
        for (m in list) {
            val c = Ui.card(this)
            c.addView(Ui.text(this, m.name, 15f, Ui.TEXT, true))
            c.addView(Ui.caption(this, "Seen " + m.seen + " · accepted " + m.accepted + (if (m.accepted > 0) String.format(Locale.US, " · avg pay $%.2f", m.avgPay) else ""))
                .apply { setPadding(0, dp(2), 0, 0) })
            if (m.done > 0) {
                val hr = m.hourly(cost)
                c.addView(Ui.text(this, String.format(Locale.US, "%d completed · avg %.0f min · about $%.0f/hr realised", m.done, m.avgMin, hr), 13f,
                    if (hr >= minHr) Ui.GREEN else Ui.AMBER, true).apply { setPadding(0, dp(6), 0, 0) })
            }
            Ui.add(root, c, 10)
        }
    }
}
