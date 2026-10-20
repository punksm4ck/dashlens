package app.dashlens

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class OfferService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var overlay: View? = null
    private var lastKey = ""
    private var lastDump = ""
    private var lastDetected: DState? = null      // last non-offer screen state, to know if you were already on an order
    private var lastOfferKey = ""
    private var lastOfferAt = 0L
    private var gonePending = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val timeoutRunnable = Runnable { hide() }

    private val goneRunnable = Runnable {
        gonePending = false
        hide()
        OfferLog.gone()
        handler.removeCallbacks(resolveRunnable)
        handler.postDelayed(resolveRunnable, 20_000L)
    }

    private val resolveRunnable = Runnable { OfferLog.resolve(this) }

    private var pendingState: DState? = null
    private val applyPending = Runnable {
        pendingState?.let { Tracker.setState(it) }
        pendingState = null
    }

    /** Feeds the mileage log and offer outcomes: works out Online / On order / not dashing from the Dasher screen. */
    private fun trackScreen(texts: List<String>) {
        val isOffer = OfferParser.looksLikeOffer(texts)
        val ds = StateDetector.detect(this, texts, isOffer)
        StateDetector.log(this, ds, texts)
        if (ds != null && !isOffer) lastDetected = ds
        if (ds == DState.ORDER) OfferLog.autoAccepted(this)
        if (ds == null) return

        if (!Tracker.running) {
            if (ds != DState.OFFLINE) maybePromptStart()
            return
        }
        if (!Prefs.getBool(this, "auto_state", true)) return
        if (System.currentTimeMillis() < Tracker.manualUntil) return
        if (ds == Tracker.state) {
            pendingState = null
            handler.removeCallbacks(applyPending)
        } else if (pendingState != ds) {
            pendingState = ds
            handler.removeCallbacks(applyPending)
            handler.postDelayed(applyPending, 3000L)
        }
    }

    private fun maybePromptStart() {
        if (!Prefs.getBool(this, "prompt_start", true)) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val now = System.currentTimeMillis()
        if (now - Prefs.getLong(this, "last_prompt") < 30 * 60_000L) return
        Prefs.putLong(this, "last_prompt", now)
        Prompts.promptStart(this)
    }

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
    }

    /** Sample card so you can check the overlay without waiting for a real offer. Never written to the history. */
    private fun showSample() {
        val o = ShareCard.sampleOffer()
        show(o, Analyzer.analyze(this, o))
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, 10_000L)
    }

    companion object {
        @Volatile private var instance: OfferService? = null

        /** False when the Accessibility service is not running. */
        fun preview(): Boolean {
            val s = instance ?: return false
            s.handler.post { s.showSample() }
            return true
        }
    }

    override fun onInterrupt() { hide() }

    override fun onDestroy() {
        instance = null
        hide()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != "com.doordash.driverapp") return

        val texts = ArrayList<String>()
        collect(root, texts, 0)

        val joined = texts.joinToString(" | ")
        if ((joined.contains("accept", true) || joined.contains("decline", true)) && joined != lastDump) {
            lastDump = joined
            Prefs.putString(this, "last_dump", joined.take(4000))
        }

        trackScreen(texts)

        val offer = OfferParser.parse(texts)
        if (offer == null && OfferParser.looksLikeOffer(texts))
            Prefs.putString(this, "parse_status", "FAILED: an offer screen was seen but pay or miles could not be read. Copy the offer text below.")
        if (offer != null)
            Prefs.putString(this, "parse_status", "OK: read \$" + offer.pay + ", " + offer.miles + " mi" +
                (if (offer.merchant.isNotEmpty()) ", " + offer.merchant else ", merchant not found"))
        if (offer == null) {
            // offer screen gone (accepted / declined / expired): hide shortly, avoids flicker
            handler.removeCallbacks(timeoutRunnable)
            if (!gonePending) { gonePending = true; handler.postDelayed(goneRunnable, 700) }
            return
        }

        gonePending = false
        handler.removeCallbacks(goneRunnable)
        handler.removeCallbacks(resolveRunnable)
        handler.removeCallbacks(timeoutRunnable)
        OfferLog.reappeared()

        val key = "${offer.pay}|${offer.miles}|${offer.merchants.joinToString("+")}"
        if (key == lastKey && overlay != null) {
            handler.postDelayed(timeoutRunnable, 45_000)
            return
        }
        lastKey = key
        val result = Analyzer.analyze(this, offer)
        logOffer(offer, result, key)
        show(offer, result)
        feedback(result)
        handler.postDelayed(timeoutRunnable, 45_000)
    }

    /** One history row per distinct offer (the same offer re-appearing within a minute is not logged twice). */
    private fun logOffer(o: Offer, r: Result, key: String) {
        val now = System.currentTimeMillis()
        if (key == lastOfferKey && now - lastOfferAt < 60_000L) { lastOfferAt = now; return }
        lastOfferKey = key; lastOfferAt = now
        val loc = Tracker.lastFix
        val id = Db.get(this).insertOffer(
            OfferRow(0L, now, o.pay, o.miles, r.tripMin, o.pickups, r.verdict.name, r.netHr, r.perMile, "PENDING",
                loc?.latitude ?: 0.0, loc?.longitude ?: 0.0, o.merchant, 0.0, 0.0, o.windowMin, o.tipsIncluded, o.items, o.payPlus)
        )
        OfferLog.begin(id, lastDetected == DState.ORDER)
    }

    private fun feedback(r: Result) {
        if (Prefs.getBool(this, "fb_haptic", false)) {
            val n = when (r.verdict) { Verdict.TAKE -> 1; Verdict.MAYBE -> 2; Verdict.DECLINE -> 3 }
            val pat = ArrayList<Long>()
            pat.add(0L)
            for (i in 0 until n) { pat.add(120L); if (i < n - 1) pat.add(110L) }
            try {
                getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(pat.toLongArray(), -1))
            } catch (_: Exception) {}
        }
        if (Prefs.getBool(this, "fb_voice", false)) {
            val say = when (r.verdict) { Verdict.TAKE -> "Take"; Verdict.MAYBE -> "Maybe"; Verdict.DECLINE -> "Decline" } +
                ". " + String.format(Locale.US, "%.0f dollars an hour.", r.netHr)
            if (tts == null) tts = TextToSpeech(this) { st -> ttsReady = st == TextToSpeech.SUCCESS }
            if (ttsReady) tts?.speak(say, TextToSpeech.QUEUE_FLUSH, null, "dashlens")
        }
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (n == null || depth > 40) return
        n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        for (i in 0 until n.childCount) collect(n.getChild(i), out, depth + 1)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun stat(label: String, value: String, color: Int = 0xFFFFFFFF.toInt()): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(tv(label, 10f, 0xFF9AA0A6.toInt()))
            addView(tv(value, 18f, color, true))
        }

    private fun show(o: Offer, r: Result) {
        hide()
        val good = 0xFF2ECC71.toInt()
        val mid = 0xFFFFB300.toInt()
        val bad = 0xFFFF4D4D.toInt()
        val muted = 0xFF9AA0A6.toInt()
        val accent = when (r.verdict) { Verdict.TAKE -> good; Verdict.MAYBE -> mid; Verdict.DECLINE -> bad }
        val f = { fmt: String, v: Double -> String.format(Locale.US, fmt, v) }
        val compact = Prefs.getBool(this, "overlay_compact", false)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(0xF2111418.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), accent)
            }
        }

        // header: verdict on the left, net $/hr on the right
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv(r.verdict.title, 22f, accent, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val plus = if (o.payPlus) "+" else ""
        head.addView(tv(f("$%.0f/hr net", r.netHr) + plus, 20f, accent, true))
        card.addView(head)
        card.addView(tv(r.floorLine, 11f, muted))

        if (o.merchant.isNotEmpty()) {
            val sub = o.merchant + (if (o.pickups > 1) "  +" + (o.pickups - 1) + " more" else "") +
                (if (o.tipsIncluded) "   ·   tips included" else "")
            card.addView(tv(sub, 13f, 0xFFE8EAED.toInt(), true).apply { setPadding(0, dp(2), 0, dp(4)) })
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(4)) }
        row1.addView(stat("PAY", f("$%.2f", o.pay) + plus))
        row1.addView(stat("NET $ / MI", f("$%.2f", r.netPerOfferMile) + plus, if (r.netPerOfferMile >= 0.0) 0xFFFFFFFF.toInt() else bad))
        row1.addView(stat("MILES", f("%.1f", o.miles)))
        row1.addView(stat("ORDER TIME", "~" + r.tripMin.toInt() + " min"))
        card.addView(row1)

        if (!compact) {
            val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(4)) }
            row2.addView(stat("$ / REAL MILE", f("$%.2f", r.perMile), if (r.perMile >= Prefs.get(this, "min_mi")) good else bad))
            row2.addView(stat("REAL MILES", f("%.1f", r.realMiles)))
            row2.addView(stat("WHOLE CYCLE", "~" + r.totalMin.toInt() + " min"))
            if (o.windowMin > 0) row2.addView(stat("DELIVER BY", "in " + o.windowMin.toInt() + " min"))
            card.addView(row2)
            if (Prefs.getBool(this, "overlay_math", true)) {
                r.hard.forEach { card.addView(tv("• $it", 11f, bad)) }
                r.mathLines.forEach {
                    val net = it.startsWith("Net pay")
                    card.addView(tv(it, 11f, if (net) 0xFFFFFFFF.toInt() else 0xFFD0D3D8.toInt(), net))
                }
                if (o.payPlus) card.addView(tv("Graded at the + minimum: real pay may be higher.", 11f, mid))
            } else {
                r.reasons.take(4).forEach { card.addView(tv("• $it", 11f, 0xFFD0D3D8.toInt())) }
            }
        }
        if (r.merchantLine.isNotEmpty())
            card.addView(tv(r.merchantLine, 11f, 0xFF7FB2FF.toInt(), true).apply { setPadding(0, dp(4), 0, 0) })

        val td = Finance.today(this)
        val goal = Finance.goalLine(this, td)
        val foot = "Today: " + Finance.money(td.income) + " est. · " + td.accepted + " accepted · " +
            Reports.dur(td.onlineMs) + " online" + (if (goal.isNotEmpty()) " · " + goal else "")
        card.addView(tv(foot, 10f, muted).apply { setPadding(0, dp(6), 0, 0) })

        val wrap = FrameLayout(this).apply {
            setPadding(dp(10), 0, dp(10), 0)
            addView(card)
        }

        val bottom = Prefs.getBool(this, "overlay_bottom", false)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (bottom) Gravity.BOTTOM else Gravity.TOP
            y = if (bottom) dp(120) else dp(36)
        }
        try {
            wm?.addView(wrap, lp)
            overlay = wrap
        } catch (_: Exception) { overlay = null }
    }

    private fun hide() {
        handler.removeCallbacks(timeoutRunnable)
        overlay?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        overlay = null
        lastKey = ""
    }
}
