package app.dashlens

import android.content.Intent
import android.net.Uri
import android.widget.LinearLayout
import java.time.LocalDate
import java.util.Locale

/** Your own numbers: offer quality, learned pace, best hours, best weekdays, hotspots, goals. */
class InsightsActivity : BaseActivity() {
    override val tab = 3
    private var days = 30

    override fun build(root: LinearLayout) {
        Ui.add(root, Ui.title(this, "Insights"), 12)
        val now = System.currentTimeMillis()
        val rows = Db.get(this).offers(now - days * 86_400_000L, now)
        val st = Insights.offerStats(rows)
        val f = { fmt: String, v: Double -> String.format(Locale.US, fmt, v) }

        val tabs = Ui.hrow(this)
        for (d in listOf(30, 90, 365)) tabs.addView(Ui.weighted(Ui.chip(this, "$d days", d == days) { days = d; render() }))
        Ui.add(root, tabs, 12)

        // ---- offers
        val oc = Ui.card(this)
        oc.addView(Ui.text(this, "Offers", 16f, Ui.TEXT, true))
        if (st.total == 0) {
            oc.addView(Ui.caption(this, "Nothing logged yet. Offers appear here once the overlay has analysed some.").apply { setPadding(0, dp(6), 0, 0) })
        } else {
            val r = Ui.hrow(this).apply { setPadding(0, dp(10), 0, dp(6)) }
            r.addView(Ui.stat(this, "Seen", st.total.toString()))
            r.addView(Ui.stat(this, "Accepted", st.accepted.toString(), Ui.GREEN))
            r.addView(Ui.stat(this, "Accept rate", f("%.0f%%", st.accepted * 100.0 / st.total)))
            oc.addView(r)
            oc.addView(Ui.text(this, "Accepted on average: " + Finance.money(st.avgPay) + ", " + f("$%.2f", st.avgPerMile) + " per real mile, about " + f("$%.0f", st.avgNetHr) + "/hr net.", 13f))
            oc.addView(Ui.caption(this, st.passedGood.toString() + " offers rated TAKE were passed · " + st.acceptedBad + " rated DECLINE were accepted.").apply { setPadding(0, dp(6), 0, 0) })
        }
        Ui.add(root, oc)

        // ---- learned pace
        val cal = Calibration.get(this)
        val cc = Ui.card(this)
        cc.addView(Ui.text(this, "Your real pace", 16f, Ui.TEXT, true))
        cc.addView(Ui.text(this,
            if (cal != null) "DashLens has learned that you drive about " + f("%.0f", cal.mph) + " mph on orders (" + f("%.1f", cal.minPerMi) + " min per mile), from " + cal.n +
                " completed orders. Offer times now use your pace instead of a guess."
            else "Dasher does not show trip time, so DashLens estimates it. Complete 5 orders with a shift running and it learns your real pace automatically.",
            13f, Ui.MUTED).apply { setPadding(0, dp(6), 0, 0) })
        Ui.add(root, cc)

        // ---- weekdays and slots
        val slots = Insights.slots(this, days)
        val wc = Ui.card(this)
        wc.addView(Ui.text(this, "Best weekdays", 16f, Ui.TEXT, true).apply { setPadding(0, 0, 0, dp(6)) })
        val wd = Insights.byWeekday(slots)
        if (wd.all { it.minutes < 30.0 }) wc.addView(Ui.caption(this, "Needs some logged shifts first."))
        else {
            val best = wd.filter { it.minutes >= 30.0 }.maxByOrNull { it.perHr }
            for (w in wd) wc.addView(Ui.text(this,
                String.format(Locale.US, "%s   %4.1f h   %9s   %s", Insights.DAYS[w.key], w.minutes / 60.0, Finance.money(w.pay), if (w.minutes >= 30.0) f("$%.0f/hr", w.perHr) else "-"),
                12f, if (w === best) Ui.GREEN else Ui.TEXT, w === best, true))
        }
        Ui.add(root, wc)

        val tc = Ui.card(this)
        tc.addView(Ui.text(this, "Best time slots", 16f, Ui.TEXT, true).apply { setPadding(0, 0, 0, dp(6)) })
        val top = Insights.topSlots(slots, 60.0, 6)
        if (top.isEmpty()) tc.addView(Ui.caption(this, "Needs more shifts: a slot needs an hour online across the period."))
        else for (s in top) tc.addView(Ui.text(this, Insights.slotLabel(s.key) + "   " + f("$%.0f/hr", s.perHr) + "   (" + f("%.1f", s.minutes / 60.0) + " h)", 12f, Ui.TEXT, mono = true))
        tc.addView(Ui.caption(this, "Income is the accepted-offer estimate plus what you log, divided by online time.").apply { setPadding(0, dp(6), 0, 0) })
        Ui.add(root, tc)

        // ---- hotspots
        val hc = Ui.card(this)
        hc.addView(Ui.text(this, "Your hotspots", 16f, Ui.TEXT, true).apply { setPadding(0, 0, 0, dp(6)) })
        val hot = Insights.hotspots(rows, 5)
        if (hot.isEmpty()) hc.addView(Ui.caption(this, "Needs accepted offers recorded while a shift is running (GPS)."))
        for (h in hot) hc.addView(Ui.button(this, h.count.toString() + " accepted · avg " + Finance.money(h.avgPay) + " · open in Maps") {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US, "geo:%.5f,%.5f?q=%.5f,%.5f", h.lat, h.lon, h.lat, h.lon)))) } catch (_: Exception) {}
        }.apply { textSize = 13f })
        Ui.add(root, hc)

        // ---- goals
        val t = Finance.today(this)
        val week = Reports.weekStart(LocalDate.now())
        val wk = Finance.incomeBetween(this, week, week.plusWeeks(1))
        val gd = Prefs.get(this, "goal_day"); val gw = Prefs.get(this, "goal_week")
        val gc = Ui.card(this)
        gc.addView(Ui.text(this, "Goals", 16f, Ui.TEXT, true).apply { setPadding(0, 0, 0, dp(6)) })
        if (gd > 0) {
            gc.addView(Ui.caption(this, "Today: " + Finance.money(t.income) + " of " + Finance.money(gd)))
            gc.addView(Ui.progress(this, t.income / gd, Ui.GREEN))
        } else gc.addView(Ui.caption(this, "Set a daily goal in Settings."))
        if (gw > 0) {
            gc.addView(Ui.caption(this, "This week: " + Finance.money(wk) + " of " + Finance.money(gw)).apply { setPadding(0, dp(10), 0, 0) })
            gc.addView(Ui.progress(this, wk / gw, Ui.BLUE))
        } else gc.addView(Ui.caption(this, "Set a weekly goal in Settings.").apply { setPadding(0, dp(10), 0, 0) })
        Ui.add(root, gc)
    }
}
