package io.github.leset0ng.hyperwave

import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
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
import android.widget.Switch
import android.widget.TextView
import io.github.leset0ng.hyperwave.AppearanceSettings.Style

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
        val mios = option(R.string.appearance_mios)
        styles.addView(ios)
        styles.addView(hyperos)
        styles.addView(mios)
        styles.check(when (AppearanceSettings.load(this)) {
            Style.IOS -> ios.id
            Style.HYPEROS -> hyperos.id
            Style.MIOS -> mios.id
        })
        styles.setOnCheckedChangeListener { _, checkedId ->
            val selected = when (checkedId) {
                ios.id -> Style.IOS
                hyperos.id -> Style.HYPEROS
                mios.id -> Style.MIOS
                else -> return@setOnCheckedChangeListener
            }
            AppearanceSettings.save(this, selected)
        }
        layout.addView(styles)
        val hint = text(R.string.appearance_hint)
        hint.setPadding(0, dp(8f), 0, dp(28f))
        layout.addView(hint)
        val artworkFlip = Switch(this)
        artworkFlip.setText(R.string.artwork_flip_title)
        artworkFlip.textSize = 16f
        artworkFlip.minHeight = dp(56f)
        artworkFlip.setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        artworkFlip.isChecked = AppearanceSettings.loadArtworkFlip(this)
        artworkFlip.setOnCheckedChangeListener { _, enabled ->
            AppearanceSettings.saveArtworkFlip(this, enabled)
        }
        layout.addView(artworkFlip, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(28f)
        })
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
        val launcherComponent = ComponentName(this, "$packageName.Launcher")
        val hideLauncher = Switch(this)
        hideLauncher.setText(R.string.hide_launcher_title)
        hideLauncher.textSize = 16f
        hideLauncher.minHeight = dp(56f)
        hideLauncher.setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        hideLauncher.isChecked = packageManager.getComponentEnabledSetting(launcherComponent) ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        hideLauncher.setOnCheckedChangeListener { _, hidden ->
            packageManager.setComponentEnabledSetting(launcherComponent,
                    if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP)
        }
        layout.addView(hideLauncher, LinearLayout.LayoutParams(-1, -2))
        val launcherHint = text(R.string.hide_launcher_hint)
        launcherHint.setPadding(0, dp(8f), 0, 0)
        layout.addView(launcherHint)
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
