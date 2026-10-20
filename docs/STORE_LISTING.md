# Store listing pack

## Title
DashLens: Dasher Offer Analyzer

## Short description (80 chars)
Know if a delivery offer is worth it. Auto mileage, earnings and tax tracking.

## Full description
DashLens puts a clear TAKE / MAYBE / DECLINE card on every delivery offer, based on YOUR numbers: your vehicle cost, your minimum $/hr and your real pace.

- Instant offer card: net $/hr, $/mile, real miles including the empty drive back
- Learns your true order times and each merchant's history from your own shifts
- Hard filters: minimum pay, maximum miles, maximum stops
- Automatic mileage and time log (on order vs between orders), weekly / monthly / yearly reports
- Earnings, expenses with receipt photos, profit and a quarterly tax set-aside
- Best hours, best days and hotspots from your own data
- 100% private: no account, no internet access, everything stays on your phone

DashLens never accepts or declines offers for you. It only shows information.

Independent app, not affiliated with or endorsed by DoorDash. Figures are estimates, not tax advice.

## Google Play: Accessibility API declaration (paste into Play Console)
Core functionality: DashLens reads on-screen text of the DoorDash Dasher app (com.doordash.driverapp) to display an on-screen profitability card for each delivery offer and to detect online / on-order state for mileage logging. It does not perform gestures, clicks or text entry, and does not access any other app. No data is transmitted off the device. In-app prominent disclosure and consent are shown before the user is sent to enable the service.

## Data safety form
- Data collected: Location (approximate and precise) is processed on-device only, not collected or shared by the developer. No data is shared with third parties. No data leaves the device.
- Encryption in transit: not applicable (no network).
- Deletion: uninstall removes all data.

## Permissions rationale
- Location (incl. background): measure miles during a shift with the screen off.
- Foreground service (location): keep mileage tracking alive during a shift.
- Notifications: shift controls and reminders.
- Vibrate: optional offer feedback.
- Exact alarm: Sunday weekly summary.
- Boot completed: reschedule the weekly summary.

## Before you publish
1. Replace the contact email in PRIVACY.md and host it at a public URL.
2. Create a release keystore and set the four DASHLENS_* variables (see .github/workflows/release.yml).
3. Review DoorDash's Dasher terms and get legal advice on distributing a tool that reads their app.
4. Have a tax professional review the wording of the tax features before charging for them.
