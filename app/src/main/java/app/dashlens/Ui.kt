package app.dashlens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** One small design system so every screen looks like the same product. Framework views only. */
object Ui {
    val BG = 0xFF0E1116.toInt()
    val SURFACE = 0xFF171B22.toInt()
    val SURFACE2 = 0xFF222834.toInt()
    val STROKE = 0xFF2B3340.toInt()
    val TEXT = 0xFFE8EAED.toInt()
    val MUTED = 0xFF9AA0A6.toInt()
    val GREEN = 0xFF2ECC71.toInt()
    val AMBER = 0xFFFFB300.toInt()
    val RED = 0xFFFF4D4D.toInt()
    val BLUE = 0xFF4D8DFF.toInt()

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun rounded(c: Context, fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(c, radiusDp).toFloat()
            if (stroke != null) setStroke(dp(c, 1), stroke)
        }

    fun text(c: Context, s: CharSequence, sp: Float = 14f, color: Int = TEXT, bold: Boolean = false, mono: Boolean = false) =
        TextView(c).apply {
            text = s
            textSize = sp
            setTextColor(color)
            if (mono) typeface = Typeface.MONOSPACE
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun title(c: Context, s: String) = text(c, s, 26f, TEXT, true)
    fun heading(c: Context, s: String) = text(c, s, 16f, TEXT, true).apply { setPadding(0, dp(c, 8), 0, dp(c, 8)) }
    fun caption(c: Context, s: CharSequence) = text(c, s, 12f, MUTED)

    fun card(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(c, SURFACE, 16, STROKE)
        setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14))
    }

    /** Adds [v] to [parent] with a bottom margin. */
    fun add(parent: LinearLayout, v: View, bottomDp: Int = 12): View {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(parent.context, bottomDp)
        parent.addView(v, lp)
        return v
    }

    /** kind: 0 secondary, 1 primary (green), 2 danger (red) */
    fun button(c: Context, label: String, kind: Int = 0, onClick: () -> Unit): Button = Button(c).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        stateListAnimator = null
        val fill = when (kind) { 1 -> GREEN; 2 -> RED; else -> SURFACE2 }
        background = rounded(c, fill, 12, if (kind == 0) STROKE else null)
        setTextColor(if (kind == 1) 0xFF08110B.toInt() else TEXT)
        setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
        setOnClickListener { onClick() }
    }

    /** Re-colours an existing button: 0 secondary, 1 primary (green), 2 danger (red). */
    fun restyle(b: Button, kind: Int) {
        val c = b.context
        val fill = when (kind) { 1 -> GREEN; 2 -> RED; else -> SURFACE2 }
        b.background = rounded(c, fill, 12, if (kind == 0) STROKE else null)
        b.setTextColor(if (kind == 1) 0xFF08110B.toInt() else TEXT)
    }

    /** "‹  title  ›" period selector that never clips the title. */
    fun navRow(c: Context, title: String, onPrev: () -> Unit, onNext: () -> Unit): LinearLayout = hrow(c).apply {
        addView(chip(c, "‹", false, onPrev).apply { textSize = 20f; setPadding(dp(c, 20), dp(c, 6), dp(c, 20), dp(c, 6)) })
        addView(text(c, title, 15f, TEXT, true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(chip(c, "›", false, onNext).apply { textSize = 20f; setPadding(dp(c, 20), dp(c, 6), dp(c, 20), dp(c, 6)) })
    }

    /** Label on the left, value on the right, optional small note underneath. */
    fun kv(c: Context, label: String, value: String, valueColor: Int = TEXT, note: String = ""): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(c, 6), 0, dp(c, 6))
        val r = hrow(c)
        r.addView(text(c, label, 14f, TEXT).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        r.addView(text(c, value, 16f, valueColor, true))
        addView(r)
        if (note.isNotEmpty()) addView(text(c, note, 11f, MUTED))
    }

    fun weighted(v: View, weight: Float = 1f, marginDp: Int = 4): View {
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        lp.setMargins(dp(v.context, marginDp), 0, dp(v.context, marginDp), 0)
        v.layoutParams = lp
        return v
    }

    fun hrow(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun chip(c: Context, label: String, selected: Boolean, onClick: () -> Unit): TextView = TextView(c).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(if (selected) 0xFF08110B.toInt() else TEXT)
        if (selected) setTypeface(typeface, Typeface.BOLD)
        background = rounded(c, if (selected) GREEN else SURFACE2, 20, if (selected) null else STROKE)
        setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8))
        setOnClickListener { onClick() }
    }

    fun pill(c: Context, label: String, color: Int): TextView = TextView(c).apply {
        text = label
        textSize = 11f
        setTextColor(color)
        setTypeface(typeface, Typeface.BOLD)
        background = rounded(c, SURFACE2, 8, color)
        setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3))
    }

    fun stat(c: Context, label: String, value: String, color: Int = TEXT): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(text(c, label.uppercase(), 10f, MUTED, true))
        addView(text(c, value, 20f, color, true))
    }

    fun input(c: Context, hint: String, value: String = "", decimal: Boolean = true): EditText = EditText(c).apply {
        this.hint = hint
        setText(value)
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        textSize = 16f
        if (decimal) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        background = rounded(c, SURFACE2, 10, STROKE)
        setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10))
    }

    fun switch(c: Context, label: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch = Switch(c).apply {
        text = label
        textSize = 14f
        setTextColor(TEXT)
        isChecked = checked
        setPadding(0, dp(c, 8), 0, dp(c, 8))
        setOnCheckedChangeListener { _, b -> onChange(b) }
    }

    /** Horizontal progress bar built from two weighted views. */
    fun progress(c: Context, frac: Double, color: Int): View {
        val f = frac.coerceIn(0.0, 1.0).toFloat()
        return LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(c, SURFACE2, 4)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 8))
            if (f > 0f) addView(View(c).apply {
                background = rounded(c, color, 4)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, f)
            })
            if (f < 1f) addView(View(c).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - f) })
        }
    }

    // ------------------------------------------------------------------ bottom navigation
    private val TABS = listOf("Home", "Offers", "Money", "Insights", "Settings")

    fun navBar(a: Activity, selected: Int): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(SURFACE)
        for (i in TABS.indices) addView(TextView(a).apply {
            text = TABS[i]
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(a, 14), 0, dp(a, 14))
            setTextColor(if (i == selected) GREEN else MUTED)
            if (i == selected) setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { go(a, i, selected) }
        })
    }

    private fun go(a: Activity, tab: Int, current: Int) {
        if (tab == current) return
        val cls: Class<*> = when (tab) {
            0 -> MainActivity::class.java
            1 -> OffersActivity::class.java
            2 -> MoneyActivity::class.java
            3 -> InsightsActivity::class.java
            else -> SettingsActivity::class.java
        }
        val i = Intent(a, cls)
        if (tab == 0) {
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            a.startActivity(i)
        } else {
            a.startActivity(i)
            if (current != 0) a.finish()
        }
    }
}
