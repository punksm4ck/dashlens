package app.dashlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the grading model with the app's own default settings (read from [Prefs.FIELDS] and
 * [Prefs.SWITCHES], so they follow the real defaults). Hand-checked figures are in the comments.
 */
class AnalyzerTest {
    /** Settings lookup that mirrors Prefs.get: the default, with float noise rounded off. */
    private fun defaults(vararg ov: Pair<String, Double>): (String) -> Double {
        val o = ov.toMap()
        return { k -> o[k] ?: (Math.round(Prefs.FIELDS.first { it.key == k }.def.toDouble() * 10000.0) / 10000.0) }
    }

    private fun switches(vararg ov: Pair<String, Boolean>): (String, Boolean) -> Boolean {
        val o = ov.toMap()
        return { k, d -> o[k] ?: Prefs.SWITCHES.firstOrNull { it.key == k }?.def ?: d }
    }

    private fun offer(
        pay: Double, miles: Double, pickups: Int = 1, items: Int = 0, plus: Boolean = false,
        merchants: List<String> = emptyList(), shown: Double = 0.0
    ) = Offer(pay, miles, shown, 0.0, pickups, merchants, true, items, plus)

    private fun grade(
        o: Offer,
        p: (String) -> Double = defaults(),
        flag: (String, Boolean) -> Boolean = switches(),
        actual: Double? = null,
        m: MerchantStat? = null
    ) = Analyzer.grade(o, p, flag, actual, null, m)

    // ---- vehicle cost per mile

    @Test fun itemizedCostIsGasPlusWear() {
        // 3.87 / 25 = 0.1548, plus 0.18 wear
        assertEquals(0.3348, Analyzer.costPerMile(defaults(), switches(), null), 0.0001)
    }

    @Test fun flatCostWhenItemizingIsOff() {
        assertEquals(0.30, Analyzer.costPerMile(defaults(), switches("itemize_cost" to false), null), 0.0001)
    }

    @Test fun loggedActualCostWinsOverBoth() {
        assertEquals(0.52, Analyzer.costPerMile(defaults(), switches(), 0.52), 0.0001)
        assertEquals(0.52, Analyzer.costPerMile(defaults(), switches("itemize_cost" to false), 0.52), 0.0001)
    }

    @Test fun zeroMpgDoesNotDivideByZero() {
        // no gas term, wear only
        assertEquals(0.18, Analyzer.costPerMile(defaults("mpg" to 0.0), switches(), null), 0.0001)
    }

    // ---- the grade itself

    @Test fun aGoodOfferIsATake() {
        // 3 mi + 20% empty = 3.6 real mi. Cost 3.6 x 0.3348 = 1.20528, so net pay 8.79472.
        // Time: 3/22 h driving (8.18 min) + 7 wait + 3 drop-off = 18.18, plus 1.44 back = 19.62 min.
        val r = grade(offer(10.0, 3.0))
        assertEquals(3.6, r.realMiles, 0.0001)
        assertEquals(0.6, r.emptyMiles, 0.0001)
        assertEquals(1.20528, r.vehicleCost, 0.0001)
        assertEquals(8.79472, r.netPay, 0.0001)
        assertEquals(19.62, r.totalMin, 0.01)
        assertEquals(26.89, r.netHr, 0.05)
        assertEquals(10.0 / 3.6, r.perMile, 0.0001)
        assertEquals(Verdict.TAKE, r.verdict)
        assertTrue(r.take)
        assertTrue(r.hard.isEmpty())
    }

    @Test fun theVerdictStepsFromDeclineToMaybeToTake() {
        // Same 3 mi trip (19.62 min, 1.205 vehicle cost) at three pay levels, with the default $18/hr floor.
        // $5.00 -> about $11.6/hr (below 85% of the floor, so DECLINE)
        // $6.75 -> about $17.0/hr (within 85% of the floor, so MAYBE)
        // $7.50 -> about $19.2/hr and $2.08/real mi (TAKE)
        assertEquals(Verdict.DECLINE, grade(offer(5.0, 3.0)).verdict)
        val maybe = grade(offer(6.75, 3.0))
        assertEquals(17.0, maybe.netHr, 0.1)
        assertEquals(Verdict.MAYBE, maybe.verdict)
        assertFalse(maybe.take)
        assertEquals(Verdict.TAKE, grade(offer(7.5, 3.0)).verdict)
    }

    @Test fun aPoorOfferIsADecline() {
        val r = grade(offer(4.0, 5.0))
        assertEquals(Verdict.DECLINE, r.verdict)
        assertFalse(r.take)
    }

