# Full-rate privileged playback capture validation

Validated on 2026-11-05 using `emulator-5580` (hongkong, HyperOS / Android 17 API 37, arm64, LSPosed 2.2.1 build 7930 / API 102). The physical phone was inspected during diagnosis but was not updated with this implementation.

## Problem and implementation

The physical phone's Spotify stream had effective audio flags `0x600`, including `FLAG_NO_MEDIA_PROJECTION` (`0x400`). Hyperwave's original non-privileged 48 kHz loopback registered successfully but received zero PCM. The existing implementation treated those buffers as valid silence, leaving the bars at their minimum heights.

The module now requests privileged playback capture while keeping its existing 48,000 Hz mono Float32 reader, 4096-frame blocks, JNI and Rust DSP. There is no added 16 kHz capture or resampling stage.

Android normally restricts privileged capture to <=16 kHz mono linear PCM with <=2 bytes/sample. Hyperwave hooks the format validator in SystemUI and system_server, but only within a synchronous operation matching all of these conditions:

- SystemUI is the caller; system_server resolves and checks the actual SystemUI UID.
- The mix is a player mix using `RENDER | LOOP_BACK`.
- Exactly two inclusion criteria match one application UID and `USAGE_MEDIA`.
- The media rule contains only the `dev.hyperwave.capture.48k.v1` tag.
- Privileged media capture is requested; voice communication capture is not.
- The format is exactly 48 kHz, mono output channel mask, Float32.
- The validator receives the same format object that was inspected on the current thread.

The thread-local exception is restored in `finally`, including nested calls and exceptions. `AudioService.isPolicyRegisterAllowed()` still executes its original permission checks. The system_server builder hook also covers AudioMix parcel reconstruction, which performs format validation before AudioService registration.

Modern LSPosed uses `system` as the system_server scope identifier. `android` did not load the server hooks on this emulator. Both `system` and `com.android.systemui` were enabled by the user, who performed the reboot. Logs confirmed hook installation in both processes.

## Legacy recording fallback follow-up

A subsequent change adds one retry using the original non-privileged capture rules when privileged setup is rejected, including a missing system_server hook. Each attempt retains UID/media filtering, 48 kHz mono Float32, and the same DSP. Only the privileged attempt adds the rule marker.

The first attempt must finish releasing its recorder and unregistering any registered policy before retry begins. A stopped session, incomplete cleanup, fatal error, or error after recording has started does not start a legacy attempt. The renderer is notified of unavailability only after both setup attempts fail (or a non-retryable error occurs). The old path may successfully record zeros for system-only streams; this retains the existing silence behavior. A failure to install SystemUI full-rate hooks no longer prevents native DSP and island hooks from loading, so legacy recording can still be attempted.

Eight focused JVM tests cover successful privileged setup, cleanup-before-retry ordering, two failures, cancellation before/during retry, errors after recording starts, incomplete cleanup, fatal errors, and repeated exception instances. With the follow-up, all 26 JVM tests, both APK builds and lint passed. This follow-up has not been installed or exercised on the emulator; the runtime results and hashes below describe the earlier privileged-capture build.

## Device results

The instrumentation source sets both AudioManager's process policy and the AudioTrack's policy, then plays a controlled 48 kHz Float32 tone or frequency sweep. Each instrumentation run lasts 27 seconds and stops its service afterward. Tests do not navigate the UI or reboot the device. The emulator must already be on the home screen with the island visible.

| Effective source policy | Input | Result |
| --- | --- | --- |
| `ALLOW_CAPTURE_BY_SYSTEM` | 10,000 Hz | 48 kHz mono Float32 capture; peak up to `0.070007324` |
| `ALLOW_CAPTURE_BY_SYSTEM` | 9,500 Hz | Peak up to `0.070007324`; sixth bar up to `0.24337924`; all other bars below `0.00113` |
| `ALLOW_CAPTURE_BY_NONE` | 9,500 Hz | All 13 periodically logged blocks had zero peak and six zero bars |
| `ALLOW_CAPTURE_BY_ALL` | 45 / 120 / 320 / 900 / 2800 / 6500 Hz sweep | All six bands responded; per-band maxima `[0.31746292, 0.3102354, 0.30110568, 0.27691832, 0.2598081, 0.24274422]` |

