package app.dashlens

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
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
import kotlin.math.abs

class OfferService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var overlay: View? = null
    private var overlayLp: WindowManager.LayoutParams? = null
    private var bands: List<Band> = emptyList()      // Accept / Decline buttons as last seen on screen
    private var lastKey = ""
    private var lastDump = ""
    private var lastDetected: DState? = null      // last non-offer screen state, to know if you were already on an order
    private var lastOfferKey = ""
    private var lastOfferAt = 0L
    private var gonePending = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val timeoutRunnable = Runnable { ocrShowing = false; hide() }

    private val goneRunnable = Runnable {
        gonePending = false
        hide()
        OfferLog.gone()
        handler.removeCallbacks(resolveRunnable)
        handler.postDelayed(resolveRunnable, 20_000L)
    }

    private val resolveRunnable = Runnable { OfferLog.resolve(this) }

    private var pendingState: DState? = null
    private val seen = HashMap<String, Seen>()      // what each driver app last showed (merged by StateMerge)
    private val applyPending = Runnable {
        pendingState?.let { Tracker.setState(it) }
        pendingState = null
    }

    /** Feeds the mileage log and offer outcomes: works out Online / On order / not dashing from the driver app screen. */
    private fun trackScreen(texts: List<String>, platform: String) {
        val isOffer = OfferParser.looksLikeOffer(texts, platform)
        val own = StateDetector.detect(this, texts, isOffer, platform)
        StateDetector.log(this, own, texts, platform)
        if (own == null) return
        val now = System.currentTimeMillis()
        seen[platform] = Seen(own, now)
        if (own == DState.ORDER) OfferLog.autoAccepted(this, platform)
        handler.post { try { checkAutoEnd() } catch (_: Exception) {} }
        // the shift state is the strongest recent state across the driver apps (see StateMerge)
        val ds = StateMerge.combine(seen, now) ?: own
        if (!isOffer) lastDetected = ds

        if (!Tracker.running) {
            if (tryAutoStart(platform, own, now)) return
            if (ds != DState.OFFLINE) maybePromptStart(platform)
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

    private var lastAutoTry = 0L

    // ---- auto end: offline or logged out of every driver app
    private var sawActive = false      // this shift has shown an online / on-order screen
    private var allOffSince = 0L
    private var lastEndNote = ""
    private val endCheck = object : Runnable {
        override fun run() {
            try { checkAutoEnd() } catch (_: Exception) {}
            handler.postDelayed(this, 30_000L)
        }
    }

    private fun enabledPlatforms(): Set<String> =
        Platform.PACKAGES.values.filter { it != Platform.UBER || Prefs.getBool(this, "uber_enabled", false) }.toSet()

    private fun endNote(text: String) {
        if (text == lastEndNote) return
        lastEndNote = text
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        Prefs.putString(this, "auto_end_status", "[" + stamp + "] " + text)
    }

    /** Ends the shift when every app you use has been offline / logged out for the set time. Runs every 30 s and after each screen. */
    private fun checkAutoEnd() {
        if (!Tracker.running) { sawActive = false; allOffSince = 0L; return }
        val now = System.currentTimeMillis()
        val enabled = enabledPlatforms()
        if (enabled.any { seen[it]?.state == DState.ONLINE || seen[it]?.state == DState.ORDER }) sawActive = true
        val off = sawActive && AutoEnd.allOff(seen, enabled)
        if (!off) { allOffSince = 0L; return }
        if (allOffSince == 0L) allOffSince = now
        val mins = Prefs.get(this, "auto_end_min")
        if (mins <= 0.0) return
        if (AutoEnd.shouldEnd(true, allOffSince, now, mins, Tracker.state == DState.ORDER)) {
            endNote("ENDED the shift: offline or logged out of every driver app for " + Prefs.fmt(mins) + " min")
            sawActive = false; allOffSince = 0L
            TrackerService.send(this, TrackerService.ACTION_AUTO_END)
        } else {
            endNote("WAITING: offline or logged out of every driver app, ends after " + Prefs.fmt(mins) + " min")
        }
    }

    /** Starts the shift by itself when the "auto start" switch is on. False = not started (the caller falls back to the notification). */
    private fun tryAutoStart(platform: String, own: DState, now: Long): Boolean {
        val on = Prefs.getBool(this, "auto_start", false)
        if (!on) { autoNote("OFF: the switch in Settings is off"); return false }
        val lastStop = Prefs.getLong(this, "last_manual_stop")
        if (!AutoStart.shouldStart(on, Tracker.running, own, now, lastStop, lastAutoTry)) {
            if (!Tracker.running && own != DState.OFFLINE && now - lastStop < AutoStart.COOLDOWN_AFTER_MANUAL_STOP_MS)
                autoNote("WAITING: you stopped a shift by hand less than 10 min ago")
            return false
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            autoNote("BLOCKED: location permission is not granted"); return false
        }
        lastAutoTry = now
        return try {
            TrackerService.start(this)
            Prompts.cancelStart(this)
            Prompts.info(this, "Shift started", "DashLens saw you go online on " + platform + " and started logging miles.")
            autoNote("STARTED on " + platform + " (start request accepted)")
            true
        } catch (e: Exception) {
            // Android does not allow starting location tracking from the background right now: ask with the notification instead
            autoNote("BLOCKED by Android (" + e.javaClass.simpleName + "): " + (e.message ?: "").take(100))
            Prefs.putLong(this, "last_prompt", 0L)
            maybePromptStart(platform)
            false
        }
    }

    private var lastUberCapture = ""
    /** The last 3 Uber screens that look like an offer, so a later screen (accept dialog, arrival) cannot wipe the evidence. */
    private fun keepUberCapture(joined: String) {
        if (joined == lastUberCapture) return
        lastUberCapture = joined
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val old = Prefs.getString(this, "uber_dumps").split("\n====\n").filter { it.isNotBlank() }
        Prefs.putString(this, "uber_dumps", (old + ("[" + stamp + "] " + joined.take(1500))).takeLast(3).joinToString("\n====\n"))
    }

    private var lastAutoNote = ""
    /** What auto start did last, shown in Settings > Diagnostics. Written only when it changes. */
    private fun autoNote(text: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val line = "[" + stamp + "] " + text
        if (text == lastAutoNote) return
        lastAutoNote = text
        Prefs.putString(this, "auto_start_status", line)
    }

    private fun maybePromptStart(platform: String) {
        if (!Prefs.getBool(this, "prompt_start", true)) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val now = System.currentTimeMillis()
        if (now - Prefs.getLong(this, "last_prompt") < 30 * 60_000L) return
        Prefs.putLong(this, "last_prompt", now)
        Prompts.promptStart(this, platform)
    }

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
        handler.removeCallbacks(endCheck)
        handler.postDelayed(endCheck, 30_000L)
        // lets DashLens read every window of a driver app (Uber's offer card is a window of its own)
        try {
            val info = serviceInfo
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            serviceInfo = info
        } catch (_: Exception) {}
    }

    /** Sample card so you can check the overlay without waiting for a real offer. Never written to the history. */
    private fun showSample() {
        bands = emptyList()
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

    /**
     * The window the event came from. An Uber offer can float over another app (Messages, Maps): then the active window
     * is that other app and Uber's card would never be read. So when the active window is not the event's app, walk up
     * from the event's own node to its window root.
     */
    private fun driverRoot(e: AccessibilityEvent): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        val pkg = e.packageName?.toString()
        if (active != null && (pkg == null || active.packageName?.toString() == pkg)) return active
        if (Platform.of(pkg) == null) return active
        var n: AccessibilityNodeInfo = e.source ?: return active
        var hops = 0
        while (hops < 60) { val p = n.parent ?: break; n = p; hops++ }
        return n
    }

    /**
     * Every window of the driver app. Uber draws its offer card as a window of its own on top of the map, so reading
     * only the active window never sees it. Falls back to the single window [driverRoot] finds when Android gives no list.
     */
    private fun driverRoots(e: AccessibilityEvent): List<AccessibilityNodeInfo> {
        val known = e.packageName?.toString() ?: rootInActiveWindow?.packageName?.toString()
        val out = ArrayList<AccessibilityNodeInfo>()
        if (Platform.of(known) != null) {
            try {
                for (w in windows) {
                    val r = w.root ?: continue
                    if (r.packageName?.toString() == known) out.add(r)
                }
            } catch (_: Exception) {}
        }
        if (out.isEmpty()) driverRoot(e)?.let { out.add(it) }
        return out
    }

    private var lastWinKey = ""
    /** For Settings > Diagnostics: how many Uber windows DashLens can see, and whether one looks like an offer. Written only when that changes. */
    private fun windowNote(n: Int, counts: List<Int>, offer: Boolean) {
        val key = n.toString() + "/" + offer
        if (key == lastWinKey) return
        lastWinKey = key
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        Prefs.putString(this, "win_status", "[" + stamp + "] Uber windows seen: " + n + " (text lines " + counts.joinToString(" + ") + "), offer screen: " + (if (offer) "yes" else "no"))
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        val roots = driverRoots(e)
        val root = roots.firstOrNull() ?: return
        val platform = Platform.of(root.packageName?.toString()) ?: return
        // Uber is opt-in: until it is switched on in Settings nothing is read from it
        if (platform == Platform.UBER && !Prefs.getBool(this, "uber_enabled", false)) return

        val texts = ArrayList<String>()
        val counts = ArrayList<Int>()
        for (r in roots) { val n0 = texts.size; collect(r, texts, 0); counts.add(texts.size - n0) }
        if (platform == Platform.UBER) windowNote(roots.size, counts, OfferParser.looksLikeOffer(texts, platform))
        // Uber hides its offer card from the accessibility read (the whole tree comes back empty while it is up): read it from a screenshot
        if (platform == Platform.UBER && texts.isEmpty()) maybeOcr()

        val joined = texts.joinToString(" | ")
        // Only offer-looking screens replace the captured text (Uber's "already accepted" dialog used to wipe it)
        val dumpable = OfferParser.looksLikeOffer(texts, platform)
        if (platform == Platform.UBER && (dumpable || OfferParser.uberMaybeOffer(texts))) keepUberCapture(joined)
        if (dumpable && joined != lastDump) {
            lastDump = joined
            Prefs.putString(this, "last_dump", "[" + platform + "] " + joined.take(4000))
        }

        trackScreen(texts, platform)

        val offer = OfferParser.parse(texts, platform = platform)
        val tag = if (platform == Platform.UBER) " (Uber)" else ""
        if (offer == null && OfferParser.looksLikeOffer(texts, platform))
            Prefs.putString(this, "parse_status", "FAILED" + tag + ": an offer screen was seen but pay or miles could not be read. Copy the offer text below.")
        if (offer != null)
            Prefs.putString(this, "parse_status", "OK" + tag + ": read \$" + offer.pay + ", " + offer.miles + " mi" +
                (if (offer.merchant.isNotEmpty()) ", " + offer.merchant else ", merchant not found"))
        if (offer == null) {
            if (ocrShowing) return      // the screenshot route owns this card and hides it itself
            // offer screen gone (accepted / declined / expired): hide shortly, avoids flicker
            handler.removeCallbacks(timeoutRunnable)
            if (!gonePending) { gonePending = true; handler.postDelayed(goneRunnable, 700) }
            return
        }

        presentOffer(offer, roots, null)
    }

    /** Shows the card for an offer, or just keeps it alive when it is the same offer. [ocrBands] = button rows found in a screenshot (no windows to search). */
    private fun presentOffer(offer: Offer, roots: List<AccessibilityNodeInfo>, ocrBands: List<Band>?) {
        gonePending = false
        handler.removeCallbacks(goneRunnable)
        handler.removeCallbacks(resolveRunnable)
        handler.removeCallbacks(timeoutRunnable)
        OfferLog.reappeared()

        val key = "${offer.platform}|${offer.pay}|${offer.miles}|${offer.merchants.joinToString("+")}"
        if (key == lastKey && overlay != null) {
            if (ocrBands == null) relayout(roots)
            handler.postDelayed(timeoutRunnable, 45_000)
            return
        }
        lastKey = key
        bands = ocrBands ?: roots.flatMap { findBands(it) }
        val result = Analyzer.analyze(this, offer)
        logOffer(offer, result, key)
        show(offer, result)
        feedback(result)
        handler.postDelayed(timeoutRunnable, 45_000)
    }

    // ---- Uber offer card read from a screenshot

    private var ocrBusy = false
    private var lastOcrAt = 0L
    private var ocrShowing = false
    private var ocrMisses = 0

    private val ocrRecheck = object : Runnable {
        override fun run() {
            if (!ocrShowing) return
            if (ocrBusy) { handler.postDelayed(this, 1000L); return }
            runOcr(true)
        }
    }

    /** At most one screenshot every 2.5 s, and only while Uber's tree is empty (an offer card, or Uber in the background). */
    private fun maybeOcr() {
        if (!Prefs.getBool(this, "uber_ocr", true) || ocrBusy || ocrShowing) return
        val now = System.currentTimeMillis()
        if (now - lastOcrAt < 2500L) return
        lastOcrAt = now
        runOcr(false)
    }

    private fun runOcr(recheck: Boolean) {
        ocrBusy = true
        OfferOcr.read(this) { lines, why ->
            ocrBusy = false
            if (lines == null) {
                ocrNote("FAILED: " + why)
                if (recheck && ocrShowing) handler.postDelayed(ocrRecheck, 2500L)
                return@read
            }
            val texts = OcrText.texts(lines)
            val offer = OfferParser.parse(texts, platform = Platform.UBER)
            // keep the text only when it looks like an offer: other apps on screen are never stored
            if (OfferParser.looksLikeOffer(texts, Platform.UBER) || OfferParser.uberMaybeOffer(texts)) {
                val card = OcrText.cardLines(lines)          // just the card: never the rest of the screen
                if (card.isNotEmpty()) keepUberCapture("[screenshot] " + card.joinToString(" | "))
            }
            if (offer != null) {
                ocrNote("OK: read \$" + offer.pay + ", " + offer.miles + " mi" + (if (offer.merchant.isNotEmpty()) ", " + offer.merchant else ", merchant not found"))
                Prefs.putString(this, "parse_status", "OK (Uber, from screenshot): read \$" + offer.pay + ", " + offer.miles + " mi" +
                    (if (offer.merchant.isNotEmpty()) ", " + offer.merchant else ", merchant not found"))
                ocrMisses = 0
                ocrShowing = true
                presentOffer(offer, emptyList(), OcrText.bands(lines, dp(20)))
                handler.removeCallbacks(ocrRecheck)
                handler.postDelayed(ocrRecheck, 2500L)
            } else if (recheck) {
                ocrMisses++
                if (ocrMisses >= 2) {          // two looks in a row without the offer: it was accepted, declined or expired
                    ocrShowing = false
                    goneRunnable.run()
                } else handler.postDelayed(ocrRecheck, 2500L)
            } else {
                ocrNote("screenshot read, no Uber offer on screen")
            }
        }
    }

    private var lastOcrNote = ""
    /** What the screenshot route did last, for Settings > Diagnostics. Written only when the result changes. */
    private fun ocrNote(text: String) {
        if (text == lastOcrNote) return
        lastOcrNote = text
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        Prefs.putString(this, "uber_ocr_status", "[" + stamp + "] " + text)
    }

    /** One history row per distinct offer (the same offer re-appearing within a minute is not logged twice). */
    private fun logOffer(o: Offer, r: Result, key: String) {
        val now = System.currentTimeMillis()
        if (key == lastOfferKey && now - lastOfferAt < 60_000L) { lastOfferAt = now; return }
        lastOfferKey = key; lastOfferAt = now
        val loc = Tracker.lastFix
        val id = Db.get(this).insertOffer(
            OfferRow(0L, now, o.pay, o.miles, r.tripMin, o.pickups, r.verdict.name, r.netHr, r.perMile, "PENDING",
                loc?.latitude ?: 0.0, loc?.longitude ?: 0.0, o.merchant, 0.0, 0.0, o.windowMin, o.tipsIncluded, o.items, o.payPlus, o.platform)
        )
        OfferLog.begin(id, lastDetected == DState.ORDER, o.platform)
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

        if (o.merchant.isNotEmpty() || o.platform != Platform.DOORDASH) {
            val sub = (if (o.platform != Platform.DOORDASH) o.platform + "   ·   " else "") + o.merchant + (if (o.pickups > 1) "  +" + (o.pickups - 1) + " more" else "") +
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

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = cardTop(wrap)
        }
        try {
            wm?.addView(wrap, lp)
            overlay = wrap
            overlayLp = lp
        } catch (_: Exception) { overlay = null; overlayLp = null }
    }

    private fun screenSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") wm?.defaultDisplay?.getRealMetrics(dm)
        val m = resources.displayMetrics
        return Pair(if (dm.widthPixels > 0) dm.widthPixels else m.widthPixels, if (dm.heightPixels > 0) dm.heightPixels else m.heightPixels)
    }

    /** Top edge for the card: your top/bottom choice plus your nudge, then moved off the Accept/Decline buttons if needed. */
    private fun cardTop(v: View): Int {
        val (w, h) = screenSize()
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val cardH = v.measuredHeight
        val nudge = (Prefs.get(this, "overlay_nudge_pct") / 100.0 * h).toInt()
        val bottom = Prefs.getBool(this, "overlay_bottom", false)
        val preferred = (if (bottom) h - cardH - dp(120) else dp(36)) + nudge
        val avoid = if (Prefs.getBool(this, "overlay_avoid", true)) bands else emptyList()
        val y = CardPlacement.chooseTop(preferred, cardH, avoid, dp(24), h - dp(24), dp(8))
        val covered = CardPlacement.covers(y, cardH, avoid, dp(8))
        Prefs.putString(this, "layout_status",
            if (avoid.isEmpty()) "Accept/Decline buttons not located, card at y=" + y + " of " + h + " px"
            else (avoid.size.toString() + " button(s) at " + avoid.joinToString(", ") { it.top.toString() + "-" + it.bottom } +
                "; card at y=" + y + " of " + h + " px" +
                (if (covered) ". No free space: turn on the compact card." else ". Clear.")))
        return y
    }

    /** Finds the Accept and Decline buttons so the card can stay off them. Uses the clickable parent when the text node is only the label. */
    private fun findBands(root: AccessibilityNodeInfo): List<Band> {
        if (!Prefs.getBool(this, "overlay_avoid", true)) return emptyList()
        val (_, h) = screenSize()
        val out = ArrayList<Band>()
        fun visit(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40) return
            val t = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
            if (t.startsWith("decline", true) || t.startsWith("accept", true) || t.equals("match", true)) {
                val own = Rect(); n.getBoundsInScreen(own)
                var use = own
                var p = n.parent
                var hops = 0
                while (p != null && hops < 3) {
                    if (p.isClickable) {
                        val r = Rect(); p.getBoundsInScreen(r)
                        if (r.height() < h * 0.3) use = r
                        break
                    }
                    p = p.parent; hops++
                }
                if (use.height() > 0 && use.bottom > 0 && use.top < h) out.add(Band(use.top, use.bottom))
            }
            for (i in 0 until n.childCount) visit(n.getChild(i), depth + 1)
        }
        visit(root, 0)
        return out
    }

    /** The same offer is still on screen: if the buttons moved (the Dasher screen was still animating), move the card too. */
    private fun relayout(roots: List<AccessibilityNodeInfo>) {
        if (!Prefs.getBool(this, "overlay_avoid", true)) return
        val now = roots.flatMap { findBands(it) }
        val moved = now.size != bands.size || now.indices.any { abs(now[it].top - bands[it].top) > dp(12) || abs(now[it].bottom - bands[it].bottom) > dp(12) }
        if (!moved) return
        bands = now
        val ov = overlay ?: return
        val lp = overlayLp ?: return
        val y = cardTop(ov)
        if (y != lp.y) {
            lp.y = y
            try { wm?.updateViewLayout(ov, lp) } catch (_: Exception) {}
        }
    }

    private fun hide() {
        handler.removeCallbacks(timeoutRunnable)
        overlay?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        overlay = null
        overlayLp = null
        lastKey = ""
    }
}
