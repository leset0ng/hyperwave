# Hyperwave

将 HyperOS 音乐超级岛右侧的预制动画替换为音频驱动波形，可在设置页选择 iOS 六柱外观或保留 HyperOS 原生五柱外观。

这是基于 **libxposed API 102** 的 LSPosed MVP，Android 接入层使用 Kotlin，DSP 使用 Rust。Android 工程直接位于本仓库根目录。

```text
SystemUI / MIUISystemUIPlugin
    → musicWave / musicPause hooks
    → UID-filtered AudioPolicy + AudioRecord
    → 48 kHz mono Float32, 4096-frame blocks
    → JNI / Rust Hann + FFT + six-band response
    → Canvas geometry (iOS six bars / native HyperOS five bars) + interruptible transitions
```

## Requirements

当前验证目标：hongkong AVD，HyperOS `OS4.0.24.0.XFRCNXM` / Android 17 API 37 / arm64，LSPosed `2.2.1 (7930)`，运行时 Xposed API `102`。

模块作用于 `com.android.systemui` 和系统框架 `system`（system_server）。SystemUI 负责 UID 音频回环与绘图；system_server 仅为模块标记的音乐回环放行 48 kHz Float32 privileged capture 格式检查，保留原有权限校验。其他 HyperOS 版本的类名、模板和音频策略可能不同，尚未验证。

开发环境：JDK 21、Android SDK 37、Android NDK `30.0.15729638`、Rust stable 和 `aarch64-linux-android` target。

## Build

