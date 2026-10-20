package app.dashlens

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A shareable picture of one graded offer: verdict, net $/hr and $/mi, and the line-by-line math. */
object ShareCard {
    private val INK = 0xFF1B1F23.toInt()
    private val GREY = 0xFF6B7280.toInt()
    private val BRAND = 0xFF2E7D32.toInt()
    private val TILE = 0xFFF3F4F6.toInt()

    private fun tv(c: Context, s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(c).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(0f, 1.1f)
    }

    private fun lp(c: Context, topDp: Int, weight: Float = 0f): LinearLayout.LayoutParams =
        (if (weight > 0f) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            .apply { topMargin = Ui.dp(c, topDp) }

    private fun tile(c: Context, label: String, value: String, sub: String, color: Int): LinearLayout =
        LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(TILE); cornerRadius = Ui.dp(c, 16).toFloat() }
            setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12))
            addView(tv(c, label, 12f, GREY, true))
            addView(tv(c, value, 26f, color, true))
            addView(tv(c, sub, 11f, GREY))
        }

    fun build(c: Context, o: Offer, r: Result): View {
        val accent = when (r.verdict) {
            Verdict.TAKE -> 0xFF2E7D32.toInt()
            Verdict.MAYBE -> 0xFFE09B00.toInt()
            Verdict.DECLINE -> 0xFFC62828.toInt()
        }
        val word = when (r.verdict) { Verdict.TAKE -> "TAKE IT"; Verdict.MAYBE -> "MAYBE"; Verdict.DECLINE -> "DECLINE" }
        val plus = if (o.payPlus) "+" else ""

        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(Ui.dp(c, 22), Ui.dp(c, 22), Ui.dp(c, 22), Ui.dp(c, 18))
        }
        root.addView(tv(c, "DASHLENS OFFER CHECK", 13f, BRAND, true).apply { letterSpacing = 0.08f })
        root.addView(tv(c, String.format(Locale.US, "$%.2f%s  ·  %.1f mi", o.pay, plus, o.miles), 30f, INK, true), lp(c, 8))
        if (o.merchant.isNotEmpty()) {
            val sub = o.merchant + (if (o.pickups > 1) "  +" + (o.pickups - 1) + " more" else "") +
                (if (o.items > 0) "  ·  " + o.items + " items" else "")
            root.addView(tv(c, sub, 18f, GREY), lp(c, 2))
        }

        val banner = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply { setColor(accent); cornerRadius = Ui.dp(c, 18).toFloat() }
            setPadding(Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14))
            addView(tv(c, word, 36f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
            addView(tv(c, r.floorLine, 14f, Color.WHITE).apply { gravity = Gravity.CENTER })
        }
        root.addView(banner, lp(c, 16))

        val tiles = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val gap = Ui.dp(c, 5)
        tiles.addView(
            tile(c, "NET \$/HR", Finance.money(r.netHr) + plus,
                Finance.money(r.netPay) + " net ÷ " + String.format(Locale.US, "%.0f", r.totalMin) + " min", INK),
            lp(c, 0, 1f).apply { rightMargin = gap }
        )
        tiles.addView(
            tile(c, "NET \$/MI", Finance.money(r.netPerOfferMile) + plus,
                Finance.money(r.netPay) + " net ÷ " + String.format(Locale.US, "%.1f", o.miles) + " offer mi", INK),
            lp(c, 0, 1f).apply { leftMargin = gap }
        )
        root.addView(tiles, lp(c, 12))

        for ((i, line) in r.mathLines.withIndex()) {
            val net = line.startsWith("Net pay")
            root.addView(tv(c, line, 13f, INK, net), lp(c, if (i == 0) 14 else 4))
        }
        root.addView(tv(c, r.assumed, 11f, GREY), lp(c, 12))
        root.addView(tv(c, "Graded by DashLens", 14f, BRAND, true).apply { gravity = Gravity.CENTER }, lp(c, 14))
        root.addView(tv(c, "Estimates from your own settings. Not financial or tax advice.", 10f, GREY).apply { gravity = Gravity.CENTER }, lp(c, 2))
        return root
    }

    fun render(c: Context, o: Offer, r: Result): Bitmap {
        val w = Ui.dp(c, 360)
        val v = build(c, o, r)
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        v.layout(0, 0, w, v.measuredHeight)
        val bmp = Bitmap.createBitmap(w, v.measuredHeight, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return bmp
    }

    /** Saves the card to Pictures/DashLens and opens the share sheet. */
    fun share(c: Context, o: Offer, r: Result) {
        try {
            val bmp = render(c, o, r)
            val name = "DashLens_offer_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/DashLens")
                }
                val uri = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
                if (uri == null) { Toast.makeText(c, "Could not save the card", Toast.LENGTH_LONG).show(); return }
                c.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                c.startActivity(Intent.createChooser(send, "Share offer card").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                val dir = c.getExternalFilesDir("DashLens")
                if (dir == null) { Toast.makeText(c, "Could not save the card", Toast.LENGTH_LONG).show(); return }
                dir.mkdirs()
                File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Toast.makeText(c, "Saved: " + dir.path + "/" + name, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(c, "Could not create the card", Toast.LENGTH_LONG).show()
        }
    }

    fun sampleOffer() = Offer(22.0, 4.7, 0.0, 0.0, 1, listOf("Dollar General"), false, 4, true)

    /** For testing: shares a card built from a made-up shop & deliver offer. */
    fun shareSample(c: Context) {
        val o = sampleOffer()
        share(c, o, Analyzer.analyze(c, o))
    }
}
