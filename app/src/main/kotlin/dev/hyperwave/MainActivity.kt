package dev.hyperwave

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import dev.hyperwave.AppearanceSettings.Style

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        val inset = dp(24f)
        layout.setPadding(inset, inset, inset, inset)
        scroll.addView(layout, FrameLayout.LayoutParams(-1, -2))
        scroll.setOnApplyWindowInsetsListener { _, windowInsets ->
            val bars = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            windowInsets
        }
        val title = TextView(this)
        title.setText(R.string.app_name)
        title.setTextAppearance(android.R.style.TextAppearance_Material_Headline)
        layout.addView(title)
        val instructions = text(R.string.setup_instructions)
        instructions.setPadding(0, dp(16f), 0, dp(28f))
        layout.addView(instructions)
        layout.addView(section(R.string.appearance_title))
        val styles = RadioGroup(this)
        val ios = option(R.string.appearance_ios)
        val hyperos = option(R.string.appearance_hyperos)
        styles.addView(ios)
        styles.addView(hyperos)
        styles.check(if (AppearanceSettings.load(this) == Style.IOS) ios.id else hyperos.id)
        styles.setOnCheckedChangeListener { _, checkedId ->
            AppearanceSettings.save(this, if (checkedId == ios.id) Style.IOS else Style.HYPEROS)
        }
        layout.addView(styles)
        val hint = text(R.string.appearance_hint)
        hint.setPadding(0, dp(8f), 0, dp(28f))
        layout.addView(hint)
        val heightHeader = LinearLayout(this)
        heightHeader.gravity = Gravity.CENTER_VERTICAL
        val heightLabel = section(R.string.height_title)
        val heightValue = text(R.string.height_title)
        heightHeader.addView(heightLabel, LinearLayout.LayoutParams(0, -2, 1f))
        heightHeader.addView(heightValue)
        layout.addView(heightHeader)
        val heightSlider = SeekBar(this)
        heightSlider.id = View.generateViewId()
        heightLabel.labelFor = heightSlider.id
        heightSlider.min = AppearanceSettings.MIN_HEIGHT_PERCENT
        heightSlider.max = AppearanceSettings.MAX_HEIGHT_PERCENT
        heightSlider.progress = AppearanceSettings.loadHeightPercent(this)
        heightValue.text = getString(R.string.height_percent, heightSlider.progress)
        heightSlider.stateDescription = heightValue.text
        heightSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                heightValue.text = getString(R.string.height_percent, progress)
                seekBar.stateDescription = heightValue.text
                if (fromUser) AppearanceSettings.saveHeightPercent(this@MainActivity, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        layout.addView(heightSlider, LinearLayout.LayoutParams(-1, dp(48f)).apply {
            bottomMargin = dp(28f)
        })
        setContentView(scroll)
    }

    private fun dp(value: Float): Int = Math.round(value * resources.displayMetrics.density)

    private fun text(resource: Int): TextView {
        val view = TextView(this)
        view.setTextAppearance(android.R.style.TextAppearance_Material_Body1)
        view.setText(resource)
        return view
    }

    private fun section(resource: Int): TextView {
        val view = text(resource)
        view.setTextAppearance(android.R.style.TextAppearance_Material_Title)
        view.isAccessibilityHeading = true
        return view
    }

    private fun option(resource: Int): RadioButton {
        val view = RadioButton(this)
        view.id = View.generateViewId()
        view.setText(resource)
        view.textSize = 16f
        view.minHeight = dp(76f)
        view.setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        view.layoutParams = RadioGroup.LayoutParams(-1, -2)
        return view
    }
}
