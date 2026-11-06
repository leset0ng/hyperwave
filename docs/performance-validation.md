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
  io.github.leset0ng.hyperwave.test/io.github.leset0ng.hyperwave.ui.PerformanceInstrumentation
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

## Redundant-work follow-up

- Waveform updates skip a transition and frame request only when the previous transition has ended, fallback is not being displayed directly, and all 24 presented geometry components exactly match the next geometry. There is no epsilon or quantization. Repeated targets during an active animation retain the previous restart behavior, interpolation, and duration. Fallback exit, pause, and appearance changes retain their own update paths.
- Appearance notifications share at most one queued settings read. The worker clears the queued marker before querying, allowing notifications during a query to queue one follow-up. Queries read the provider's latest state; UI preference persistence and slider feedback remain immediate. Hot reload resets the marker when retiring workers.

The temporary 22dp production-View probe delivers 60 identical bar arrays at nominal 85 ms intervals after a 500 ms settling period, through the actual `onBars` callback, without AudioRecord. Before this change, identical zero and constant nonzero inputs each caused **610 submitted window frames** over about 5.12 seconds. After the change, both inputs caused **0 submitted window frames** for **all three appearances (HyperOS, iOS, MiOS)**. Single-input controls also remained at zero frames. Host scheduling extended the follow-up measurement windows to approximately 5.5–6.9 seconds; the observation is elimination of redundant submitted frames, not a CPU or battery-saving percentage.

Five additional JVM regressions cover settled equality, repeated targets during an active animation, interruption at the current presentation, fallback returning to the previous target, and preservation of a one-ULP geometry change. The full follow-up build passed all 68 JVM tests and 11 Rust tests (one timing-only test ignored), APK/test APK assembly, and Android lint.

- Backdrop preparation retains one immutable 96x96 base pixel array (36 KiB) per current holder/source/Icon, alongside the existing two final-result slots. Icon/source changes clear the base. Full-source copying, brightness scanning, saturation, cropping and scrims run once; each size retains the original radius and integer blur. The base does not retain the caller's bitmap and each render copies its pixels into thread-local scratch before blur. Generation checks still gate publication on the main thread.
- Flip drawing retains the original alpha layer, perspective, clipping, blur creation, and PLUS glow. Only exact `BitmapDrawable` instances reuse their RenderNode display list, with drawable identity, actual integer drawing bounds, view size, bitmap generation and display-list validity checked. Other drawables are recorded every time. Settling/hiding/detaching discards the display list. The public API 33 `ValueAnimator.getDurationScale()` supplies the framework's animation scale without a per-frame settings lookup.

Device component checks passed exact backdrop pixel comparisons against `ReferenceArtwork` for both sizes in both orders, repeated renders, software/hardware sources, transparent and rectangular images, caller-source recycling, and non-bitmap drawable bounds. The two-size paired benchmark compares two independent full preparations against one shared preparation plus two renders, excluding Icon loading:

| Two-size operation | Independent preparation | Shared preparation | Speedup |
| --- | ---: | ---: | ---: |
| 128x128 source | 494.167 us | 386.417 us | 1.28x |
| 1024x1024 source | 3538.458 us | 1905.334 us | 1.86x |

The artwork flip runner also passed 23 deterministic hardware pixel comparisons against a test-only copy of the original renderer. Cases include translucent patterned bitmaps, three aspect ratios, rotation, partial opacity, blur and glow, reuse across effect changes, in-place bitmap changes and changing non-cached drawables. Every compared pixel matched exactly. Existing latest-image, rounded-corner and hide/detach lifecycle checks also passed. The models are frozen for these pixel comparisons; animation evolution is covered by JVM model tests. Dynamic changes to the system animator scale and a live injected SystemUI session were not exercised by these tests.

## Unchanged host updates and exact fallback reuse

