package app.dashlens

import java.io.File
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uber Driver support. The screen text below is copied from real Uber Driver screenshots, so a change that
 * breaks reading them fails here. Add a case whenever a real Uber screen is mis-read.
 */
class UberSupportTest {
    private val now = LocalTime.of(10, 13)

    /** The delivery offer card: "Delivery / Exclusive / $4.32 / Guaranteed (incl. tip) / 22 min (8.1 mi) total / merchant". */
    private fun uberOffer() = listOf(
        "Delivery", "Exclusive", "$4.32", "Guaranteed (incl. tip)", "22 min (8.1 mi) total",
        "Get Hooked, A Coffee & Bagel Company", "Bellflower Ct & Stearns St, Simi Valley", "Customer verification", "Accept"
    )

    private fun defaults(vararg ov: Pair<String, Double>): (String) -> Double {
        val o = ov.toMap()
        return { k -> o[k] ?: (Math.round(Prefs.FIELDS.first { it.key == k }.def.toDouble() * 10000.0) / 10000.0) }
    }

    private val flags: (String, Boolean) -> Boolean = { k, d -> Prefs.SWITCHES.firstOrNull { it.key == k }?.def ?: d }

    private fun classifyUber(texts: List<String>, offer: Boolean = false): DState? {
        val d = StateDetector.defaults(Platform.UBER)
        return StateDetector.classify(texts, offer, StateDetector.words(d.first), StateDetector.words(d.second), StateDetector.words(d.third))
    }

    // ---- reading the offer

    @Test fun readsTheUberDeliveryOffer() {
        val o = OfferParser.parse(uberOffer(), now, Platform.UBER)
        assertNotNull(o)
        o!!
        assertEquals(4.32, o.pay, 0.001)
        assertEquals(8.1, o.miles, 0.001)
        assertEquals(22.0, o.shownMinutes, 0.001)
        assertEquals(listOf("Get Hooked, A Coffee & Bagel Company"), o.merchants)
        assertEquals(1, o.pickups)
        assertTrue(o.tipsIncluded)
        assertFalse(o.payPlus)
        assertEquals(0.0, o.windowMin, 0.001)
        assertEquals(Platform.UBER, o.platform)
    }

    @Test fun doorDashOffersStayDoorDash() {
        val o = OfferParser.parse(listOf("$5.00", "Guaranteed (incl. tips)", "1.4 mi", "Pickup", "Chipotle", "Accept", "Decline"), now)
        assertEquals(Platform.DOORDASH, o!!.platform)
        assertEquals(listOf("Chipotle"), o.merchants)
    }

    /** An Uber "Package" offer: the pickup is an address, not a store, and the trip is long. */
    private fun uberPackageOffer() = listOf(
        "Package", "Exclusive", "$29.25", "4.88", "Verified", "Guaranteed (incl. tip)", "1 hr 39 min (41.8 mi) total",
        "1480 Blackstock Ave, Simi Valley, CA 93063-3114, USA", "Flight Ave & W 74th St, Los Angeles", "Accept"
    )

    @Test fun readsAnUberPackageOfferAndDoesNotTreatTheAddressAsAMerchant() {
        val o = OfferParser.parse(uberPackageOffer(), now, Platform.UBER)
        assertNotNull(o)
        o!!
        assertEquals(29.25, o.pay, 0.001)
        assertEquals(41.8, o.miles, 0.001)
        assertEquals(99.0, o.shownMinutes, 0.001)
        assertTrue(o.merchants.isEmpty())
        assertTrue(o.tipsIncluded)
    }

    @Test fun aLongPackageOfferIsGradedOnItsRealCost() {
        // 41.8 mi + 20% empty = 50.16 real mi, vehicle cost 16.79, net 12.46 over 99 + 7 + 3 + 20.06 = 129.06 min: about $5.79/hr
        val o = OfferParser.parse(uberPackageOffer(), now, Platform.UBER)!!
        val r = Analyzer.grade(o, defaults(), flags, null, null, null)
        assertEquals(5.79, r.netHr, 0.02)
        assertEquals(29.25 / 50.16, r.perMile, 0.0001)
        assertEquals(Verdict.DECLINE, r.verdict)
    }

