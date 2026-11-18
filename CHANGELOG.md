# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [4.3.0] - 2026-11-17

### Added
- The shift now ends by itself when you are offline or logged out of every driver app you use (DoorDash, and Uber if enabled) for 1 minute (Settings > "End the shift N minutes after you go offline", 0.5 = 30 s, 0 = off). It only runs after DashLens has seen you online during the shift, never ends a shift while you are on an order, and does not trigger the 10-minute wait before auto start, so going online again starts a new shift. Logged-out screens ("Forgot password", "Log in to your...", the Dasher home with "Avg. offer wait") now count as offline. Settings > Diagnostics shows what auto end did last.
- The screenshot text saved in Diagnostics is now only the offer card itself; whatever was on screen behind it is dropped (it used to keep the whole screen).
- Uber offers read from a screenshot: Uber hides its offer card from the accessibility read (the whole screen comes back empty while an offer is up), so DashLens now takes a screenshot when Uber's screen is empty and reads the pay, miles and merchant with on-device text recognition (Settings > Read Uber offer cards from a screenshot). Nothing is saved or sent; the app still has no internet permission (the build fails if one appears). Needs Android 11+; after updating, switch DashLens off and on under Accessibility and accept the screenshot permission.
- Uber offers: DashLens now reads every window of the driver app. Uber draws its offer card as a window of its own over the map, which the old single-window read never saw (every other Uber screen was read fine). Settings > Diagnostics shows how many Uber windows were seen and whether one looked like an offer.
- Fixed: the Dasher ratings screen ("Acceptance rate") was mistaken for an offer and overwrote the captured offer text. Only real Accept / Decline buttons (and Uber's Match) count now.
- Uber offers are now also recognised by their numbers (a pay line plus a "22 min (8.1 mi) total" line), so an offer with an unexpected button label still gets a card. Settings > Diagnostics keeps the last 3 Uber screens that looked like an offer.
- Settings > Diagnostics now shows what auto start did last (started, waiting, or blocked by Android and why), plus an "Allow display over other apps" button. Android only lets an app start location tracking from the background in certain cases (for example with that permission, or with battery optimisation off); without it you get the Start shift notification.
- Auto start (opt-in): **Settings > Start a shift automatically when DashLens sees you go online**. Going online on DoorDash or Uber then starts the shift and logs miles without a tap. It waits 10 minutes after you stop a shift by hand, and if Android refuses to start location tracking from the background you still get the Start shift notification.
- Uber Driver support (opt-in). Switch on **Settings > Also read the Uber Driver app**; until then nothing is read from Uber. DashLens reads Uber delivery offers (pay, the "22 min (8.1 mi) total" line, pickup merchant), grades them like Dasher offers using the time Uber shows, and detects Uber's Finding trips (online) and arrival / navigation (on order) screens. Offer cards and history are tagged with the app.
- If you use both apps, the shift state is the strongest recent state of either app (On order beats Online beats Paused), so checking Uber during a DoorDash order no longer ends that order early. DoorDash-only use behaves as before.
- Offers record which app they came from (database v5; existing offers are marked DoorDash). The offers CSV has a Platform column and the money summary splits accepted-offer income by app when both are used.
- Separate Uber word lists for online / on order / not driving under Settings > Screen keywords.
- Uber offers laid out any other way (for example ride requests with separate pickup and trip legs) are reported as not read instead of being graded on half a trip.
- Unit tests for the Uber screens (taken from real screenshots), the app merge, and a check that the accessibility config lists exactly the apps DashLens knows.

### Changed
- Onboarding, About, the service description, the privacy policy, the store listing and the README now say DashLens may read the Uber Driver app (only when switched on) and name Uber in the not-affiliated notice.

## [4.2.1] - 2026-10-23

### Changed
- The offer card no longer sits on top of the Accept and Decline buttons. DashLens finds the two buttons on the Dasher screen and moves the card just below or above them. If the Dasher screen is still sliding in, the card follows the buttons. Switch it off with "Keep the offer card off the Accept and Decline buttons" in Settings.
- New setting "Move the offer card down (+) or up (-)" (a % of the screen height) for fine tuning. Number boxes now accept a minus sign.
- Settings > Diagnostics shows where the buttons were found and where the card was placed, so a wrong position is easy to report.
- The Preview card has no real buttons to avoid, so it shows your top/bottom choice plus the nudge.

### Added
- Unit tests for the offer parser and the grading model (31 cases: pay, distance and merchant reading, "+" pay, item counts, deliver-by window across midnight, vehicle cost modes, hard filters, shop & deliver time, merchant history). Run them with `gradle testDebugUnitTest`.
- CI now runs the unit tests and a lint pass on every push, uploads the test and lint reports, and the release workflow stops if a test fails.

## [4.2.0] - 2026-10-20

### Added
- Line-by-line vehicle cost on every offer: gas (miles ÷ mpg × gas price) plus wear and tear (per mile), then net pay. New settings: gas price, mpg, wear and tear. The old flat cost per mile still works when "itemize" is switched off, and your logged actual cost still overrides both.
- Shop & deliver time model: "N items" is read from the offer, and shopping time (per item) plus checkout time (per store) replaces the wait at the store. New settings: shopping time per item, checkout time.
- Offers shown with a "+" (a minimum pay) are marked and graded at that minimum, with a note that the real pay may be higher.
- Net $/mi tile (net pay ÷ offer miles), a "against your $X/hr floor, after vehicle costs" line under the verdict, and an optional full-math block on the offer card: gas, wear, net pay, time breakdown, miles counted. Switch it off in Settings for the old short reasons.
- Shareable offer card: tap SHARE on any row in Offers to make a picture card (verdict, net $/hr and $/mi, the math, the assumptions) saved to Pictures/DashLens and opened in the share sheet. Settings > Diagnostics has "Share a sample offer card (test)".
- Settings: "Load typical Dasher assumptions" button (asks first) that fills gas, mpg, wear, shopping, checkout, drop-off, return-miles and speed with typical values.

### Changed
- The offer-card preview is now a sample shop & deliver offer.
- The merchant history screen uses the vehicle cost actually in force (itemized, flat or logged actual) instead of the flat rate only.
- Database schema v4 adds item count and "+" flag to the offer history (existing data is kept).
- Default vehicle cost is now gas + wear (about $0.33/mi at the default gas price and mpg) instead of a flat $0.30/mi. Turn "itemize" off in Settings to keep the flat rate.

## [4.1.0] - 2026-10-20

### Changed
- Version bump so the in-app version matches the release tag.

## [4.0.1] - 2026-10-20

### Changed
- Release workflow now attaches DashLens.apk and SHA256SUMS.txt automatically.
- README install section with QR code and checksum.

## [4.0.0] - 2026-10-20

### Added
- LICENSE, SECURITY.md, CONTRIBUTING.md, and issue/pull request templates.