- `WaveformView.configure()` returns when both UID and playback state match. Waveform and flipping artwork visibility setters return when host visibility matches. Actual configuration/visibility changes retain the previous transition, capture reconciliation and invalidation paths; parent/window visibility and attachment callbacks retain their separate lifecycle handling.
- HyperOS fallback evaluates tracks 0 through 3, then copies track 1's level and gradient endpoints to the identical track 4. Every arithmetic operation of the retained tracks, easing iteration count, timing and output format is unchanged. This removes two 18-iteration Bezier solves per sample (20% of this helper's solver calls, not 20% of total CPU).

The same temporary 22dp production-View probe used in the read-only review delivered 60 unchanged `configure(-1, true)` calls through a main-thread Handler after settling. The review baseline submitted 120 window frame callbacks in 5.17 seconds; the optimized version submitted **0** in 8.67 seconds. Idle and empty-main-post controls both submitted **0** (9.30 and 9.68 seconds). Emulator scheduling extended the later run; these observations count redundant window frame callbacks and do not establish host bind frequency, energy savings or a frame-time speedup. No AudioRecord was started; the temporary probe was uninstalled after measurement.

The `waveform_lifecycle` device runner checks all three appearances for unchanged pause/live configuration, unchanged visible/hidden notifications, transition continuity, hide/show, detach/reattach, parent visibility restoration, paused geometry endpoints, and independent palette/artwork invalidation. Its settled live state is injected to avoid privileged recording services. The fallback JVM differential test compares all 16 scalar outputs by raw float bits against the frozen original implementation over 24,386 elapsed-time inputs, including full cycles, segment boundaries, wrap points, large timestamps and deterministic random values.

```sh
adb -s emulator-5580 shell am instrument -w -e artwork_hooks true \
  io.github.leset0ng.hyperwave.test/io.github.leset0ng.hyperwave.ui.PerformanceInstrumentation
adb -s emulator-5580 shell am instrument -w -e waveform_lifecycle true \
  io.github.leset0ng.hyperwave.test/io.github.leset0ng.hyperwave.ui.PerformanceInstrumentation
adb -s emulator-5580 shell am instrument -w -e artwork_flip true \
  io.github.leset0ng.hyperwave.test/io.github.leset0ng.hyperwave.ui.PerformanceInstrumentation
```

- Artwork metadata preparation retains its original event-time snapshot, including while flipping is disabled. A complete disabled-state bypass was not applied: resolving a media ID only when enabling could associate an older delivered image with the next track's metadata. Instead, one `MediaController` is retained for the most recent equivalent session token, with synchronized replacement and retirement. Every event still calls `getMetadata()`; metadata values are not cached and no controller callbacks are registered. The device test uses actual `MediaSession` objects to check fresh IDs for identical title/artist, equivalent parceled tokens, session switching, disabled/re-enabled operation, concurrent access and retirement.
- Artwork `onBound()` keeps its current LayoutParams when absolute size, gravity, all four margins and layout direction match. Either side having relative margins retains the original copy/assignment path because their hidden unresolved margin state cannot be compared through public getters alone. Device checks cover changed absolute fields, RTL/relative margins, unchanged bind object identity and actual changed bind propagation.

The libxposed API is included only in the test APK to construct an inert `ReloadableHooks` for these checks; no framework hooks are installed. These tests do not execute the injected metadata hook or validate native host bind frequency. Package UID and reflection lookup caches were left out because their measured benefit is still unknown and they require additional invalidation/lifetime handling.

Validation for this follow-up passed: 69 JVM tests (zero skipped/failures/errors), debug APK/test APK assembly, Android lint, the three-style waveform lifecycle runner, actual-session/artwork-bind checks, the 23 deterministic hardware flip pixel comparisons and latest-image/corner/hide/detach checks, plus the existing backdrop/JNI/corner/concurrent-audio component runner. The repeated visible/hidden artwork notifications also remained clean without requesting drawing after settling. No Rust production code changed. All device validation used the HyperOS arm64 emulator's application test process; a live SystemUI injection and real-device power/latency measurement remain unverified.