    @Test fun merchantsThatStartWithADigitAreStillMerchants() {
        for (name in listOf("7-Eleven", "99 Ranch Market", "85\u00B0C Bakery Cafe", "5 Guys")) {
            val o = OfferParser.parse(listOf("$6.10", "Guaranteed (incl. tip)", "15 min (5.0 mi) total", name, "1st St, Simi Valley", "Accept"), now, Platform.UBER)
            assertEquals(listOf(name), o!!.merchants)
        }
    }

    @Test fun skipsTagsToFindTheUberMerchant() {
        val o = OfferParser.parse(
            listOf("$6.10", "Guaranteed (incl. tip)", "15 min (5.0 mi) total", "Delivery", "Taco Bell", "1st St, Simi Valley", "Accept"),
            now, Platform.UBER
        )
        assertEquals(listOf("Taco Bell"), o!!.merchants)
    }

    @Test fun anUberOfferWithoutAMerchantLineIsStillGraded() {
        val o = OfferParser.parse(listOf("$6.10", "Guaranteed (incl. tip)", "15 min (5.0 mi) total", "Accept"), now, Platform.UBER)
        assertNotNull(o)
        assertTrue(o!!.merchants.isEmpty())
        assertEquals(5.0, o.miles, 0.001)
    }

    @Test fun uberTotalLineWinsOverSeparateLegs() {
        val o = OfferParser.parse(
            listOf("$9.00", "Guaranteed (incl. tip)", "4 min (1.1 mi) away", "20 min (7.0 mi) total", "Panda Express", "Accept"),
            now, Platform.UBER
        )
        assertEquals(7.0, o!!.miles, 0.001)
        assertEquals(20.0, o.shownMinutes, 0.001)
    }

    @Test fun uberLegsWithoutATotalAreNotGuessed() {
        // a layout we have not seen (for example a ride request): better no card than a card graded on half the trip
        val o = OfferParser.parse(listOf("$7.50", "4 min (1.1 mi) away", "12 min (4.5 mi)", "Accept"), now, Platform.UBER)
        assertNull(o)
    }

    @Test fun aSingleUberLegWithoutTheWordTotalIsStillRead() {
        val o = OfferParser.parse(listOf("$7.50", "Guaranteed (incl. tip)", "12 min (4.5 mi)", "Accept"), now, Platform.UBER)
        assertEquals(4.5, o!!.miles, 0.001)
    }

    @Test fun uberScreensWithoutAnAcceptButtonAreNotOffers() {
        assertNull(OfferParser.parse(listOf("$21.07", "TODAY", "Finding trips"), now, Platform.UBER))
        assertNull(OfferParser.parse(listOf("You've arrived", "The Home Depot", "Start shopping"), now, Platform.UBER))
    }

    // ---- grading it

    @Test fun theScreenshotOfferIsADecline() {
        // 8.1 mi + 20% empty = 9.72 real mi. Vehicle cost 9.72 x 0.3348 = 3.2543, so net pay 1.0657.
        // Time: 22 shown + 7 wait + 3 drop-off + 3.888 back = 35.888 min, so about $1.78/hr and $0.44 per real mile.
        val o = OfferParser.parse(uberOffer(), now, Platform.UBER)!!
        val r = Analyzer.grade(o, defaults(), flags, null, null, null)
        assertEquals(9.72, r.realMiles, 0.0001)
        assertEquals(1.0657, r.netPay, 0.001)
        assertEquals(35.888, r.totalMin, 0.001)
        assertEquals(1.78, r.netHr, 0.02)
        assertEquals(4.32 / 9.72, r.perMile, 0.0001)
        assertEquals(Verdict.DECLINE, r.verdict)
        assertEquals("time shown by Uber", r.source)
    }

