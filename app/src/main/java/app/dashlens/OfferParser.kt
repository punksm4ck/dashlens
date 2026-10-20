package app.dashlens

import java.time.Duration
import java.time.LocalTime

/**
 * What the Dasher offer screen actually shows (verified from real screenshots): pay "$5.00" + "Guaranteed (incl. tips)",
 * distance "1.4 mi", "Deliver by 9:44 AM", a "Pickup" label followed by the merchant name, and Accept / Decline.
 * It does NOT show an estimated trip time, so [shownMinutes] is 0 unless some Dasher version prints one.
 */
data class Offer(
    val pay: Double,
    val miles: Double,
    val shownMinutes: Double,      // minutes printed on the offer, 0 when Dasher shows none
    val windowMin: Double,         // minutes from now until "Deliver by", 0 when not shown
    val pickups: Int,
    val merchants: List<String>,
    val tipsIncluded: Boolean,
    val items: Int = 0,            // shop & deliver item count ("4 items"), 0 when not shown
    val payPlus: Boolean = false   // pay shown as "$22.00+": a minimum, the real pay can be higher
) {
    val merchant: String get() = merchants.firstOrNull() ?: ""
}

object OfferParser {
    private val payRe = Regex("""\$\s?(\d{1,3}(?:\.\d{1,2})?)""")
    private val miRe = Regex("""(\d+(?:\.\d+)?)\s*mi\b""", RegexOption.IGNORE_CASE)
    private val hrRe = Regex("""(\d+)\s*h(?:r|our)s?\b""", RegexOption.IGNORE_CASE)
    private val minRe = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
    private val shownMinRe = Regex("""^\D{0,3}(\d+)\s*min""", RegexOption.IGNORE_CASE)
    private val countRe = Regex("""(\d+)\s*(?:pickups?|stops?)\b""", RegexOption.IGNORE_CASE)
    private val itemsRe = Regex("""(\d+)\s+items?\b""", RegexOption.IGNORE_CASE)
    private val plusRe = Regex("""\$\s?\d{1,3}(?:\.\d{1,2})?\s*\+""")
    private val deliverByRe = Regex("""deliver\s+by\s+(\d{1,2}):(\d{2})\s*([ap])?\.?m?""", RegexOption.IGNORE_CASE)
    private val pickupLabelRe = Regex("""^pick-?up(\s+\d+)?$""", RegexOption.IGNORE_CASE)
    private val notMerchantRe = Regex("""^(customer\s+drop-?off|drop-?off.*|deliver.*|accept.*|decline.*)$""", RegexOption.IGNORE_CASE)

    private fun minutesIn(s: String): Double? {
        val h = hrRe.find(s)?.groupValues?.get(1)?.toDouble() ?: 0.0
        val m = minRe.find(s)?.groupValues?.get(1)?.toDouble()
        if (m == null && h == 0.0) return null
        return h * 60 + (m ?: 0.0)
    }

    fun looksLikeOffer(texts: List<String>): Boolean = texts.any {
        it.startsWith("accept", true) || it.startsWith("decline", true)
    }

    /** Dasher sometimes draws money one character per node ("$", "5", ".", "0", "0"): glue those back together. */
    fun normalize(texts: List<String>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < texts.size) {
            val t = texts[i]
            if (t == "$") {
                val sb = StringBuilder("$")
                var j = i + 1
                while (j < texts.size && j < i + 9 && (texts[j] == "." || texts[j].all { it.isDigit() })) { sb.append(texts[j]); j++ }
                if (sb.length > 1) { out.add(sb.toString()); i = j; continue }
            }
            out.add(t.replace('\u2019', '\''))
            i++
        }
        return out
    }

    /** Minutes from [now] until a "Deliver by 9:44 AM" line, or 0 when absent / implausible. */
    fun windowMinutes(texts: List<String>, now: LocalTime): Double {
        for (t in texts) {
            val m = deliverByRe.find(t) ?: continue
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            val ap = m.groupValues[3].lowercase()
            if (h > 23 || min > 59) continue
            if (ap == "p" && h < 12) h += 12
            if (ap == "a" && h == 12) h = 0
            var d = Duration.between(now, LocalTime.of(h, min)).seconds / 60.0
            if (d < -1.0) d += 24 * 60.0
            return if (d in 0.0..240.0) Math.max(d, 1.0) else 0.0
        }
        return 0.0
    }

    /** Returns null unless the text looks like a DoorDash offer screen with a pay and a distance. */
    fun parse(raw: List<String>, now: LocalTime = LocalTime.now()): Offer? {
        if (!looksLikeOffer(raw)) return null
        val t = normalize(raw)
        val all = t.joinToString(" | ")

        // pay: the amount sitting on / next to the "Guaranteed (incl. tips)" line, else the first dollar amount
        val money = t.indices.filter { payRe.containsMatchIn(t[it]) }
        if (money.isEmpty()) return null
        val pick = money.firstOrNull { i ->
            t[i].contains("guarantee", true) || t[i].contains("incl", true) ||
                t.getOrNull(i + 1)?.contains("guarantee", true) == true
        } ?: money.first()
        val pay = payRe.find(t[pick])?.groupValues?.get(1)?.toDoubleOrNull() ?: return null

        val combo = t.firstOrNull { miRe.containsMatchIn(it) && minutesIn(it) != null }
        val miles = miRe.find(combo ?: t.firstOrNull { miRe.containsMatchIn(it) } ?: return null)
            ?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val shown = if (combo != null) minutesIn(combo) ?: 0.0
        else t.firstNotNullOfOrNull { shownMinRe.find(it)?.groupValues?.get(1)?.toDoubleOrNull() } ?: 0.0

        val merchants = ArrayList<String>()
        for (i in t.indices) if (pickupLabelRe.matches(t[i].trim())) {
            val name = t.getOrNull(i + 1)?.trim().orEmpty()
            if (name.isNotEmpty() && !notMerchantRe.matches(name) && !pickupLabelRe.matches(name)) merchants.add(name)
        }
        val counted = countRe.find(all)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val pickups = maxOf(merchants.size, counted, 1).coerceIn(1, 6)

        if (pay <= 0.0 || miles <= 0.0) return null
        val tips = all.contains("incl. tip", true) || all.contains("guaranteed", true)
        val plus = plusRe.containsMatchIn(t[pick]) || t.getOrNull(pick + 1)?.trim() == "+"
        val items = (itemsRe.find(all)?.groupValues?.get(1)?.toIntOrNull() ?: 0).let { if (it in 1..200) it else 0 }
        return Offer(pay, miles, shown, windowMinutes(t, now), pickups, merchants, tips, items, plus)
    }
}
