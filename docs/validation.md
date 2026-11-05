# Validation and debugging

## Test commands

```sh
cargo test --locked -p hyperwave-dsp
cargo test --locked --release -p hyperwave-dsp benchmark_required_bins -- --ignored --nocapture

JAVA_HOME=$(/usr/libexec/java_home -v 21) \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

## What has been verified

Tested on the AVD listed in [compatibility.md](compatibility.md): API 102 module loading, native library loading, UID-filtered capture, non-zero response across six test bands, real in-island drawing, and `AudioPolicy` unregistration after the test session ends. The AVD currently has the module installed and enabled. Records and screen-recording index live in `hyperos-re/mvp-validation.md`.

The synthetic audio source lives in `androidTest` and is used for on-device audio capture regression runs.

After the native color-pick hook was added, `:app:assembleDebug :app:lintDebug` passes. Updating the module on the same AVD and reloading SystemUI produced logs with multiple native color return values carrying `0x99` alpha; screenshots confirm the six island bars take the cover gradient and that capture plus realtime drawing keep working.

Android lint reports warnings about internal-API reflection, dynamic native library loading and single-ABI arm64 support — all inherent to the module's approach. Lint reports no errors.

## Golden vectors

`dsp/tests/data/pcm-golden-vectors.json` holds seven numerical reference inputs from this repo's iOS reverse engineering, with the original scope fields intact. They are reconstructed Accelerate pipeline results, not recordings of Apple's music UI.

The Rust tests check all 2048 magnitudes and the six bar values, plus half-window, segment boundary, wrong-length and non-finite inputs. Production only computes the bins the six bands need; tests still validate the full spectrum and compare production bands against the full-spectrum path.

## Per-area detail

- [full-rate-capture-validation.md](full-rate-capture-validation.md) — 48 kHz system-level capture on the emulator, 9.5/10 kHz high-frequency inputs, capture policy boundaries and reproduction commands. Uses a synthetic track to reproduce Spotify's system-level capture restriction; the fix has not been verified against Spotify on a real device.
- [native-capture-validation.md](native-capture-validation.md) — native read loop blocking, chunking, cancellation and emulator regression.
- [performance-validation.md](performance-validation.md) — numerical regression, paired device timing and reproduction commands.

Artwork brightness and blur are batched in Rust and only generated on demand for the iOS appearance. Continuous corners reuse symmetric samples and unchanged paths. No unbounded album cache or image fingerprinting.

## Logs

```sh
adb -s emulator-5580 logcat -s HyperWave
adb -s emulator-5580 shell 'su -c "/data/adb/modules/zygisk_lsposed/lspctl status"'
```

Module loading and plugin hook information also go to the LSPosed module log. Audio logs contain the target UID, sample format, block peak and the six bar values; raw audio is never written.
