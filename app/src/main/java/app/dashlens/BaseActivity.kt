package app.dashlens

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/** Every main screen: scrolling content on top, the five-tab bar at the bottom. */
abstract class BaseActivity : Activity() {
    /** 0 Home, 1 Offers, 2 Money, 3 Insights, 4 Settings */
    abstract val tab: Int
    protected lateinit var root: LinearLayout
    protected lateinit var scroll: ScrollView

    abstract fun build(root: LinearLayout)

    protected fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(frame())
        render()
    }

    /** Rebuilds the screen content (keeps the scroll position). */
    protected fun render() {
        val y = scroll.scrollY
        root.removeAllViews()
        build(root)
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun frame(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        // buttons and horizontal rows added straight to the page get a small bottom margin automatically
        root = object : LinearLayout(this) {
            override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
                if (params is LayoutParams && params.topMargin == 0 && params.bottomMargin == 0 &&
                    (child is android.widget.Button || (child is LinearLayout && child.orientation == HORIZONTAL))
                ) params.bottomMargin = dp(8)
                super.addView(child, index, params)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(root)
        outer.addView(scroll)
        outer.addView(Ui.navBar(this, tab))
        return outer
    }
}
