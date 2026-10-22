# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

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
