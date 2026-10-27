package app.dashlens

import android.content.Context
import java.util.Locale

data class Field(val key: String, val label: String, val def: Float, val group: String = "Costs and time assumptions")
data class Sw(val key: String, val label: String, val def: Boolean)

object Prefs {
    private const val G_RULES = "Offer rules (what counts as TAKE)"
    private const val G_COST = "Costs and time assumptions"
    private const val G_CARD = "Offer card position"
    private const val G_MONEY = "Goals, mileage rate and taxes"

    val FIELDS = listOf(
        Field("min_hr", "Minimum net \$/hr (full cycle)", 18f, G_RULES),
        Field("min_mi", "Minimum \$ per real mile", 1.5f, G_RULES),
        Field("min_pay", "Hard filter: minimum offer pay \$ (0 = off)", 0f, G_RULES),
        Field("max_mi", "Hard filter: maximum offer miles (0 = off)", 0f, G_RULES),
        Field("max_stops", "Hard filter: maximum pickups / stops (0 = off)", 0f, G_RULES),
        Field("overlay_nudge_pct", "Move the offer card down (+) or up (-) by this % of the screen height (0 = no change)", 0f, G_CARD),
        Field("gas_price", "Gas price (\$ per gallon)", 3.87f, G_COST),
        Field("mpg", "Your car's fuel economy (mpg)", 25f, G_COST),
        Field("wear_mi", "Wear and tear (\$ per mile: tires, maintenance, depreciation)", 0.18f, G_COST),
        Field("cost_mi", "Flat vehicle cost per mile (\$) - used only when itemizing is turned off", 0.30f, G_COST),
        Field("empty_pct", "Empty miles coming back (% of offer miles)", 20f, G_COST),
        Field("wait_min", "Merchant wait per pickup (min)", 7f, G_COST),
        Field("extra_pickup_min", "Extra overhead per additional pickup (min)", 5f, G_COST),
        Field("dropoff_min", "Drop-off handoff time (min)", 3f, G_COST),
        Field("shop_min_item", "Shop & deliver: shopping time per item (min)", 1.5f, G_COST),
        Field("checkout_min", "Shop & deliver: checkout time per store (min)", 5f, G_COST),
        Field("offer_speed_mph", "Avg speed on an order, before DashLens has learned yours (mph)", 22f, G_COST),
        Field("speed_mph", "Avg speed for the return drive (mph)", 25f, G_COST),
        Field("irs_rate", "IRS business mileage rate (\$/mi) - check each Jan/Jul", 0.76f, G_MONEY),
        Field("goal_day", "Daily earnings goal \$ (0 = off)", 0f, G_MONEY),
        Field("goal_week", "Weekly earnings goal \$ (0 = off)", 0f, G_MONEY),
        Field("tax_rate", "Income-tax set-aside % (federal + state, applied after self-employment tax)", 10f, G_MONEY),
        Field("biz_pct", "Business-use % of vehicle (actual-expense method only; odometer log overrides)", 100f, G_MONEY),
        Field("auto_stop_min", "Auto-stop shift after N minutes of auto-detected 'not dashing' (0 = off)", 30f, G_MONEY)
    )

    val SWITCHES = listOf(
        Sw("auto_state", "Auto-detect Online / On order from Dasher screens", true),
        Sw("prompt_start", "Notify me to start a shift when Dasher looks active", true),
        Sw("auto_calibrate", "Learn my real order times and merchant history from my own shifts", true),
        Sw("overlay_bottom", "Show the offer card at the bottom of the screen", false),
        Sw("overlay_avoid", "Keep the offer card off the Accept and Decline buttons (it moves just below or above them)", true),
        Sw("overlay_compact", "Compact offer card (verdict and key numbers only)", false),
        Sw("fb_haptic", "Vibrate on verdict (1 buzz take, 2 maybe, 3 decline)", false),
        Sw("fb_voice", "Speak the verdict aloud", false),
        Sw("itemize_cost", "Vehicle cost = gas (mpg and gas price) + wear and tear, shown line by line (off = one flat \$/mile)", true),
        Sw("overlay_math", "Show the full math on the offer card (gas, wear, net pay, time, miles counted)", true),
        Sw("use_actual_cost", "Use my actual gas + maintenance cost per mile (from logged expenses) in offer analysis", false),
        Sw("income_manual_only", "Income = only what I log by hand (ignore accepted-offer estimates)", false),
        Sw("method_actual", "Tax method: actual vehicle expenses instead of standard mileage", false),
        Sw("basis_online", "Mileage basis: all online miles instead of on-order miles only", false)
    )

    private fun sp(c: Context) = c.getSharedPreferences("dashlens", Context.MODE_PRIVATE)

    fun get(c: Context, k: String): Double {
        val v = sp(c).getFloat(k, FIELDS.first { it.key == k }.def).toDouble()
        return Math.round(v * 10000.0) / 10000.0   // hides float noise like 0.30000001192
    }

    fun put(c: Context, k: String, v: Float) = sp(c).edit().putFloat(k, v).apply()

    fun getString(c: Context, k: String): String = sp(c).getString(k, "") ?: ""
    fun putString(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()

    fun getBool(c: Context, k: String, def: Boolean): Boolean = sp(c).getBoolean(k, def)
    fun putBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()

    fun getLong(c: Context, k: String): Long = sp(c).getLong(k, 0L)
    fun putLong(c: Context, k: String, v: Long) = sp(c).edit().putLong(k, v).apply()

    fun fmt(v: Double): String =
        String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
}