    @Test fun aGoodUberOfferIsATake() {
        // 6 mi, 18 min shown, $14: real 7.2 mi, net 11.589, 30.88 min, so about $22.5/hr and $1.94 per real mile
        val o = OfferParser.parse(listOf("$14.00", "Guaranteed (incl. tip)", "18 min (6.0 mi) total", "Wingstop", "Accept"), now, Platform.UBER)!!
        val r = Analyzer.grade(o, defaults(), flags, null, null, null)
        assertEquals(22.5, r.netHr, 0.05)
        assertEquals(Verdict.TAKE, r.verdict)
    }

    @Test fun theTipsNoteNamesTheRightApp() {
        val uber = Offer(10.0, 3.0, 0.0, 0.0, 1, emptyList(), false, 0, false, Platform.UBER)
        val dd = Offer(10.0, 3.0, 0.0, 0.0, 1, emptyList(), false)
        assertTrue(Analyzer.grade(uber, defaults(), flags, null, null, null).reasons.any { it.startsWith("Uber did not say") })
        assertTrue(Analyzer.grade(dd, defaults(), flags, null, null, null).reasons.any { it.startsWith("Dasher did not say") })
    }

    // ---- online / on order / offline on Uber screens

    @Test fun findingTripsIsOnline() {
        assertEquals(DState.ONLINE, classifyUber(listOf("$21.07", "TODAY", "Finding trips", "Rancho Simi Community Park")))
        assertEquals(DState.ONLINE, classifyUber(listOf("$8.01", "Finding trips")))
    }

    @Test fun theNavigationScreenIsOnOrder() {
        assertEquals(DState.ORDER, classifyUber(listOf("Erringer Rd", "150 ft", "40", "LIMIT", "E Los Angeles Ave", "5 min", "1.3 mi", "Expected by 10:29 AM")))
    }

    @Test fun theShoppingArrivalScreenIsOnOrder() {
        val arrived = listOf("You've arrived", "The Home Depot", "575 Cochran St, Simi Valley, CA 93065", "Note from business",
            "Shop 1 order", "Tyler H.", "66A0C · 1 item (4 ct)", "Help and support", "Start shopping")
        assertEquals(DState.ORDER, classifyUber(arrived))
        assertEquals(DState.ORDER, classifyUber(listOf("You’ve arrived", "Start shopping")))   // curly apostrophe
    }

    @Test fun anOfferIsOnlineAndUnknownScreensAreIgnored() {
        assertEquals(DState.ONLINE, classifyUber(uberOffer(), offer = true))
        assertNull(classifyUber(listOf("Earnings", "Inbox", "Account")))
    }

    @Test fun youAreOfflineIsOffline() {
        assertEquals(DState.OFFLINE, classifyUber(listOf("You’re offline")))
    }

    @Test fun uberAndDoorDashKeepSeparateWordLists() {
        val dd = StateDetector.defaults(Platform.DOORDASH)
        val ub = StateDetector.defaults(Platform.UBER)
        assertEquals(StateDetector.DEF_ORDER, dd.first)
        assertEquals(StateDetector.UBER_DEF_ORDER, ub.first)
        assertEquals(Triple("kw_order", "kw_online", "kw_offline"), StateDetector.keys(Platform.DOORDASH))
        assertEquals(Triple("kw_uber_order", "kw_uber_online", "kw_uber_offline"), StateDetector.keys(Platform.UBER))
    }

    // ---- merging the two apps

    private val t0 = 1_000_000_000L
    private val min = 60_000L

    @Test fun aSingleAppAlwaysWins() {
        assertEquals(DState.ONLINE, StateMerge.combine(mapOf(Platform.DOORDASH to Seen(DState.ONLINE, t0)), t0))
        assertEquals(DState.OFFLINE, StateMerge.combine(mapOf(Platform.UBER to Seen(DState.OFFLINE, t0)), t0))
        assertEquals(DState.ORDER, StateMerge.combine(mapOf(Platform.DOORDASH to Seen(DState.ORDER, t0)), t0))
    }

