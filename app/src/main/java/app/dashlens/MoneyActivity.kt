package app.dashlens

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Earnings, expenses (with receipt photos) and the tax / profit view. */
class MoneyActivity : BaseActivity() {
    override val tab = 2

    private var sub = 0                       // 0 earnings, 1 expenses, 2 tax
    private var anchor: LocalDate = LocalDate.now()

    private var pendingReceipt = ""
    private var pendingCamUri: Uri? = null
    private var receiptBtn: Button? = null

    private val REQ_CAM = 31
    private val REQ_PICK = 32
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val dayF = DateTimeFormatter.ofPattern("MMM d", Locale.US)






    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MoneyActivity, android.R.layout.simple_spinner_dropdown_item, items)
    }

    private fun monthStart() = anchor.withDayOfMonth(1)


    private fun button(label: String, weight: Float = 0f, onClick: () -> Unit): Button =
        Ui.button(this, label, 0, onClick).also { if (weight > 0f) Ui.weighted(it, weight) }

    private fun text(s: String, size: Float, mono: Boolean = false, bold: Boolean = false): TextView =
        Ui.text(this, s, size, if (size <= 12f && !mono) Ui.MUTED else Ui.TEXT, bold, mono)

    private fun decimal(hint: String): EditText = Ui.input(this, hint)

    override fun build(root: LinearLayout) {
        Ui.add(root, Ui.title(this, "Money"), 8)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val names = listOf("Earnings", "Expenses", "Tax")
        for (i in names.indices) tabs.addView(Ui.weighted(Ui.chip(this, names[i], i == sub) { sub = i; render() }))
        root.addView(tabs)

        val title = if (sub == 2) "Tax year " + anchor.year
        else monthStart().format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US))
        root.addView(Ui.navRow(this, title, { anchor = if (sub == 2) anchor.minusYears(1) else anchor.minusMonths(1); render() }, { anchor = if (sub == 2) anchor.plusYears(1) else anchor.plusMonths(1); render() }))

        when (sub) {
            0 -> renderEarnings()
            1 -> renderExpenses()
            else -> renderTax()
        }
    }

    // ------------------------------------------------------------------ earnings
    private fun renderEarnings() {
        val from = monthStart(); val to = from.plusMonths(1)
        val db = Db.get(this)
        val f = Reports.dayStart(from); val t = Reports.dayStart(to)
        val list = db.earnings(f, t)
        val acc = db.acceptedPay(f, t)
        val logged = list.sumOf { it.amount }
        root.addView(text("\nLogged by hand", 13f))
        root.addView(text(Finance.money(logged), 22f, bold = true))
        root.addView(text("\nAccepted offers (estimate from the overlay)", 13f))
        root.addView(text(Finance.money(acc.first) + "  (" + acc.second + " offers)", 18f, bold = true))
        root.addView(text(
            "Offer pay may leave out part of the tip. Log the actual payout or any extra (cash tips, bonuses, promos) here. " +
                "Turn on \"Income = only what I log by hand\" on the main screen if you log full payouts.", 11f))
        root.addView(button("+ Add earning") { addEarning() })
        for (e in list) {
            val line = Instant.ofEpochMilli(e.ts).atZone(zone).toLocalDate().format(dayF) + "   " +
                Finance.money(e.amount) + "   " + e.kind + " / " + e.platform + (if (e.note.isNotBlank()) "   " + e.note else "")
            root.addView(text(line, 13f).apply {
                setPadding(0, dp(8), 0, dp(8))
                setOnLongClickListener { confirmDelete("earning") { db.deleteEarning(e.id); render() }; true }
            })
        }
        if (list.isNotEmpty()) root.addView(text("Long-press an entry to delete it.", 11f))
    }

    private fun addEarning() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val amt = decimal("Amount \$")
        val kind = spinner(Finance.KINDS)
        val plat = EditText(this).apply { setText("DoorDash"); hint = "Platform" }
        val date = EditText(this).apply { setText(LocalDate.now().toString()); hint = "Date yyyy-mm-dd" }
        val note = EditText(this).apply { hint = "Note (optional)" }
        listOf<android.view.View>(amt, kind, plat, date, note).forEach { col.addView(it) }
        AlertDialog.Builder(this).setTitle("Add earning").setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Save") { _, _ ->
                val a = amt.text.toString().toDoubleOrNull()
                val d = runCatching { LocalDate.parse(date.text.toString().trim()) }.getOrNull()
                if (a == null || d == null) { Toast.makeText(this, "Need an amount and a date like 2026-10-19", Toast.LENGTH_LONG).show() }
                else {
                    Db.get(this).insertEarning(stamp(d), a, plat.text.toString().trim().ifEmpty { "DoorDash" },
                        Finance.KINDS[kind.selectedItemPosition], note.text.toString().trim())
                    render()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun stamp(d: LocalDate): Long =
        if (d == LocalDate.now()) System.currentTimeMillis() else d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    // ------------------------------------------------------------------ expenses
    private fun renderExpenses() {
        val from = monthStart(); val to = from.plusMonths(1)
        val db = Db.get(this)
        val f = Reports.dayStart(from); val t = Reports.dayStart(to)
        val list = db.expenses(f, t)
        root.addView(text("\nTotal this month", 13f))
        root.addView(text(Finance.money(list.sumOf { it.amount }), 22f, bold = true))
        for ((k, v) in db.expenseByCat(f, t)) root.addView(text(String.format(Locale.US, "%-12s %s", k, Finance.money(v)), 12f, mono = true))
        val ac = Finance.actualCostPerMile(this)
        root.addView(text(
            if (ac != null) "\nYour actual gas + maintenance cost: " + Finance.money(ac) + " per tracked mile (last 90 days). " +
                "Compare with the cost-per-mile setting."
            else "\nLog gas and maintenance and track 100+ miles to see your actual cost per mile.", 11f))
        root.addView(button("+ Add expense") { addExpense() })
        for (e in list) {
            val line = Instant.ofEpochMilli(e.ts).atZone(zone).toLocalDate().format(dayF) + "   " + Finance.money(e.amount) +
                "   " + e.category + (if (e.note.isNotBlank()) "   " + e.note else "") + (if (e.receipt.isNotBlank()) "   [receipt]" else "")
            root.addView(text(line, 13f).apply {
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { if (e.receipt.isNotBlank()) viewReceipt(e.receipt) }
                setOnLongClickListener { confirmDelete("expense") { db.deleteExpense(e.id); render() }; true }
            })
        }
        if (list.isNotEmpty()) root.addView(text("Tap an entry to open its receipt. Long-press to delete.", 11f))
    }

    private fun addExpense() {
        pendingReceipt = ""
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val amt = decimal("Amount \$")
        val cat = spinner(Finance.CATS)
        val date = EditText(this).apply { setText(LocalDate.now().toString()); hint = "Date yyyy-mm-dd" }
        val note = EditText(this).apply { hint = "Note (optional)" }
        val rb = Button(this).apply { text = "No receipt attached"; isAllCaps = false; isEnabled = false }
        receiptBtn = rb
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Take photo", 1f) { takePhoto() })
        row.addView(button("Pick photo", 1f) { pickPhoto() })
        listOf<android.view.View>(amt, cat, date, note, row, rb).forEach { col.addView(it) }
        AlertDialog.Builder(this).setTitle("Add expense").setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Save") { _, _ ->
                val a = amt.text.toString().toDoubleOrNull()
                val d = runCatching { LocalDate.parse(date.text.toString().trim()) }.getOrNull()
                if (a == null || d == null) { Toast.makeText(this, "Need an amount and a date like 2026-10-19", Toast.LENGTH_LONG).show() }
                else {
                    Db.get(this).insertExpense(stamp(d), a, Finance.CATS[cat.selectedItemPosition], note.text.toString().trim(), pendingReceipt)
                    pendingReceipt = ""
                    render()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun setReceipt(uri: String) {
        pendingReceipt = uri
        receiptBtn?.text = "Receipt attached"
    }

    private fun takePhoto() {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "receipt_" + System.currentTimeMillis() + ".jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DashLens")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
        if (uri == null) { Toast.makeText(this, "Could not prepare a photo file", Toast.LENGTH_LONG).show(); return }
        pendingCamUri = uri
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
        i.clipData = ClipData.newRawUri("", uri)
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivityForResult(i, REQ_CAM) }
        catch (e: Exception) { Toast.makeText(this, "No camera app found. Use Pick photo.", Toast.LENGTH_LONG).show() }
    }

    private fun pickPhoto() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        try { startActivityForResult(i, REQ_PICK) }
        catch (e: Exception) { Toast.makeText(this, "No photo picker found", Toast.LENGTH_LONG).show() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAM) {
            val u = pendingCamUri
            if (resultCode == RESULT_OK && u != null) setReceipt(u.toString())
            else if (u != null) { try { contentResolver.delete(u, null, null) } catch (_: Exception) {} }
            pendingCamUri = null
        } else if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val u = data?.data ?: return
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            setReceipt(u.toString())
        }
    }

    private fun viewReceipt(uri: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { Toast.makeText(this, "Could not open the receipt", Toast.LENGTH_SHORT).show() }
    }

    private fun confirmDelete(what: String, doIt: () -> Unit) {
        AlertDialog.Builder(this).setTitle("Delete this " + what + "?")
            .setPositiveButton("Delete") { _, _ -> doIt() }.setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ tax
    private fun renderTax() {
        val y = anchor.year
        val from = LocalDate.of(y, 1, 1); val to = LocalDate.of(y + 1, 1, 1)
        MoneyCard.add(this, root, from, to)
        root.addView(text("Quarterly estimated-tax periods", 14f, bold = true).apply { setPadding(0, dp(12), 0, 0) })
        root.addView(text(Finance.quartersText(this, y), 11f, mono = true))
        root.addView(text(
            "Due dates move to the next business day when they land on a weekend; holidays are not accounted for. " +
                "Method in use: " + (if (Prefs.getBool(this, "method_actual", false)) "actual vehicle expenses" else "standard mileage rate") +
                ", miles basis: " + (if (Prefs.getBool(this, "basis_online", false)) "all online miles" else "on-order miles") +
                ". Change these on the main screen. Under the standard rate, gas, maintenance, car washes and insurance are not deducted again; tolls and parking still are.",
            11f))

        root.addView(text("Odometer log for " + y, 16f, bold = true).apply { setPadding(0, dp(16), 0, 0) })
        val s = EditText(this).apply {
            hint = "Odometer on Jan 1 " + y
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(Prefs.getString(this@MoneyActivity, "odo_start_$y"))
        }
        val e = EditText(this).apply {
            hint = "Odometer on Dec 31 " + y
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(Prefs.getString(this@MoneyActivity, "odo_end_$y"))
        }
        root.addView(s); root.addView(e)
        root.addView(button("Save odometer") {
            Prefs.putString(this, "odo_start_$y", s.text.toString().trim())
            Prefs.putString(this, "odo_end_$y", e.text.toString().trim())
            render()
        })
        val os = s.text.toString().toDoubleOrNull(); val oe = e.text.toString().toDoubleOrNull()
        if (os != null && oe != null && oe > os) {
            val total = oe - os
            root.addView(text(String.format(Locale.US, "Total miles driven: %.0f. Business share from tracked miles: %.1f%%.", total, Finance.bizPct(this, y)), 12f))
        } else root.addView(text("Enter both readings to get your business-use % from the odometer (the tax form asks for total miles).", 11f))
    }
}
