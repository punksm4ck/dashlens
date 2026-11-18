package app.dashlens

/** One line of text found in a screenshot, with where it sits (pixels from the top / left of the screen). */
class OcrLine(val text: String, val top: Int, val bottom: Int, val left: Int)

/**
 * Turns what text recognition found in a screenshot into the same list of text lines the accessibility read produces,
 * so [OfferParser] can read it. Pure logic (no Android classes), so it is unit-tested.
 */
object OcrText {
    // a big "$" is often read as "S" or "5" when it sits in front of the amount
    private val moneyMisreadRe = Regex("""^[S§]\s?(\d{1,3}\.\d{2})$""")

    /** Lines in reading order (top to bottom, then left to right), with OCR slips in money amounts repaired. */
    fun texts(lines: List<OcrLine>): List<String> =
        lines.sortedWith(compareBy({ it.top }, { it.left }))
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .map { t -> moneyMisreadRe.find(t)?.let { "$" + it.groupValues[1] } ?: t }

    /** Rows to keep the DashLens card off: the Accept / Decline / Match button text, padded to roughly the button's size. */
    fun bands(lines: List<OcrLine>, pad: Int): List<Band> =
        lines.filter { l ->
            val t = l.text.trim()
            t.equals("accept", true) || t.equals("decline", true) || t.equals("match", true)
        }.map { Band(it.top - pad, it.bottom + pad) }
}
