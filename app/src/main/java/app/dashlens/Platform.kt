package app.dashlens

/**
 * The driver apps DashLens can read. The package names here must match `packageNames` in
 * res/xml/accessibility_config.xml (a unit test checks that), because Android only sends DashLens events
 * from the apps listed there.
 *
 * Uber is opt-in: nothing is read from it until "Also read the Uber Driver app" is switched on in Settings.
 */
object Platform {
    const val DOORDASH = "DoorDash"
    const val UBER = "Uber"

    val PACKAGES: Map<String, String> = linkedMapOf(
        "com.doordash.driverapp" to DOORDASH,
        "com.ubercab.driver" to UBER
    )

    /** Which app a package belongs to, or null when DashLens does not read it. */
    fun of(pkg: String?): String? = if (pkg == null) null else PACKAGES[pkg]

    /** How each app is spoken about on screen ("time shown by Dasher" / "time shown by Uber"). */
    fun appName(platform: String): String = if (platform == UBER) "Uber" else "Dasher"
}