The 9.5 kHz input is inside the analyzer's highest band and above a 16 kHz capture path's 8 kHz Nyquist limit. The 10 kHz input is slightly above the analyzer's approximately 9.984 kHz upper band boundary, so its sixth-bar value is not used as the in-band response assertion.

During the system-only test, native AudioPolicy reported an active UID 10309 media `RENDER | LOOP_BACK` mix, a 48 kHz Float32 AudioRecord client, and an effective capture policy mask of `0x400`. The none-policy test reported `0x1400`, including `NO_SYSTEM_CAPTURE`, and remained excluded. After the final test, no Audio Policy Mix remained and the test service was stopped. The final sweep restored the normal test policy to `ALLOW_CAPTURE_BY_ALL`.

## Verification and reproduction

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug

# Install both APKs, enable system + com.android.systemui, reboot, then stay on Home.
adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

adb -s emulator-5580 shell am instrument -w -e capture_policy 2 -e test_frequency 10000 \
  dev.hyperwave.test/dev.hyperwave.ui.PerformanceInstrumentation
adb -s emulator-5580 shell am instrument -w -e capture_policy 2 -e test_frequency 9500 \
  dev.hyperwave.test/dev.hyperwave.ui.PerformanceInstrumentation
adb -s emulator-5580 shell am instrument -w -e capture_policy 3 -e test_frequency 9500 \
  dev.hyperwave.test/dev.hyperwave.ui.PerformanceInstrumentation
adb -s emulator-5580 shell am instrument -w -e capture_policy 1 \
  dev.hyperwave.test/dev.hyperwave.ui.PerformanceInstrumentation

adb -s emulator-5580 logcat -d -v threadtime Hyperwave:I '*:S'
adb -s emulator-5580 shell dumpsys media.audio_policy
```

All 18 JVM tests passed, with zero failures/errors; debug and instrumentation APK builds and lint passed. The three new scope tests cover exact format identity, nested/exception restoration, and thread isolation. A non-mutating independent review caught a call to package-private `getTargetMixType`; it was replaced with public `getTargetMixRole` before successful runtime verification.

Instrumentation's `capture_source` result only indicates source playback completed. Capture success was checked separately from SystemUI PCM logs, including nonzero peaks, band responses, recording format and cleanup. A direct validator check from the unrelated instrumentation process could not run because hidden-API filtering hides the method; this is reported as unexercised, not a passing format-limit assertion.

Artifacts used:

- Module APK SHA-256: `4d81941260cbf25dabb561543ca7ced068ae3ca223113920eb0b0ffeee911f52`
- Instrumentation APK SHA-256: `4ec9a0e981ec2007afd130c85864895b46ca8e7e30619a5d2b73993a6eda98d7`
- Local raw evidence: `hyperos-re/evidence/full-rate-capture.log`, `full-rate-active-policy.txt`, `full-rate-none-policy.txt`, `full-rate-cleanup-policy.txt`, `full-rate-cleanup-services.txt`, `full-rate-hooks.log`.

## Limits

This validates the mechanism with synthetic streams matching Spotify's capture restriction. Spotify itself, Bluetooth routing on the physical phone, other ROMs, concurrent unrelated sources and vendor-specific native restrictions were not tested after the change. No claim of universal application compatibility is made.

The AudioRecord client is Float32, but the emulator's remote-submix HAL/mixer reports PCM16 internally. The test confirms preservation of the 48 kHz sampling rate and response above 8 kHz, not an end-to-end bit-perfect Float32 audio path. Existing DSP formulas and rendering behavior are unchanged. `ALLOW_CAPTURE_BY_NONE` continues to produce zeros rather than triggering fallback, because zeros alone cannot distinguish exclusion from silence.
