package app.dashlens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

/** All thresholds, assumptions, options, keyword tuning, diagnostics and about. Saves automatically when you leave. */
class SettingsActivity : BaseActivity() {
    override val tab = 4

    private val inputs = LinkedHashMap<String, EditText>()
    private val kw = LinkedHashMap<String, EditText>()

    override fun onPause() {
        saveAll()
        super.onPause()
    }

    private fun saveAll() {
        for ((k, et) in inputs) et.text.toString().toFloatOrNull()?.let { Prefs.put(this, k, it) }
        for ((k, et) in kw) Prefs.putString(this, k, et.text.toString().trim())
        Calibration.reset()
    }

    private fun preset(name: String, hr: Float, mi: Float) =
        Ui.chip(this, name, false) {
            saveAll()
            Prefs.put(this, "min_hr", hr); Prefs.put(this, "min_mi", mi)
            Toast.makeText(this, name + ": minimum " + hr.toInt() + " \$/hr and \$" + mi + " per real mile", Toast.LENGTH_SHORT).show()
            inputs.clear(); kw.clear(); render()
        }

    private val typical = mapOf(
        "gas_price" to 3.87f, "mpg" to 25f, "wear_mi" to 0.18f, "shop_min_item" to 1.5f, "checkout_min" to 5f,
        "dropoff_min" to 5f, "empty_pct" to 50f, "offer_speed_mph" to 25f, "speed_mph" to 25f
    )

