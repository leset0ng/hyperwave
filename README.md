# HyperWave

让 HyperOS 音乐超级岛的波形，跟着你正在听的歌跳动。

HyperWave 是一个 LSPosed 模块，把音乐超级岛右侧的预制动画换成音频驱动的实时频谱。三种外观可选：iOS 六柱、HyperOS 原生五柱，以及结合六柱几何与 HyperOS 渐变的 MiOS。

- **真实频谱**：48 kHz 单声道 Float32 系统级回环，Rust 侧 FFT 分析，六频段实时映射到柱高
- **三种外观**：iOS / HyperOS / MiOS，设置里实时切换，无需重启系统界面
- **原生交互不变**：岛容器、封面、点击行为全部沿用 HyperOS，只替换波形图标

```text
音乐播放 → UID 过滤的 AudioRecord 回环 → Rust FFT → 六柱值 → Canvas 绘制
```

Android 接入层使用 Kotlin，DSP 使用 Rust，基于 **libxposed API 102**，Android 工程直接位于仓库根目录。

## 环境要求

- HyperOS 设备，已安装 LSPosed，运行时 Xposed API 102
- 作用域：**系统界面 / `com.android.systemui`** 与 **系统框架 / `system`**

当前验证目标为 Android 17 (API 37) arm64 的 HyperOS `OS4.0.24.0.XFRCNXM`，其他版本尚未验证。详见 `docs/compatibility.md`。

## 构建

```sh
rustup target add aarch64-linux-android
cargo test --locked -p hyperwave-dsp

# macOS, if the default Java is not JDK 21:
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Gradle 会自动运行 `scripts/build-rust.sh`，把 Rust `.so` 打进 APK。NDK 路径可用 `ANDROID_NDK_HOME` 指定，SDK 用 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME`。产物在 `app/build/outputs/apk/debug/app-debug.apk`。

需要 JDK 21、Android SDK 37、Android NDK `30.0.15729638`、Rust stable 与 `aarch64-linux-android` target。

## 安装

1. 安装 APK，在 LSPosed 中启用 HyperWave，勾选**系统界面**与**系统框架**两个作用域。系统进程的作用域标识是 `system`，不是 `android`。
2. 首次启用作用域，或从不支持热重载的旧版升级时，重启设备。支持热重载的版本运行后，更新 APK 会通过 API 102 请求各目标进程替换 Hook，无需常规重启；需要框架实现 API 102 的热重载能力。
3. 打开 HyperWave 选择外观，用“频谱高度”滑块调整柱高（50%–200%，默认 150%）。设置自动保存并实时生效。
4. 播放音乐并回到桌面，波形开始跟随音频。

桌面入口可以在设置里隐藏，需要时从 LSPosed 的模块页面进入设置。热重载会停止旧代采集、释放旧 View 与监听器，并在新代恢复现有音乐岛；旧线程未能退出时会拒绝本次重载。框架不支持、拒绝或重载失败时，仍需重启相应进程；涉及 `system_server` 时重启设备。停用或卸载后同样需要重启，才能退出已注入 `system_server` 与 SystemUI 的代码。详见 `docs/hot-reload.md`。

## 文档

- `docs/hot-reload.md` — API 102 热重载、首次升级与验证范围
- `docs/architecture.md` — 音频链路、Hook 设计、DSP 与三种外观的绘制实现
- `docs/compatibility.md` — 已验证环境、兼容性与 MVP 边界
- `docs/validation.md` — 验证结论、golden vectors、日志抓取
- `docs/full-rate-capture-validation.md`、`docs/native-capture-validation.md`、`docs/performance-validation.md` — 各专题的实测记录与复现命令

## 目录结构

```text
app/            Android app, modern Xposed entry, capture and renderer
  src/main/resources/META-INF/xposed/
dsp/            Rust DSP + JNI entry + numerical reference tests
scripts/        Native build script
gradle/         Gradle wrapper
docs/           Architecture, compatibility and validation records
hyperos-re/     APK samples, decompilation and AVD validation evidence (local)
ios-re/         Existing Apple algorithm and rendering research (local)
```

`hyperos-re/` 与 `ios-re/` 中的原始样本和反编译产物不参与 APK 编译；DSP 数值测试使用独立复制的参考向量。