    @Test fun lookingAtUberDoesNotEndADoorDashOrder() {
        val seen = mapOf(Platform.DOORDASH to Seen(DState.ORDER, t0), Platform.UBER to Seen(DState.ONLINE, t0 + 5 * min))
        assertEquals(DState.ORDER, StateMerge.combine(seen, t0 + 5 * min))
    }

    @Test fun finishingTheOrderInTheSameAppEndsIt() {
        val seen = mapOf(Platform.DOORDASH to Seen(DState.ONLINE, t0 + 20 * min), Platform.UBER to Seen(DState.ONLINE, t0 + 5 * min))
        assertEquals(DState.ONLINE, StateMerge.combine(seen, t0 + 20 * min))
    }

    @Test fun onlineInOneAppBeatsOfflineInTheOther() {
        val seen = mapOf(Platform.DOORDASH to Seen(DState.OFFLINE, t0), Platform.UBER to Seen(DState.ONLINE, t0 + min))
        assertEquals(DState.ONLINE, StateMerge.combine(seen, t0 + min))
    }

    @Test fun anOldOrderStopsCountingAfter45Minutes() {
        val seen = mapOf(Platform.DOORDASH to Seen(DState.ORDER, t0), Platform.UBER to Seen(DState.ONLINE, t0 + 44 * min))
        assertEquals(DState.ORDER, StateMerge.combine(seen, t0 + 45 * min))
        assertEquals(DState.ONLINE, StateMerge.combine(seen, t0 + 46 * min))
    }

    @Test fun anOldOnlineStopsCountingAfter30Minutes() {
        val seen = mapOf(Platform.DOORDASH to Seen(DState.ONLINE, t0), Platform.UBER to Seen(DState.OFFLINE, t0 + 29 * min))
        assertEquals(DState.ONLINE, StateMerge.combine(seen, t0 + 30 * min))
        assertEquals(DState.OFFLINE, StateMerge.combine(seen, t0 + 31 * min))
    }

    @Test fun nothingRecentMeansNoChange() {
        assertNull(StateMerge.combine(emptyMap(), t0))
        assertNull(StateMerge.combine(mapOf(Platform.UBER to Seen(DState.ORDER, t0)), t0 + 46 * min))
    }

    // ---- Package offer with a "Match" button (real phone screenshot, floating over Messages)

    private val matchOffer = listOf("Package (5)", "Close", "\$37.61", "Guaranteed (incl. tip)", "1 hr 37 min (30.3 mi) total",
        "LOWE'S OF SIMI VALLEY, CA", "Dorado Ct & E Avenida de Los Arboles, Thousand Oaks", "Customer verification", "Match")

    @Test fun matchButtonCountsAsAnOfferScreen() {
        assertTrue(OfferParser.looksLikeOffer(matchOffer))
        assertFalse(OfferParser.looksLikeOffer(listOf("Home", "\$18.09", "Finding trips")))
    }

    @Test fun readsAMatchPackageOffer() {
        val o = OfferParser.parse(matchOffer, platform = Platform.UBER)!!
        assertEquals(37.61, o.pay, 0.001)
        assertEquals(30.3, o.miles, 0.001)
        assertEquals("LOWE'S OF SIMI VALLEY, CA", o.merchant)
    }

    @Test fun youreOnlineMeansUberOnline() {
        val d = StateDetector.words(StateDetector.UBER_DEF_ONLINE)
        assertEquals(DState.ONLINE, StateDetector.classify(listOf("Home", "You\u2019re online"), false, emptyList(), d, emptyList()))
    }

    // ---- auto start of a shift

