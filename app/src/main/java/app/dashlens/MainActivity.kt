package app.dashlens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Home: shift controls, today at a glance, setup health, last offer. */
class MainActivity : BaseActivity() {
    override val tab = 0

    private val handler = Handler(Looper.getMainLooper())
    private var shiftPill: TextView? = null
    private var shiftLine: TextView? = null
    private var vIncome: TextView? = null
    private var vAccepted: TextView? = null
    private var vPerHr: TextView? = null
    private var vOnlineMi: TextView? = null
    private var vOrderMi: TextView? = null
    private var vTime: TextView? = null
    private var vGoal: TextView? = null
    private var startStop: Button? = null

    private val live = object : Runnable {
        override fun run() { refreshLive(); handler.postDelayed(this, 2000L) }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs.getBool(this, "onboarded", false)) startActivity(Intent(this, OnboardingActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        Summaries.schedule(this)
        Summaries.catchUp(this)
        render()
        handler.post(live)
    }

    override fun onPause() {
        handler.removeCallbacks(live)
        super.onPause()
    }

    // ------------------------------------------------------------------ health checks
    private fun accessibilityOn() =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains(packageName) == true

    private fun hasLocation() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun hasBackground() = Build.VERSION.SDK_INT < 29 ||
        checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun hasNotifications() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun batteryFree() = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun appSettings() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun askPermissions() {
        val p = ArrayList<String>()
        p.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(p.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun checkRow(parent: LinearLayout, label: String, ok: Boolean, fix: String, onFix: () -> Unit) {
        val r = Ui.hrow(this).apply { setPadding(0, dp(4), 0, dp(4)) }
        r.addView(Ui.text(this, if (ok) "✓" else "✕", 16f, if (ok) Ui.GREEN else Ui.RED, true).apply { setPadding(0, 0, dp(10), 0) })
        r.addView(Ui.text(this, label, 14f).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        if (!ok) r.addView(Ui.button(this, fix) { onFix() }.apply { textSize = 12f; setPadding(dp(10), dp(6), dp(10), dp(6)) })
        parent.addView(r)
    }

    // ------------------------------------------------------------------ screen
    override fun build(root: LinearLayout) {
        Ui.add(root, Ui.title(this, "DashLens"), 0)
        Ui.add(root, Ui.caption(this, "Offer analyzer · mileage · earnings · taxes"), 14)

        val a = accessibilityOn(); val l = hasLocation(); val b = hasBackground(); val n = hasNotifications(); val bat = batteryFree()
        if (!(a && l && b && n && bat)) {
            val c = Ui.card(this)
            c.addView(Ui.text(this, "Finish setup", 16f, Ui.TEXT, true).apply { setPadding(0, 0, 0, dp(6)) })
            checkRow(c, "Offer overlay (Accessibility)", a, "Turn on") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            checkRow(c, "Location permission", l, "Allow") { askPermissions() }
            checkRow(c, "Location: Allow all the time", b, "Open") { appSettings() }
            checkRow(c, "Notifications", n, "Allow") { askPermissions() }
            checkRow(c, "Battery: Unrestricted", bat, "Open") { appSettings() }
            if (!a) c.addView(Ui.caption(this, "If Accessibility is greyed out: Settings > Apps > DashLens > ⋮ > Allow restricted settings, then try again.").apply { setPadding(0, dp(6), 0, 0) })
            Ui.add(root, c)
        }

        // ---- shift card
        val shift = Ui.card(this)
        val top = Ui.hrow(this)
        shiftPill = Ui.pill(this, "NOT TRACKING", Ui.MUTED)
        top.addView(shiftPill)
        shiftLine = Ui.text(this, "", 12f, Ui.MUTED).apply { setPadding(dp(10), 0, 0, 0) }
        top.addView(shiftLine)
        shift.addView(top)

        val r1 = Ui.hrow(this).apply { setPadding(0, dp(14), 0, dp(10)) }
        vIncome = Ui.text(this, "-", 20f, Ui.TEXT, true); vAccepted = Ui.text(this, "-", 20f, Ui.TEXT, true); vPerHr = Ui.text(this, "-", 20f, Ui.GREEN, true)
        r1.addView(tile("Est. income", vIncome!!)); r1.addView(tile("Accepted", vAccepted!!)); r1.addView(tile("$ / hr online", vPerHr!!))
        shift.addView(r1)
        val r2 = Ui.hrow(this).apply { setPadding(0, 0, 0, dp(6)) }
        vOnlineMi = Ui.text(this, "-", 17f, Ui.TEXT, true); vOrderMi = Ui.text(this, "-", 17f, Ui.TEXT, true); vTime = Ui.text(this, "-", 17f, Ui.TEXT, true)
        r2.addView(tile("Online mi", vOnlineMi!!)); r2.addView(tile("On-order mi", vOrderMi!!)); r2.addView(tile("Online time", vTime!!))
        shift.addView(r2)

        vGoal = Ui.caption(this, "")
        val goal = Prefs.get(this, "goal_day")
        if (goal > 0.0) {
            shift.addView(vGoal)
            val td = Finance.today(this)
            shift.addView(Ui.progress(this, td.income / goal, Ui.GREEN).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6) })
        }

        startStop = Ui.button(this, "Start shift", 1) { toggleShift() }
        Ui.add(shift, startStop!!, 0)
        (startStop!!.layoutParams as LinearLayout.LayoutParams).topMargin = dp(14)

        val manual = Ui.hrow(this).apply { setPadding(0, dp(8), 0, 0) }
        fun small(s: String, act: String) = Ui.weighted(Ui.button(this, s) {
            if (Tracker.running) TrackerService.send(this, act) else Toast.makeText(this, "Tap Start shift first", Toast.LENGTH_SHORT).show()
        }.apply { textSize = 13f })
        manual.addView(small("Online", TrackerService.ACTION_ONLINE))
        manual.addView(small("On order", TrackerService.ACTION_ORDER))
        manual.addView(small("Pause", TrackerService.ACTION_PAUSE))
        shift.addView(manual)
        shift.addView(Ui.caption(this, "Manual buttons override auto-detection for 5 minutes.").apply { setPadding(0, dp(8), 0, 0) })
        Ui.add(root, shift)

        // ---- last offer
        val lo = Ui.card(this)
        lo.addView(Ui.text(this, "Last offer", 16f, Ui.TEXT, true))
        val now = System.currentTimeMillis()
        val last = Db.get(this).offers(now - 24 * 3_600_000L, now + 1000L, 1).firstOrNull()
        if (last == null) {
            lo.addView(Ui.caption(this, "Nothing yet today. Go online in Dasher: the DashLens card appears on every offer.").apply { setPadding(0, dp(6), 0, 0) })
        } else {
            val col = when (last.verdict) { "TAKE" -> Ui.GREEN; "MAYBE" -> Ui.AMBER; else -> Ui.RED }
            val h = Ui.hrow(this).apply { setPadding(0, dp(8), 0, dp(4)) }
            h.addView(Ui.pill(this, last.verdict, col))
            h.addView(Ui.text(this, (if (last.merchant.isNotEmpty()) last.merchant else "Offer"), 15f, Ui.TEXT, true).apply { setPadding(dp(10), 0, 0, 0) })
            lo.addView(h)
            lo.addView(Ui.text(this, String.format(Locale.US, "$%.2f · %.1f mi · about $%.0f/hr net · %s", last.pay, last.miles, last.netHr,
                last.outcome.lowercase()), 13f, Ui.MUTED))
        }
        Ui.add(root, lo)

        Ui.add(root, Ui.button(this, "Preview the offer card") {
            if (!OfferService.preview()) Toast.makeText(this, "Turn DashLens on under Accessibility first", Toast.LENGTH_LONG).show()
            else Toast.makeText(this, "Sample offer card shown for 10 seconds", Toast.LENGTH_SHORT).show()
        })
        Ui.add(root, Ui.button(this, "Mileage reports (week / month / year)") { startActivity(Intent(this, ReportsActivity::class.java)) })
        refreshLive()
    }

    private fun tile(label: String, value: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        addView(Ui.text(this@MainActivity, label.uppercase(), 10f, Ui.MUTED, true))
        addView(value)
    }

    private fun toggleShift() {
        if (Tracker.running) { TrackerService.send(this, TrackerService.ACTION_STOP); return }
        if (!hasLocation()) { askPermissions(); Toast.makeText(this, "Allow location, then tap Start shift again", Toast.LENGTH_LONG).show(); return }
        TrackerService.start(this)
    }

    private fun refreshLive() {
        val now = System.currentTimeMillis()
        val t = Reports.totals(this, Reports.dayStart(LocalDate.now()), now)
        val td = Finance.today(this)
        vIncome?.text = Finance.money(td.income)
        vAccepted?.text = td.accepted.toString()
        vPerHr?.text = if (td.onlineMs > 0L && td.income > 0.0) String.format(Locale.US, "$%.0f", td.perHour) else "-"
        vPerHr?.setTextColor(if (td.income > 0.0) Ui.GREEN else Ui.MUTED)
        vOnlineMi?.text = Reports.mi(t.onlineM)
        vOrderMi?.text = Reports.mi(t.orderM)
        vTime?.text = Reports.dur(t.onlineMs)
        val g = Prefs.get(this, "goal_day")
        if (g > 0.0) vGoal?.text = "Daily goal: " + Finance.money(td.income) + " of " + Finance.money(g) + " (" + String.format(Locale.US, "%.0f", td.income / g * 100.0) + "%)"
        if (Tracker.running) {
            shiftPill?.text = "ON SHIFT"
            shiftPill?.setTextColor(Ui.GREEN)
            shiftLine?.text = Tracker.state.label
            startStop?.text = "Stop shift"
            startStop?.let { Ui.restyle(it, 2) }
        } else {
            shiftPill?.text = "NOT TRACKING"
            shiftPill?.setTextColor(Ui.MUTED)
            shiftLine?.text = if (hasLocation()) "Tap Start shift when you go online" else "Location permission needed"
            startStop?.text = "Start shift"
            startStop?.let { Ui.restyle(it, 1) }
        }
    }
}
