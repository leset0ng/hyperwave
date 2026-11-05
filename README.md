# Hyperwave

将 HyperOS 音乐超级岛右侧的预制动画替换为音频驱动的六柱波形。

这是基于 **libxposed API 102** 的 LSPosed MVP，Android 接入层使用 Java，DSP 使用 Rust。Android 工程直接位于本仓库根目录。

```text
SystemUI / MIUISystemUIPlugin
    → musicWave / musicPause hooks
    → UID-filtered AudioPolicy + AudioRecord
    → 48 kHz mono Float32, 4096-frame blocks
    → JNI / Rust Hann + FFT + six-band response
    → Canvas geometry + interruptible 80 ms transitions
```

## Requirements

当前验证目标：hongkong AVD，HyperOS `OS4.0.24.0.XFRCNXM` / Android 17 API 37 / arm64，LSPosed `2.2.1 (7930)`，运行时 Xposed API `102`。

模块只作用于 `com.android.systemui`，依赖其音频路由与播放捕获权限。其他 HyperOS 版本的类名、模板和音频策略可能不同，尚未验证。

开发环境：JDK 21、Android SDK 37、Android NDK `30.0.15729638`、Rust stable 和 `aarch64-linux-android` target。

## Build

```sh
rustup target add aarch64-linux-android
cargo test --locked -p hyperwave-dsp

# macOS, if the default Java is not JDK 21:
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:assembleDebug :app:lintDebug
```

Gradle 自动运行 `scripts/build-rust.sh`，将 Rust `.so` 放入 APK。可通过 `ANDROID_NDK_HOME` 指定 NDK 路径，通过 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME` 指定 SDK。

产物：`app/build/outputs/apk/debug/app-debug.apk`。

```kotlin
dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
```

模块使用 `XposedModule.onModuleLoaded` / `onPackageReady`、`hook(...).intercept(...)` 和 `META-INF/xposed/` 配置，没有使用 legacy XposedBridge API。

## Install and use

1. 安装 APK，在 LSPosed 中启用 **Hyperwave**，确认推荐作用域 **系统界面 / com.android.systemui** 已勾选。
2. 重启系统界面或设备，让新模块进入 SystemUI 进程。更新 APK 后也需要重新加载 SystemUI。
3. 播放音乐并回到桌面。模块接管 `musicWave` / `musicPause` 图标，保留 HyperOS 原有岛容器、封面和点击行为。
4. 打开 Hyperwave，点击“播放测试音”可运行一分钟测试：依次播放 45、120、320、900、2800、6500 Hz 的低幅合成音。用“停止测试音”提前结束。

测试播放器使用 MediaSession 和允许捕获的 AudioTrack，不需要音乐文件。测试服务在一分钟后自动结束。

停用：在 LSPosed 中禁用 Hyperwave 并重启系统界面。卸载应用也需要重新加载系统界面才能退出已注入代码。

## Implemented

- API 102 模块注册、固定 SystemUI 作用域、SystemUI 插件 ClassLoader 发现。
- 仅替换音乐模板的动画，跳过其他事件及投放静态图标。
- 按播放器 UID 与 `USAGE_MEDIA` 匹配的回环采集，`RENDER | LOOP_BACK` 保留原播放输出。
- 48 kHz mono Float32 → 4096-frame block → Rust DSP → 六柱。
- 前 2048 样本周期 Hann + 2048 零填充、FFT 尺度与 packed DC/Nyquist、六个实际 bin 分组、幅值求和和非线性映射。
- 对目标几何做 80 ms ease-out，从当前显示状态接续；暂停使用独立 900 ms 过渡。
- 视图隐藏、移除和播放源切换时停止采集；失效 session 的更新不会写入新视图状态。
- 采集失败时播放已还原的 fallback 高度轨迹；正常采集到零值时显示低柱。

## MVP boundaries

- 初版柱形为白色。原系统封面保留；尚未实现 iOS 的封面着色遮罩、blur 和颜色处理。
- Android `drawRoundRect` 用于圆角，没有声称与 Apple continuous corners 逐像素一致。
- fallback 使用已还原 VariantA 的五条轨迹，暂用线性关键帧插值，第六柱保持最低高度；不是完整 Core Animation cubic/排列/相位复刻。
- 48 kHz 模式受播放器捕获策略限制。`ALLOW_CAPTURE_BY_SYSTEM` / `ALLOW_CAPTURE_BY_NONE` 的音频可能不能进入这条路径；全零 PCM 无法单独区分真实静音和被策略排除。模块不会据此伪造实时频谱，也不会自动降为全局混音。
- 更宽松的 privileged capture 被 Android 限制为至多 16 kHz mono PCM16，MVP 不启用这条有高频损失的降级路径。
- UID 过滤不是 iOS 的 PID tap，同 UID 的媒体流可能被合并。蓝牙、远程投放、受保护内容、多用户和各音乐应用兼容性尚未全面测试。
- 当前宿主内运行 DSP；native 崩溃会影响 SystemUI。JNI 已检查块长度、处理非有限值并拦截 Rust unwind。

## Validation and debugging

已在上述 AVD 实测 API 102 模块加载、native 加载、UID 过滤采集、六个测试频段的非零响应、实际岛内绘图和测试结束后的 AudioPolicy 注销。当前 AVD 已安装并启用模块。实测记录与录屏索引见 `hyperos-re/mvp-validation.md`。

`dsp/tests/data/pcm-golden-vectors.json` 来自本仓库 iOS 逆向的七组数值参考输入，保留原始 scope 字段。这些向量是重建的 Accelerate pipeline 结果，不是 Apple 音乐 UI 的动态录制。Rust 测试检查全部 2048 个幅值与六柱值，并额外检查半窗、分段边界、错误长度和非有限输入。

Android lint 的内部 API 反射、动态 native 库加载和单 arm64 支持警告与模块的实现方式有关；lint 没有错误。

```sh
adb -s emulator-5580 logcat -s Hyperwave
adb -s emulator-5580 shell 'su -c "/data/adb/modules/zygisk_lsposed/lspctl status"'
```

模块加载、插件 Hook 信息同时写入 LSPosed 模块日志。音频日志包含目标 UID、采样格式、块峰值和六柱数值，不保存原始音频。

## Layout

```text
app/            Android app, modern Xposed entry, capture and renderer
  src/main/resources/META-INF/xposed/
dsp/            Rust DSP + JNI entry + numerical reference tests
scripts/        Native build script
gradle/         Gradle wrapper
hyperos-re/     APK samples, decompilation and AVD validation evidence (local)
ios-re/         Existing Apple algorithm and rendering research (local)
```

HyperOS 调查与动态验证记录继续保存在 `hyperos-re/`。原始 iOS/HyperOS 样本及反编译产物不参与 APK 编译；DSP 数值测试使用独立复制的参考向量。