```sh
rustup target add aarch64-linux-android
cargo test --locked -p hyperwave-dsp

# macOS, if the default Java is not JDK 21:
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
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

1. 安装 APK，在 LSPosed 中启用 **Hyperwave**，确认推荐作用域 **系统界面 / com.android.systemui** 和 **系统框架 / system** 均已勾选。现代模块的系统进程作用域标识为 `system`，不是 `android`。
2. 首次启用系统框架作用域或更新其 Hook 后，重启设备或模拟器，让新模块进入 system_server 与 SystemUI。仅重启系统界面无法更新 system_server 中的 Hook。
3. 打开 Hyperwave，选择“iOS”或“HyperOS”外观，选择自动保存并实时应用，无需为了切换外观重启系统界面。默认选择 HyperOS。
4. 播放音乐并回到桌面。模块接管 `musicWave` / `musicPause` 图标，保留 HyperOS 原有岛容器、封面和点击行为。
5. 点击“播放测试音”可运行一分钟测试：依次播放 45、120、320、900、2800、6500 Hz 的低幅合成音。用“停止测试音”提前结束。

测试播放器使用 MediaSession 和允许捕获的 AudioTrack，不需要音乐文件。测试服务在一分钟后自动结束。

停用：在 LSPosed 中禁用 Hyperwave 并重启设备或模拟器。卸载应用后也需要重启，才能退出已注入 system_server 与 SystemUI 的代码。

## Implemented

- API 102 模块注册、SystemUI 与系统框架作用域、SystemUI 插件 ClassLoader 发现。
- 两个进程协同放行带专用标记、单个应用 UID、仅 `USAGE_MEDIA`、`RENDER | LOOP_BACK` 的 48 kHz mono Float32 privileged capture；格式例外仅在当前线程的匹配操作内生效，system_server 同时核对调用者为 SystemUI。
- 仅替换音乐模板的动画，跳过其他事件及投放静态图标。
- 按播放器 UID 与 `USAGE_MEDIA` 匹配的回环采集，`RENDER | LOOP_BACK` 保留原播放输出。
- 48 kHz mono Float32 → 4096-frame block → Rust DSP → 六柱。
- 前 2048 样本周期 Hann + 2048 零填充、FFT 尺度与 packed DC/Nyquist、六个实际 bin 分组、幅值求和和非线性映射。
- 对目标几何做 80 ms ease-out，从当前显示状态接续；暂停使用独立 900 ms 过渡。
- 视图隐藏、移除和播放源切换时停止采集；失效 session 的更新不会写入新视图状态。
- 系统级采集初始化失败时，释放已创建的录音器并注销策略，再以旧的普通捕获规则重试一次；两次均保持 UID 过滤、`USAGE_MEDIA`、48 kHz mono Float32。两条路径都失败才播放对应外观的 fallback；已开始采集后的读流/DSP 错误不触发旧路径重试。正常采集到零值时显示低柱。fallback 退出时保留当前显示几何，接续暂停或实时动画。
- iOS 外观：六柱、实时 `5S/48` 基础宽、预制 `S/8` 基础宽、动态增粗和圆角像素取整；封面采用 HSP 亮度处理、饱和度、明暗 scrim、模糊，再以柱形遮罩合成。
- iOS fallback：三种真实轨迹排列、2.66 秒循环、非均匀 keyTimes 的三次 Hermite 插值，第六柱保持最低高度。插值公式经本机公开 QuartzCore presentation 采样核对。
- HyperOS 外观：五柱、固定柱宽与圆角，继承原 Lottie View/Stub 的尺寸、margin 和 gravity。48 kHz 六个 DSP 频段重采样至五个显示柱；无额外改变 DSP 公式。fallback 使用原生 `music_wave` 的高度和渐变轨迹，暂停采用原生高度与反向渐变。
- Hook 原生 `IslandIconViewHolder.getLottieColor(Bitmap)`，读取返回的上下渐变色并原样保留返回值；HyperOS 模式保留系统取色及 60% 不透明度，颜色和外观更新均不重启采集。
- 设置保存在设备保护存储，通过只读 ContentProvider 和 ContentObserver 同步到 SystemUI；读取和封面处理在后台执行。

## MVP boundaries

- iOS 外观按当前逆向规格重建。连续圆角使用 macOS QuartzCore 离屏采样轮廓及插值，模糊使用 Android 端的 Gaussian 近似；Apple 的内部工作色域、模糊核和 iOS 真机合成细节未验证，不能声称逐像素一致。
- iOS fallback cubic 已以本机 macOS 公开 QuartzCore API 的 756 个 presentation 采样点验证；iOS 系统宿主的安装全局相位与实际状态时序仍未验证。
- HyperOS 实时高度在原生动画范围内响应音频，渐变范围随高度映射到原生端点范围；fallback 保留独立的高度/渐变时间曲线。原生 path 关键帧中的亚像素中心微移未单独复刻。
- buffering pulse 仍未接入独立的宿主状态。
- 48 kHz 系统级回环可接收 `ALLOW_CAPTURE_BY_SYSTEM` 音轨，依赖两端 Hook 均加载；Hook 缺失或初始化被拒绝时，自动重试旧的普通 48 kHz Float32 捕获，不降低采样率。旧路径仍受播放器策略限制，Spotify 等仅允许系统捕获的音轨可能返回全零；此时显示低柱，不播放预制动画。`ALLOW_CAPTURE_BY_NONE` 仍由 native 音频策略排除；全零 PCM 无法单独区分真实静音和被策略排除。模块不会据此伪造实时频谱，也不会自动降为全局混音。
- 该路径针对内部格式校验进行受限 Hook，超出 Android 标准 privileged capture 的 16 kHz 格式约束；其他 ROM 的实现和兼容性需要实测。DSP 与读取端保持 48 kHz Float32，没有新增 16 kHz 或升采样路径。
- UID 过滤不是 iOS 的 PID tap，同 UID 的媒体流可能被合并。蓝牙、远程投放、受保护内容、多用户和各音乐应用兼容性尚未全面测试。
- 当前宿主内运行 DSP；native 崩溃会影响 SystemUI。JNI 已检查块长度、处理非有限值并拦截 Rust unwind。

## Validation and debugging

48 kHz 系统级捕获的模拟器验证、9.5/10 kHz 高频输入、捕获策略边界与复现命令见 `docs/full-rate-capture-validation.md`。该验证使用合成音轨复现 Spotify 的系统级捕获限制，尚未验证 Spotify 在真机上的修复效果。

已在上述 AVD 实测 API 102 模块加载、native 加载、UID 过滤采集、六个测试频段的非零响应、实际岛内绘图和测试结束后的 AudioPolicy 注销。当前 AVD 已安装并启用模块。实测记录与录屏索引见 `hyperos-re/mvp-validation.md`。

原生取色接入后，`:app:assembleDebug :app:lintDebug` 通过；在同一 AVD 更新模块并重新加载 SystemUI，日志确认取得多组带 `0x99` alpha 的原生颜色返回值，截图确认岛内六柱显示封面渐变色，音频采集和实时波形继续工作。

`dsp/tests/data/pcm-golden-vectors.json` 来自本仓库 iOS 逆向的七组数值参考输入，保留原始 scope 字段。这些向量是重建的 Accelerate pipeline 结果，不是 Apple 音乐 UI 的动态录制。Rust 测试检查全部 2048 个幅值与六柱值，并额外检查半窗、分段边界、错误长度和非有限输入。

Android lint 的内部 API 反射、动态 native 库加载和单 arm64 支持警告与模块的实现方式有关；lint 没有错误。

性能优化的数值回归、设备配对计时和复现命令见 `docs/performance-validation.md`。封面亮度与模糊在 Rust 中批量处理，仅在 iOS 外观按需生成；连续圆角复用对称采样和不变路径；生产 DSP 只计算六频段使用的幅值，测试保留完整频谱检查。

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
