package app.dashlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Screen is 2400 px tall in these tests; the card is 500 px tall; gap 8 px. */
class CardPlacementTest {
    private val minTop = 60
    private val maxBottom = 2376
    private val card = 500
    private val gap = 8

    private fun pick(pref: Int, vararg bands: Band) = CardPlacement.chooseTop(pref, card, bands.toList(), minTop, maxBottom, gap)

    @Test fun noButtonsKeepsThePreferredPosition() = assertEquals(100, pick(100))

    @Test fun declineAtTheTopPushesTheCardBelowIt() {
        // Decline sits at 40..140; the card wanted to start at 100 and would cover it.
        assertEquals(148, pick(100, Band(40, 140)))
    }

    @Test fun acceptAtTheBottomPushesTheCardAboveIt() {
        // Accept at 2100..2250; the card wanted 1780..2280 and would cover it. Above it: 2100 - 8 - 500 = 1592.
        assertEquals(1592, pick(1780, Band(2100, 2250)))
    }

    @Test fun buttonsAtBothEndsUseTheSpaceBetween() {
        val y = pick(100, Band(40, 140), Band(2100, 2250))
        assertEquals(148, y)
        assertFalse(CardPlacement.covers(y, card, listOf(Band(40, 140), Band(2100, 2250)), gap))
    }

    @Test fun anAlreadyClearPositionIsNotMoved() = assertEquals(900, pick(900, Band(40, 140), Band(2100, 2250)))

    @Test fun picksTheNearestFreeSpot() {
        // Button in the middle: both above (1000 - 8 - 500 = 492) and below (1100 + 8 = 1108) are free; preferred 700 is closer to 492.
        assertEquals(492, pick(700, Band(1000, 1100)))
    }

    @Test fun noRoomFallsBackToThePreferredPosition() {
        val big = Band(100, 2300)
        assertEquals(300, pick(300, big))
        assertTrue(CardPlacement.covers(300, card, listOf(big), gap))
    }

    @Test fun preferredIsClampedToTheScreen() {
        assertEquals(60, pick(-50))
        assertEquals(maxBottom - card, pick(5000))
    }
}