    private val MIN = 60_000L
    private fun start(enabled: Boolean = true, running: Boolean = false, seen: DState = DState.ONLINE, now: Long = 10_000 * MIN, stop: Long = 0L, tried: Long = 0L) =
        AutoStart.shouldStart(enabled, running, seen, now, stop, tried)

    @Test fun autoStartIsOffByDefault() {
        assertFalse(Prefs.SWITCHES.first { it.key == "auto_start" }.def)
    }

    @Test fun startsWhenYouGoOnlineOrAreOnAnOrder() {
        assertTrue(start())
        assertTrue(start(seen = DState.ORDER))
    }

    @Test fun neverStartsWhenOffRunningOrSwitchedOff() {
        assertFalse(start(seen = DState.OFFLINE))
        assertFalse(start(running = true))
        assertFalse(start(enabled = false))
    }

    @Test fun waitsTenMinutesAfterAManualStop() {
        val now = 10_000 * MIN
        assertFalse(start(now = now, stop = now - 9 * MIN))
        assertTrue(start(now = now, stop = now - 11 * MIN))
    }

    @Test fun triesAtMostOnceAMinute() {
        val now = 10_000 * MIN
        assertFalse(start(now = now, tried = now - 30_000L))
        assertTrue(start(now = now, tried = now - 61_000L))
    }

    // ---- offer detection must not be fooled by other screens, and must not need a button

    @Test fun ratingsScreenIsNotAnOffer() {
        val ratings = listOf("Platinum", "Overall rating", "88", "Acceptance rate", "84%", "26 of 30 points", "Last 30-day orders", "59")
        assertFalse(OfferParser.looksLikeOffer(ratings))
        assertNull(OfferParser.parse(ratings))
    }

    @Test fun realAcceptAndDeclineButtonsStillCount() {
        assertTrue(OfferParser.looksLikeOffer(listOf("\$6.00", "Decline")))
        assertTrue(OfferParser.looksLikeOffer(listOf("\$6.00", "Accept")))
    }

    @Test fun uberOfferIsRecognisedWithoutAnyButton() {
        val noButton = matchOffer.filter { !it.equals("Match", true) }
        assertFalse(OfferParser.looksLikeOffer(noButton))                     // DoorDash rules: needs a button
        assertTrue(OfferParser.looksLikeOffer(noButton, Platform.UBER))       // Uber: pay + "... total" line is enough
        val o = OfferParser.parse(noButton, platform = Platform.UBER)!!
        assertEquals(37.61, o.pay, 0.001)
        assertEquals(30.3, o.miles, 0.001)
    }

    @Test fun uberHomeAndArrivalScreensAreNotOffers() {
        val home = listOf("Home", "\$18.09", "Earnings Trends", "Finding trips")
        val arrived = listOf("You've arrived", "vons:2047", "5805 E Los Angeles Ave, Simi Valley, CA 93063-4256", "Scan to verify", "10 packages (2 stops)", "Complete pickup")
        assertFalse(OfferParser.looksLikeOffer(home, Platform.UBER))
        assertFalse(OfferParser.looksLikeOffer(arrived, Platform.UBER))
        assertFalse(OfferParser.uberMaybeOffer(home))
        assertFalse(OfferParser.uberMaybeOffer(arrived))
    }

    // ---- real offer from the 2026-11-17 phone test (screenshot) and the "Opportunities" list that was captured instead

    private val mcdOffer = listOf("Delivery", "Exclusive", "\$4.02", "Guaranteed (incl. tip)", "19 min (3.2 mi) total",
        "McDonald's\u00ae (1495 E LOS ANGELES AVE)", "Cutler St & Whitcomb Ave, Simi Valley", "Accept")

    @Test fun readsTheMcDonaldsOfferFromTheScreenshot() {
        val o = OfferParser.parse(mcdOffer, platform = Platform.UBER)!!
        assertEquals(4.02, o.pay, 0.001)
        assertEquals(3.2, o.miles, 0.001)
        assertEquals("McDonald's\u00ae (1495 E LOS ANGELES AVE)", o.merchant)
    }

