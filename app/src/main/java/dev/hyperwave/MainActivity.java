package dev.hyperwave;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.graphics.Insets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.hyperwave.audio.NativeDsp;

public final class MainActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int inset = dp(24);
        layout.setPadding(inset, inset, inset, inset);
        scroll.addView(layout, new ScrollView.LayoutParams(-1, -2));
        scroll.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextAppearance(android.R.style.TextAppearance_Material_Headline);
        layout.addView(title);
        TextView instructions = text(R.string.setup_instructions);
        instructions.setPadding(0, dp(16), 0, dp(28));
        layout.addView(instructions);
        layout.addView(section(R.string.appearance_title));
        RadioGroup styles = new RadioGroup(this);
        RadioButton ios = option(R.string.appearance_ios);
        RadioButton hyperos = option(R.string.appearance_hyperos);
        styles.addView(ios);
        styles.addView(hyperos);
        styles.check(AppearanceSettings.load(this) == AppearanceSettings.Style.IOS ? ios.getId() : hyperos.getId());
        styles.setOnCheckedChangeListener((group, checkedId) -> AppearanceSettings.save(this,
                checkedId == ios.getId() ? AppearanceSettings.Style.IOS : AppearanceSettings.Style.HYPEROS));
        layout.addView(styles);
        TextView hint = text(R.string.appearance_hint);
        hint.setPadding(0, dp(8), 0, dp(28));
        layout.addView(hint);
        layout.addView(section(R.string.test_title));
        TextView testHint = text(R.string.test_hint);
        testHint.setPadding(0, dp(8), 0, dp(16));
        layout.addView(testHint);
        Button play = new Button(this);
        play.setMinHeight(dp(48));
        play.setText(R.string.test_play);
        play.setOnClickListener(view -> {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            startForegroundService(new Intent(this, TestAudioService.class).setAction(TestAudioService.PLAY));
        });
        layout.addView(play);
        Button stop = new Button(this);
        stop.setText(R.string.test_stop);
        stop.setMinHeight(dp(48));
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(-1, -2);
        stopParams.topMargin = dp(8);
        stop.setLayoutParams(stopParams);
        stop.setOnClickListener(view -> stopService(new Intent(this, TestAudioService.class)));
        layout.addView(stop);
        TextView dsp = new TextView(this);
        dsp.setPadding(0, inset, 0, 0);
        try {
            NativeDsp.load(getApplicationInfo().nativeLibraryDir);
            float[] pcm = new float[4096];
            for (int i = 0; i < pcm.length; i++) pcm[i] = (float) (.05 * Math.sin(2 * Math.PI * 440 * i / 48000));
            float[] bars = new float[6];
            if (!NativeDsp.analyze(pcm, bars) || bars[2] <= 0) throw new IllegalStateException("440 Hz response missing");
            dsp.setText(R.string.dsp_ready);
            Log.i("Hyperwave", "app JNI self-check passed " + java.util.Arrays.toString(bars));
        } catch (Throwable error) {
            dsp.setText(getString(R.string.dsp_failed, error.getMessage()));
            Log.e("Hyperwave", "app JNI self-check failed", error);
        }
        layout.addView(dsp);
        setContentView(scroll);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(int resource) {
        TextView view = new TextView(this);
        view.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        view.setText(resource);
        return view;
    }

    private TextView section(int resource) {
        TextView view = text(resource);
        view.setTextAppearance(android.R.style.TextAppearance_Material_Title);
        view.setAccessibilityHeading(true);
        return view;
    }

    private RadioButton option(int resource) {
        RadioButton view = new RadioButton(this);
        view.setId(View.generateViewId());
        view.setText(resource);
        view.setTextSize(16);
        view.setMinHeight(dp(76));
        view.setPadding(dp(8), dp(8), dp(8), dp(8));
        view.setLayoutParams(new RadioGroup.LayoutParams(-1, -2));
        return view;
    }
}
