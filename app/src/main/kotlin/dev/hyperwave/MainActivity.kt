package dev.hyperwave

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Insets
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import dev.hyperwave.AppearanceSettings.Style
import dev.hyperwave.audio.NativeDsp

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
        layout.addView(section(R.string.test_title))
        val testHint = text(R.string.test_hint)
        testHint.setPadding(0, dp(8f), 0, dp(16f))
        layout.addView(testHint)
        val play = Button(this)
        play.minHeight = dp(48f)
        play.setText(R.string.test_play)
        play.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            startForegroundService(Intent(this, TestAudioService::class.java).setAction(TestAudioService.PLAY))
        }
        layout.addView(play)
        val stop = Button(this)
        stop.setText(R.string.test_stop)
        stop.minHeight = dp(48f)
        val stopParams = LinearLayout.LayoutParams(-1, -2)
        stopParams.topMargin = dp(8f)
        stop.layoutParams = stopParams
        stop.setOnClickListener { stopService(Intent(this, TestAudioService::class.java)) }
        layout.addView(stop)
        val dsp = TextView(this)
        dsp.setPadding(0, inset, 0, 0)
        try {
            NativeDsp.load(applicationInfo.nativeLibraryDir)
            val pcm = FloatArray(4096)
            for (i in pcm.indices) pcm[i] = (0.05 * Math.sin(2 * Math.PI * 440 * i / 48000)).toFloat()
            val bars = FloatArray(6)
            if (!NativeDsp.analyze(pcm, bars) || bars[2] <= 0)
                throw IllegalStateException("440 Hz response missing")
            dsp.setText(R.string.dsp_ready)
            Log.i("Hyperwave", "app JNI self-check passed " + bars.contentToString())
        } catch (error: Throwable) {
            dsp.text = getString(R.string.dsp_failed, error.message)
            Log.e("Hyperwave", "app JNI self-check failed", error)
        }
        layout.addView(dsp)
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
