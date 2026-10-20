package app.dashlens

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView

/** First-run welcome plus the prominent Accessibility disclosure. */
class OnboardingActivity : Activity() {

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(24))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(col) })

        Ui.add(col, Ui.title(this, "Welcome to DashLens"), 6)
        Ui.add(col, Ui.text(this, "Know in a glance whether an offer is worth your time, and keep your miles, earnings and tax numbers without lifting a finger.", 15f, Ui.MUTED), 18)

        val what = Ui.card(this)
        what.addView(Ui.text(this, "What you get", 16f, Ui.TEXT, true).apply { setPadding(0, 0, 0, dp(6)) })
        for (s in listOf(
            "A TAKE / MAYBE / DECLINE card on every offer, with your real net \$/hr and \$/mile",
            "It learns your true order times and each merchant's history from your own shifts",
            "Automatic mileage and time log, earnings, expenses with receipts and a tax set-aside",
            "Best hours and hotspots from your own data"
        )) what.addView(Ui.text(this, "•  " + s, 14f, Ui.TEXT).apply { setPadding(0, dp(3), 0, dp(3)) })
        Ui.add(col, what)

        val acc = Ui.card(this)
        acc.addView(Ui.text(this, "How DashLens uses Accessibility", 16f, Ui.AMBER, true).apply { setPadding(0, 0, 0, dp(6)) })
        acc.addView(Ui.text(this,
            "To show the offer card, DashLens uses Android's Accessibility Service to read the text on screens of the DoorDash Dasher app (offer pay, miles, merchant, " +
                "and whether you are online or on an order).\n\n" +
                "•  It reads only the Dasher app, and nothing else on your phone.\n" +
                "•  It never taps, accepts, declines or types anything for you.\n" +
                "•  Nothing leaves your phone. DashLens has no internet access and no account.\n\n" +
                "You turn this on yourself in Android settings, and can turn it off any time.", 14f, Ui.TEXT))
        Ui.add(col, acc)

        val dis = Ui.card(this)
        dis.addView(Ui.text(this,
            "Earnings, mileage deduction and tax figures are estimates to help you plan, not tax advice. DashLens is an independent app and is not affiliated with or endorsed by DoorDash.",
            12f, Ui.MUTED))
        Ui.add(col, dis, 20)

        Ui.add(col, Ui.button(this, "I understand. Set up DashLens", 1) {
            Prefs.putBool(this, "onboarded", true)
            Prefs.putBool(this, "accessibility_consent", true)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            finish()
        }, 8)
        Ui.add(col, Ui.button(this, "Not now") {
            Prefs.putBool(this, "onboarded", true)
            finish()
        }, 0)
    }
}