    @Test fun aHardFilterForcesDeclineEvenOnAGoodOffer() {
        val r = grade(offer(10.0, 3.0), p = defaults("max_mi" to 2.0))
        assertEquals(Verdict.DECLINE, r.verdict)
        assertEquals(1, r.hard.size)
        assertTrue(r.hard[0].contains("maximum"))
    }

    @Test fun minimumPayAndMaxStopsAreHardFilters() {
        val low = grade(offer(10.0, 3.0), p = defaults("min_pay" to 12.0))
        assertEquals(Verdict.DECLINE, low.verdict)
        val many = grade(offer(20.0, 6.0, pickups = 3), p = defaults("max_stops" to 2.0))
        assertEquals(Verdict.DECLINE, many.verdict)
        assertEquals(1, many.hard.size)
    }

    @Test fun flatRateMathUsesTheFlatCost() {
        // 3.6 real mi x 0.30 = 1.08, so net pay 8.92
        val r = grade(offer(10.0, 3.0), flag = switches("itemize_cost" to false))
        assertEquals(1.08, r.vehicleCost, 0.0001)
        assertEquals(8.92, r.netPay, 0.0001)
    }

    @Test fun loggedActualCostIsUsedAndSaidSo() {
        // 3.6 x 0.50 = 1.80
        val r = grade(offer(10.0, 3.0), actual = 0.50)
        assertEquals(1.80, r.vehicleCost, 0.0001)
        assertTrue(r.reasons.any { it.contains("actual") })
    }

    @Test fun shopAndDeliverReplacesTheStoreWaitWithShoppingTime() {
        // 4 items x 1.5 + 5 checkout = 11 min shopping and checkout instead of the 7 min wait
        val plain = grade(offer(12.0, 3.0))
        val shop = grade(offer(12.0, 3.0, items = 4))
        assertEquals(7.0, plain.waitMin, 0.0001)
        assertEquals(11.0, shop.waitMin, 0.0001)
        assertEquals(4.0, shop.tripMin - plain.tripMin, 0.0001)
    }

    @Test fun extraPickupsAddOverheadAndWait() {
        // two pickups: 2 x 7 wait + 1 x 5 extra
        val one = grade(offer(15.0, 5.0))
        val two = grade(offer(15.0, 5.0, pickups = 2))
        assertEquals(7.0 + 5.0, two.tripMin - one.tripMin, 0.0001)
    }

    @Test fun timeDasherShowsBeatsTheEstimate() {
        val r = grade(offer(10.0, 3.0, shown = 12.0))
        assertEquals("time shown by Dasher", r.source)
        // 12 drive + 7 wait + 3 drop-off
        assertEquals(22.0, r.tripMin, 0.0001)
    }

    @Test fun merchantHistoryOverridesTheModelAfterTwoCompletedOrders() {
        val hist = MerchantStat("Chipotle", seen = 5, accepted = 3, done = 3, avgPay = 9.0, avgMin = 20.0, avgMi = 3.0)
        val r = grade(offer(10.0, 3.0, merchants = listOf("Chipotle")), m = hist)
        // same miles as the history, so the ratio is 1 and the order takes the historical 20 min
        assertEquals(20.0, r.tripMin, 0.0001)
        assertEquals("your history at Chipotle", r.source)
        assertTrue(r.merchantLine.contains("3 completed orders"))
    }

    @Test fun oneCompletedOrderIsNotEnoughToOverride() {
        val hist = MerchantStat("Chipotle", seen = 2, accepted = 1, done = 1, avgPay = 9.0, avgMin = 40.0, avgMi = 3.0)
        val r = grade(offer(10.0, 3.0, merchants = listOf("Chipotle")), m = hist)
        assertFalse(r.source.contains("history"))
        assertTrue(r.merchantLine.contains("seen 2 times"))
    }

    @Test fun aPlusPayIsGradedAtTheMinimumAndSaidSo() {
        val r = grade(offer(22.0, 6.0, plus = true))
        assertTrue(r.payPlus)
        assertTrue(r.reasons.any { it.contains("+") })
        assertTrue(r.assumed.contains("minimum"))
    }

    @Test fun neverReturnsNanForEdgeSettings() {
        val r = grade(offer(10.0, 3.0), p = defaults("speed_mph" to 0.0, "empty_pct" to 0.0))
        assertFalse(r.netHr.isNaN())
        assertFalse(r.netHr.isInfinite())
        assertEquals(0.0, r.emptyMiles, 0.0001)
    }

    @Test fun theCardAlwaysExplainsItself() {
        val r = grade(offer(10.0, 3.0))
        assertTrue(r.mathLines.isNotEmpty())
        assertTrue(r.assumed.startsWith("Assumed:"))
        assertTrue(r.floorLine.contains("/hr floor"))
        assertTrue(r.reasons.isNotEmpty())
    }
}
