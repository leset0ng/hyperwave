# Performance validation

## Changes

- Prepare artwork only for the iOS appearance. Retain the two 96x96 results for logical sizes 16 and 22 on the current holder/source/Icon; cancel superseded tasks with generation tokens. A newly delivered Icon instance invalidates this small cache, even if its pixels are identical. There is no unbounded album cache or expensive image fingerprinting.
- Compute full-source HSP brightness and three separable box-filter passes in Rust. Transfer pixels in reusable 64K-pixel tiles rather than reading one row at a time. Keep source pixel order, f64 accumulation, integer blur rounding, Canvas saturation, crop, and scrim order. The artwork mutex/workspace is independent of audio DSP.
- Compute symmetric continuous-corner edge samples once and retain one Path per bar. Rebuilding still emits the original 194 line segments; unchanged bounds/radius reuse the path. No atlas values or sampling density changed.
- Compute only FFT magnitudes 0..851 in production. Reference tests still compute and validate all 2048 magnitudes and compare production bands against the full-spectrum path. FFT size and scaling remain unchanged.

## Checks

```sh
cargo test --locked -p hyperwave-dsp
cargo test --locked --release -p hyperwave-dsp benchmark_required_bins -- --ignored --nocapture

JAVA_HOME=$(/usr/libexec/java_home -v 21) \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug

android install --device=emulator-5580 --apks=app/build/outputs/apk/debug/app-debug.apk
android install --device=emulator-5580 --apks=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w \
  ng.lst.hyperwave.test/ng.lst.hyperwave.ui.PerformanceInstrumentation
```

The device runner returns `INSTRUMENTATION_CODE: -1` and a `checks=PASS` result on success. A failure returns code 0 with a failure stack trace. It runs in the app test process; it does not restart SystemUI or reload the injected module. `ReferenceArtwork` and `ReferenceCorners` retain the old algorithms exclusively in the test APK, for paired comparisons on the same runtime.

Results from 2026-11-05:

- Rust: 8 tests passed; the timing-only test is ignored by default and passed when explicitly invoked. Seven PCM golden vectors validate every full-spectrum magnitude and six-band value.
- Android JVM: 15 tests passed, including atlas-boundary, randomized coordinate, and floating-point join regression tests.
- APK and test APK builds passed. Android lint passed with warnings and no errors.
- Device runner passed exact artwork pixel comparisons for logical sizes 16 and 22, software and hardware inputs, rectangular images, dark/light images, and alpha-bearing images. Caller-owned sources remained valid.
- Device runner passed JNI invalid-size/radius/nonfinite-accumulator checks and verified that blur does not overwrite the array tail.
- Device runner passed 1000 randomized Android Path comparisons, unchanged-path identity, invalid-bounds clearing, and zero-radius rectangles.
- Audio DSP and artwork blur both completed concurrently with valid outputs.
- Independent source review found no concrete defects in artwork lifecycle, ownership, numerical kernels, or JNI bounds. Rapid appearance switching inside the live SystemUI host was not exercised by this test runner.

## Paired timings

Device: `hongkong` arm64 emulator, HyperOS `OS4.0.24.0.XFRCNXM`, Android API 37. Debug Kotlin APK, release Rust library. Each measurement uses 8 warmup pairs and 11 measured pairs, alternates old/new order, and reports the median. Artwork measurements include full bitmap processing and result recycling, including pixel transfers and JNI, but exclude Icon loading. Animated-path measurements rebuild six changing paths; unchanged-path measurements reuse identical bounds/radii. Path measurements exclude Canvas rasterization and GPU work.

| Operation | Reference | Optimized | Speedup |
| --- | ---: | ---: | ---: |
| 128x128 artwork, logical size 16 | 1473.209 us | 259.750 us | 5.67x |
| 1024x1024 artwork, logical size 16 | 27139.750 us | 1908.250 us | 14.22x |
| Six changing corner paths | 29.139 us | 24.203 us | 1.20x |
| Six unchanged corner paths | 29.304 us | 1.030 us | 28.44x |

DSP paired release timing on the macOS arm64 development host (`rustc 1.98.0`): median of 11 measured batches after two warmup batches, 4000 blocks per batch, alternating order. Full-spectrum path: **9.8335 us/block**; required-bin path: **8.6804 us/block**, **1.13x** speedup. Both include the same 4096-point FFT and band mapping. This is a host measurement, not an Android DSP benchmark.

These are component timings from one environment, not end-to-end frame-rate, battery, or real-device guarantees. The emulator's Java compilation state can materially affect ratios. No timing threshold is asserted by the tests. The physical device was unauthorized and was not used.
