package dev.hyperwave;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.hyperwave.audio.NativeDsp;

public final class MainActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int inset = Math.round(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(inset, inset * 2, inset, inset);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Hyperwave");
        title.setTextSize(30);
        layout.addView(title);
        TextView instructions = new TextView(this);
        instructions.setText("在 LSPosed 中启用 Hyperwave，作用域选择系统界面，然后重启系统界面。\n\n播放音乐后，超级岛右侧显示六柱波形。可用下面的测试音验证六个频段。测试会播放一分钟低音量合成音。\n\nAPI 102 · Rust DSP · arm64");
        instructions.setTextSize(16);
        instructions.setPadding(0, inset, 0, inset);
        layout.addView(instructions);
        Button play = new Button(this);
        play.setText("播放测试音");
        play.setOnClickListener(view -> {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            startForegroundService(new Intent(this, TestAudioService.class).setAction(TestAudioService.PLAY));
        });
        layout.addView(play);
        Button stop = new Button(this);
        stop.setText("停止测试音");
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
            dsp.setText("Rust 音频计算已就绪");
            Log.i("Hyperwave", "app JNI self-check passed " + java.util.Arrays.toString(bars));
        } catch (Throwable error) {
            dsp.setText("Rust 加载失败：" + error.getMessage());
            Log.e("Hyperwave", "app JNI self-check failed", error);
        }
        layout.addView(dsp);
        setContentView(layout);
    }
}
