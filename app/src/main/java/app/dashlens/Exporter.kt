package app.dashlens

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import java.io.File

object Exporter {
    /** Saves into Downloads/DashLens (visible in the Files app). Returns a short location string, or null on failure. */
    fun save(c: Context, name: String, mime: String, content: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/DashLens")
                }
                val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return null
                c.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                "Downloads/DashLens/" + name
            } else {
                val dir = c.getExternalFilesDir("DashLens") ?: return null
                dir.mkdirs()
                File(dir, name).writeText(content)
                dir.path + "/" + name
            }
        } catch (e: Exception) {
            null
        }
    }

    fun saveReport(c: Context, r: Report, base: String): String? {
        val a = save(c, base + "_summary.csv", "text/csv", Reports.csv(r))
        save(c, base + "_triplog.csv", "text/csv", Reports.tripLog(c, r))
        save(c, base + "_summary.txt", "text/plain", Reports.text(c, r))
        save(c, base + "_offers.csv", "text/csv", Reports.offersCsv(c, r))
        save(c, base + "_ledger.csv", "text/csv", Reports.ledgerCsv(c, r))
        return a
    }

    fun share(c: Context, subject: String, text: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        c.startActivity(Intent.createChooser(i, "Share summary").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