    private fun confirmTypical() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Load typical Dasher assumptions?")
            .setMessage(
                "This replaces your gas price, mpg, wear and tear, shopping and checkout time, drop-off time, empty-return miles and speed with typical values " +
                    "(gas \$3.87/gal, 25 mpg, wear \$0.18/mi, 1.5 min per shop item, 5 min checkout, 5 min drop-off, half a trip back empty, 25 mph). " +
                    "Your minimum \$/hr and \$/mile are not changed."
            )
            .setPositiveButton("Load") { _, _ ->
                saveAll()
                for ((k, v) in typical) Prefs.put(this, k, v)
                Prefs.putBool(this, "itemize_cost", true)
                inputs.clear(); kw.clear(); render()
                Toast.makeText(this, "Typical Dasher assumptions loaded", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copy(key: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dashlens", Prefs.getString(this, key)))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    override fun build(root: LinearLayout) {
        inputs.clear(); kw.clear()
        Ui.add(root, Ui.title(this, "Settings"), 12)

        // ---- quick profiles
        val q = Ui.card(this)
        q.addView(Ui.text(this, "How picky should DashLens be?", 16f, Ui.TEXT, true))
        q.addView(Ui.caption(this, "Quick profiles set the two minimums. You can fine-tune everything below.").apply { setPadding(0, dp(4), 0, dp(10)) })
        val row = Ui.hrow(this)
        row.addView(Ui.weighted(preset("Strict", 22f, 1.75f)))
        row.addView(Ui.weighted(preset("Balanced", 18f, 1.25f)))
        row.addView(Ui.weighted(preset("Easygoing", 14f, 1.0f)))
        q.addView(row)
        Ui.add(root, q)

        val t = Ui.card(this)
        t.addView(Ui.text(this, "Costs and time: typical Dasher", 16f, Ui.TEXT, true))
        t.addView(Ui.caption(this, "Not sure what to enter? Load a typical set of gas, wear, shopping and drop-off numbers, then change what does not match you.")
            .apply { setPadding(0, dp(4), 0, dp(10)) })
        t.addView(Ui.button(this, "Load typical Dasher assumptions") { confirmTypical() })
        Ui.add(root, t)

        // ---- numbers, grouped
        var card: LinearLayout? = null
        var last = ""
        for (f in Prefs.FIELDS) {
            if (f.group != last) {
                card = Ui.card(this)
                card.addView(Ui.text(this, f.group, 16f, Ui.TEXT, true).apply { setPadding(0, 0, 0, dp(4)) })
                Ui.add(root, card)
                last = f.group
            }
            card!!.addView(Ui.caption(this, f.label).apply { setPadding(0, dp(10), 0, dp(4)) })
            val et = Ui.input(this, "", Prefs.fmt(Prefs.get(this, f.key)))
            inputs[f.key] = et
            card.addView(et)
        }

        // ---- options
        val o = Ui.card(this)
        o.addView(Ui.text(this, "Options", 16f, Ui.TEXT, true))
        for (sw in Prefs.SWITCHES) o.addView(Ui.switch(this, sw.label, Prefs.getBool(this, sw.key, sw.def)) { Prefs.putBool(this, sw.key, it) })
        Ui.add(root, o)

        // ---- keywords
        val k = Ui.card(this)
        k.addView(Ui.text(this, "Screen keywords (advanced)", 16f, Ui.TEXT, true))
        k.addView(Ui.caption(this, "Comma separated. Blank = built-in defaults, which already know Dasher's online and offline screens.").apply { setPadding(0, dp(4), 0, 0) })
        val defs = listOf(
            Triple("kw_order", "Words that mean ON ORDER", StateDetector.DEF_ORDER),
            Triple("kw_online", "Words that mean ONLINE", StateDetector.DEF_ONLINE),
            Triple("kw_offline", "Words that mean NOT dashing", StateDetector.DEF_OFFLINE)
        )
        for ((key, label, def) in defs) {
            k.addView(Ui.caption(this, label).apply { setPadding(0, dp(10), 0, dp(4)) })
            val et = Ui.input(this, def, Prefs.getString(this, key), false).apply { textSize = 13f }
            kw[key] = et
            k.addView(et)
        }
        Ui.add(root, k)

        // ---- diagnostics
        val d = Ui.card(this)
        d.addView(Ui.text(this, "Diagnostics", 16f, Ui.TEXT, true))
        d.addView(Ui.caption(this, "If an offer card or the Online / On order state is wrong, copy these and send them to support.").apply { setPadding(0, dp(4), 0, dp(8)) })
        d.addView(Ui.button(this, "Preview the offer card") {
            if (!OfferService.preview()) Toast.makeText(this, "Turn DashLens on under Accessibility first", Toast.LENGTH_LONG).show()
        })
        d.addView(Ui.button(this, "Share a sample offer card (test)") { ShareCard.shareSample(this) })
        val ps = Prefs.getString(this, "parse_status")
        d.addView(Ui.text(this, "Last offer read: " + (if (ps.isEmpty()) "no offer seen yet" else ps), 13f,
            if (ps.startsWith("OK")) Ui.GREEN else if (ps.startsWith("FAILED")) Ui.RED else Ui.MUTED, true).apply { setPadding(0, 0, 0, dp(8)) })
        val dump = Prefs.getString(this, "last_dump")
        d.addView(Ui.caption(this, "Last captured offer text"))
        d.addView(Ui.text(this, if (dump.isEmpty()) "(nothing captured yet)" else dump, 11f, Ui.MUTED, mono = true))
        d.addView(Ui.button(this, "Copy offer text") { copy("last_dump") })
        val sl = Prefs.getString(this, "screen_log")
        d.addView(Ui.caption(this, "Screen log (last 12 changes)").apply { setPadding(0, dp(12), 0, 0) })
        d.addView(Ui.text(this, if (sl.isEmpty()) "(nothing logged yet)" else sl, 11f, Ui.MUTED, mono = true))
        d.addView(Ui.button(this, "Copy screen log") { copy("screen_log") })
        Ui.add(root, d)

        // ---- about
        val a = Ui.card(this)
        a.addView(Ui.text(this, "About DashLens", 16f, Ui.TEXT, true))
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        a.addView(Ui.caption(this, "Version " + ver).apply { setPadding(0, dp(4), 0, dp(6)) })
        a.addView(Ui.text(this,
            "Everything stays on this phone: DashLens has no internet permission and no account. It reads only the DoorDash Dasher app screen, never taps or accepts anything for you, " +
                "and is not affiliated with or endorsed by DoorDash. Earnings and tax figures are estimates, not tax advice.", 12f, Ui.MUTED))
        a.addView(Ui.button(this, "Replay the setup guide") { startActivity(Intent(this, OnboardingActivity::class.java)) })
        Ui.add(root, a)
    }
}
