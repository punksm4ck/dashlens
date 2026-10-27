package app.dashlens

import kotlin.math.abs

/** A horizontal band of the screen that must stay uncovered, such as the Accept or Decline button. */
data class Band(val top: Int, val bottom: Int)

/** Works out where the offer card goes so it does not sit on top of the Dasher buttons. Pure maths, easy to test. */
object CardPlacement {

    /** True when a card of height [cardH] with its top edge at [y] would touch one of the [bands] (allowing [gap] pixels around each). */
    fun covers(y: Int, cardH: Int, bands: List<Band>, gap: Int): Boolean =
        bands.any { it.bottom + gap > y && it.top - gap < y + cardH }

    /**
     * Returns the card's top edge in screen pixels. It starts from [preferred]; if that would cover a band it moves the card to the
     * nearest spot just below or just above a band where nothing is covered. If there is no free spot it returns the preferred
     * position (clamped to the screen) so the caller can tell the user.
     */
    fun chooseTop(preferred: Int, cardH: Int, bands: List<Band>, minTop: Int, maxBottom: Int, gap: Int): Int {
        val hi = maxBottom - cardH
        if (hi < minTop) return minTop
        val pref = preferred.coerceIn(minTop, hi)
        if (!covers(pref, cardH, bands, gap)) return pref
        val candidates = ArrayList<Int>()
        for (b in bands) {
            candidates.add(b.bottom + gap)
            candidates.add(b.top - gap - cardH)
        }
        val free = candidates.filter { it in minTop..hi && !covers(it, cardH, bands, gap) }
        return free.minByOrNull { abs(it - pref) } ?: pref
    }
}
