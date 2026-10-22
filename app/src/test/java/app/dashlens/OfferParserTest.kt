package app.dashlens

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser is the part most likely to break when DoorDash changes its screens, so these tests pin down
 * what it does with the offer screens we have actually seen. Add a new case here whenever a real screen
 * is mis-read.
 */
class OfferParserTest {
    private val now = LocalTime.of(9, 20)

    private fun standard() = listOf(
        "$5.00", "Guaranteed (incl. tips)", "1.4 mi", "Deliver by 9:44 AM",
        "Pickup", "Chipotle", "Customer drop-off", "Accept", "Decline"
    )

    @Test fun readsAStandardOffer() {
        val o = OfferParser.parse(standard(), now)
        assertNotNull(o)
        o!!
        assertEquals(5.0, o.pay, 0.001)
        assertEquals(1.4, o.miles, 0.001)
        assertEquals(0.0, o.shownMinutes, 0.001)
        assertEquals(24.0, o.windowMin, 0.001)
        assertEquals(1, o.pickups)
        assertEquals(listOf("Chipotle"), o.merchants)
        assertEquals("Chipotle", o.merchant)
        assertTrue(o.tipsIncluded)
        assertFalse(o.payPlus)
        assertEquals(0, o.items)
    }

    @Test fun ignoresScreensThatAreNotOffers() {
        assertNull(OfferParser.parse(listOf("$5.00", "1.4 mi", "Deliver by 9:44 AM"), now))
        assertNull(OfferParser.parse(listOf("Home", "Earnings", "Schedule"), now))
        assertNull(OfferParser.parse(emptyList(), now))
    }

    @Test fun needsBothAPayAndADistance() {
        assertNull(OfferParser.parse(listOf("$5.00", "Accept", "Decline"), now))
        assertNull(OfferParser.parse(listOf("1.4 mi", "Accept", "Decline"), now))
    }

    @Test fun gluesMoneyDrawnOneCharacterPerNode() {
        val split = listOf("$", "5", ".", "0", "0", "Guaranteed (incl. tips)", "1.4 mi", "Accept", "Decline")
        val o = OfferParser.parse(split, now)
        assertNotNull(o)
        assertEquals(5.0, o!!.pay, 0.001)
        assertEquals(listOf("$5.00"), OfferParser.normalize(listOf("$", "5", ".", "0", "0")))
    }

    @Test fun marksAPlusPayAsAMinimum() {
        val o = OfferParser.parse(listOf("$22.00+", "Guaranteed (incl. tips)", "6.1 mi", "Accept", "Decline"), now)
        assertNotNull(o)
        assertEquals(22.0, o!!.pay, 0.001)
        assertTrue(o.payPlus)
    }

    @Test fun readsTheItemCountOnShopAndDeliver() {
        val o = OfferParser.parse(listOf("$14.50", "Guaranteed (incl. tips)", "3.2 mi", "4 items", "Accept", "Decline"), now)
        assertNotNull(o)
        assertEquals(4, o!!.items)
    }

    @Test fun countsEveryPickup() {
        val o = OfferParser.parse(
            listOf("$11.00", "Guaranteed (incl. tips)", "5.0 mi", "Pickup", "Store A", "Pickup", "Store B", "Accept", "Decline"), now
        )
        assertNotNull(o)
        assertEquals(listOf("Store A", "Store B"), o!!.merchants)
        assertEquals(2, o.pickups)
    }

    @Test fun doesNotTreatDropOffAsAMerchant() {
        val o = OfferParser.parse(listOf("$6.00", "2.0 mi", "Pickup", "Customer drop-off", "Accept", "Decline"), now)
        assertNotNull(o)
        assertTrue(o!!.merchants.isEmpty())
    }

    @Test fun usesTheTimeDasherShowsWhenThereIsOne() {
        val o = OfferParser.parse(listOf("$8.00", "12 min (3.1 mi)", "Accept", "Decline"), now)
        assertNotNull(o)
        assertEquals(12.0, o!!.shownMinutes, 0.001)
        assertEquals(3.1, o.miles, 0.001)
        val long = OfferParser.parse(listOf("$20.00", "1 hr 5 min (10 mi)", "Accept", "Decline"), now)
        assertEquals(65.0, long!!.shownMinutes, 0.001)
    }

    @Test fun flagsOffersThatDoNotSayTipsAreIncluded() {
        val o = OfferParser.parse(listOf("$5.00", "1.4 mi", "Accept", "Decline"), now)
        assertNotNull(o)
        assertFalse(o!!.tipsIncluded)
    }

    @Test fun deliverByWindowHandlesAmPmAndMidnight() {
        assertEquals(24.0, OfferParser.windowMinutes(listOf("Deliver by 9:44 AM"), LocalTime.of(9, 20)), 0.001)
        assertEquals(40.0, OfferParser.windowMinutes(listOf("Deliver by 6:30 PM"), LocalTime.of(17, 50)), 0.001)
        assertEquals(20.0, OfferParser.windowMinutes(listOf("Deliver by 12:10 AM"), LocalTime.of(23, 50)), 0.001)
        assertEquals(15.0, OfferParser.windowMinutes(listOf("Deliver by 12:15 PM"), LocalTime.of(12, 0)), 0.001)
    }

    @Test fun deliverByWindowIgnoresImplausibleOrMissingTimes() {
        assertEquals(0.0, OfferParser.windowMinutes(listOf("Deliver by 3:00 PM"), LocalTime.of(9, 0)), 0.001)
        assertEquals(0.0, OfferParser.windowMinutes(listOf("Deliver by 13:99"), LocalTime.of(9, 0)), 0.001)
        assertEquals(0.0, OfferParser.windowMinutes(listOf("Accept", "Decline"), LocalTime.of(9, 0)), 0.001)
    }
}
