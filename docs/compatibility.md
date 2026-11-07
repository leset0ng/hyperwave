# Compatibility and limitations

## Validated environment

| | Physical device | Emulator |
|---|---|---|
| Device | Xiaomi 17 Pro (`pandora`, arm64) | `hongkong` AVD (arm64) |
| ROM | HyperOS `OS4.0.4.0.XBLCNXM` | HyperOS `OS4.0.24.0.XFRCNXM` |
| Android | 17, API 37 | 17, API 37 |
| Framework | LSPosed `2.2.1 (7939)` | LSPosed `2.2.1 (7930)` |

Both use Xposed API `102`, scoped to `com.android.systemui` and `system` (system_server).

Other HyperOS versions may use different class names, templates and audio policies and are untested.

Build toolchain: JDK 21, Android SDK 37, Android NDK `30.0.15729638`, Rust stable with the `aarch64-linux-android` target.

## MVP boundaries

- **iOS appearance** is rebuilt from the reverse-engineered spec. Continuous corners use macOS QuartzCore offscreen-sampled contours and interpolation; blur is an Android-side Gaussian approximation. Apple's internal working color space, blur kernel and on-device compositing are unverified — this is not pixel-identical.
- **iOS fallback** cubic was verified against 756 presentation samples from the public QuartzCore API on macOS. The iOS host's global phase offset and real state timing are still unverified.
- **HyperOS realtime bars** respond to audio within the native animation's height range, and the gradient range maps onto the native endpoints. Fallback keeps its own independent height/gradient curves. The sub-pixel center drift inside the native path keyframes is not reproduced.
- **`buffering` pulse** is not wired to a separate host state yet.
- **48 kHz system-level loopback** receives `ALLOW_CAPTURE_BY_SYSTEM` tracks and depends on both hooks loading. A missing hook or rejected initialization retries once with ordinary non-privileged 48 kHz Float32 capture — the sample rate is never reduced. The legacy path is still constrained by the player's policy: system-only tracks such as Spotify may return all zeros, which shows low bars instead of the pre-rendered animation. `ALLOW_CAPTURE_BY_NONE` is still excluded by the native audio policy, and all-zero PCM cannot distinguish real silence from a policy-excluded stream. The module does not fake a spectrum and does not silently drop to global mix capture.
- **Format exception**: the hooks relax Android's standard privileged-capture <=16 kHz constraint only for the tagged internal request. Compatibility and behavior on other ROMs need real-device testing. DSP and the reader stay at 48 kHz Float32; there is no 16 kHz path and no resampling stage.
- **UID filtering** is not iOS's PID tap, so media streams from the same UID can be merged. Bluetooth, remote/CAST playback, protected content, multi-user setups and per-app compatibility are not fully tested.
- **DSP runs inside the host process**; a native crash takes SystemUI down with it. JNI checks block lengths, rejects non-finite values and catches Rust unwinds.