    @Test fun opportunitiesListIsNotAnOfferEvenThoughItHasAcceptButtons() {
        val list = listOf("You're Online", "Opportunities", "New", "Tuesday, Nov 17", "6:45 PM", "New", "\$10.81, Delivery",
            "vons:2092. 2048 E Avenida de los Arboles, Thousand Oaks, CA 91362, USA", "Accept", "Accept", "Map", "Home", "Earnings")
        assertFalse(OfferParser.looksLikeOffer(list, Platform.UBER))
    }

    // ---- reading the offer card from a screenshot (Uber hides it from the accessibility read)

    private fun ocr(vararg rows: Triple<String, Int, Int>) = rows.map { OcrLine(it.first, it.second, it.second + 60, it.third) }

    @Test fun readsTheOfferFromScreenshotTextWithMapLabelsAround() {
        // OCR returns blocks in any order and includes the map labels above the card (positions from the 18:19 screenshot)
        val lines = ocr(
            Triple("Accept", 2070, 540), Triple("Customer verification", 1900, 215), Triple("Country Club Dr, Simi Valley", 1760, 180),
            Triple("McDonald's", 1670, 180), Triple("21 min (4.2 mi) total", 1535, 180), Triple("Guaranteed (incl. tip)", 1380, 115),
            Triple("S7.62", 1230, 95), Triple("Exclusive", 1115, 445), Triple("Delivery", 1115, 180),
            Triple("ROYAL AVE", 540, 530), Triple("Iceoplex Simi", 205, 40), Triple("Valley", 255, 110))
        val o = OfferParser.parse(OcrText.texts(lines), platform = Platform.UBER)!!
        assertEquals(7.62, o.pay, 0.001)
        assertEquals(4.2, o.miles, 0.001)
        assertEquals("McDonald's", o.merchant)
    }

    @Test fun screenshotTextIsPutInReadingOrder() {
        val t = OcrText.texts(ocr(Triple("b", 200, 10), Triple("c", 200, 90), Triple("a", 100, 400)))
        assertEquals(listOf("a", "b", "c"), t)
    }

    @Test fun findsTheAcceptButtonRowsToKeepTheCardOffThem() {
        val b = OcrText.bands(ocr(Triple("Delivery", 1115, 180), Triple("Accept", 2070, 540)), 55)
        assertEquals(1, b.size)
        assertEquals(2015, b[0].top)
        assertEquals(2185, b[0].bottom)
    }

    @Test fun anEmptyScreenshotIsNotAnOffer() {
        assertNull(OfferParser.parse(OcrText.texts(ocr(Triple("Home", 100, 50), Triple("\$29.17", 160, 50))), platform = Platform.UBER))
    }

    // ---- which apps DashLens is allowed to read

    @Test fun mapsPackagesToApps() {
        assertEquals(Platform.DOORDASH, Platform.of("com.doordash.driverapp"))
        assertEquals(Platform.UBER, Platform.of("com.ubercab.driver"))
        assertNull(Platform.of("com.android.chrome"))
        assertNull(Platform.of("com.ubercab"))
        assertNull(Platform.of(null))
    }

    @Test fun uberIsOffByDefault() {
        val sw = Prefs.SWITCHES.first { it.key == "uber_enabled" }
        assertFalse(sw.def)
    }

    @Test fun accessibilityConfigListsExactlyTheKnownApps() {
        // Android only delivers events from the packages in this file, so it must match Platform.PACKAGES
        val f = listOf("src/main/res/xml/accessibility_config.xml", "app/src/main/res/xml/accessibility_config.xml").map { File(it) }.first { it.exists() }
        val listed = Regex("""android:packageNames="([^"]*)"""").find(f.readText())!!.groupValues[1].split(',').map { it.trim() }.toSet()
        assertEquals(Platform.PACKAGES.keys, listed)
    }
}
