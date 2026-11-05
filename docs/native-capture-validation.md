# Native capture loop

## Implementation

`PlaybackCapture` retains the existing UID/media AudioPolicy setup, 48,000 Hz mono
Float32 AudioRecord, privileged-to-legacy initialization fallback, audio-thread
priority and recorder/policy cleanup. After recording starts, it enters
`NativeDsp.capture()` once for the lifetime of that capture attempt.

Rust owns a stable 4096-float PCM allocation and a separate analyzer per session.
It calls the public `AudioRecord.read(ByteBuffer, sizeInBytes, READ_BLOCKING)`
overload through cached JNI method IDs. The direct ByteBuffer references that
native allocation, so PCM is not copied into a JVM float array or passed back
through `GetFloatArrayRegion` for each FFT. The existing `analyze(FloatArray, ...)`
entry remains available for component checks and numerical comparisons.

The reader still uses AudioRecord's Java/native entry; this does not inject into
audioserver or use private libaudioclient ABI. Native code owns the streaming loop,
block accumulation and FFT. Only the six-band array and periodic peak/block
metadata are delivered to Kotlin. Each delivered six-band array is distinct so a
queued UI update cannot observe the next block overwriting it. Method IDs are
cached. Each read creates a direct ByteBuffer view from the current mutable PCM
slice and deletes its JNI local reference before the slice borrow ends. Views
are not cached across Rust mutable borrows; the backing PCM allocation is reused.
Per-block JNI local array references are also deleted before the next iteration.

AudioRecord's direct-buffer overload counts **bytes** and ignores the buffer's
position. Each read creates a temporary direct view of the unfilled native
region. Reads must contain whole Float32 samples and must fit the remaining
capacity. No partial block is analyzed. The sample rate, 4096-frame hop, first-2048
Hann window, zero padding, band mapping and 80 ms geometry transition are unchanged.

Cancellation reads Kotlin's volatile stopped flag through the callback before
and after each read. `AudioRecord.stop()` unblocks the read from the stopping
thread. A stopped read does not publish data or report a read-status failure.
The capture thread then releases AudioRecord and unregisters its AudioPolicy in
the existing `finally` block. Java read/callback exceptions remain pending and
propagate to that cleanup; native errors become `IllegalStateException`, and Rust
unwind is caught at the JNI boundary. Streaming errors retain the existing rule
that they must not start the initialization fallback.

## Automated checks

```sh
cargo test --locked -p hyperwave-dsp
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Native capture tests exercise multiple partial reads across consecutive blocks,
compare their band values with the existing analyzer, reject empty/error/unaligned/
oversize reads, and cancel before a read or while it returns an error, a partial
block or a full block. Existing Rust tests retain all Apple reconstruction vectors.
The JVM fallback tests retain their cleanup, cancellation and no-retry assertions.

Results on this change: 11 Rust tests passed; the manual timing benchmark remained
ignored. All 26 JVM tests passed, and both debug APK builds and Android lint
completed successfully. Independent review of the final JNI buffer lifetime
repair found no remaining demonstrable issue in that scoped change.

## Runtime validation

Use the existing HyperOS emulator with the module enabled for SystemUI and system.
After installing the APKs, reload SystemUI, dismiss the emulator keyguard, and
ensure the launcher and music island are visible. A hidden host intentionally does
not start capture. Run these instrumentation commands **sequentially**, waiting for each
to finish; starting a second instrumentation in the same application terminates
the earlier source process.

```sh
adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell 'su -c "killall com.android.systemui"'
adb -s emulator-5580 shell wm dismiss-keyguard
adb -s emulator-5580 shell input keyevent KEYCODE_HOME

adb -s emulator-5580 shell am instrument -w \
  ng.lst.hyperwave.test/ng.lst.hyperwave.ui.PerformanceInstrumentation
adb -s emulator-5580 shell am instrument -w -e capture_policy 1 \
  ng.lst.hyperwave.test/ng.lst.hyperwave.ui.PerformanceInstrumentation
adb -s emulator-5580 shell am instrument -w -e capture_policy 2 -e test_frequency 9500 \
  ng.lst.hyperwave.test/ng.lst.hyperwave.ui.PerformanceInstrumentation
adb -s emulator-5580 shell am instrument -w -e capture_policy 3 -e test_frequency 9500 \
  ng.lst.hyperwave.test/ng.lst.hyperwave.ui.PerformanceInstrumentation

adb -s emulator-5580 logcat -d -v threadtime HyperWave:I '*:S'
adb -s emulator-5580 shell dumpsys media.audio_policy
```

A completed source instrumentation is not sufficient evidence of capture. Confirm
`reader=native`, PCM peaks and band values in a SystemUI session that starts after
the current `test playback policy=...` marker, then confirm `capture stopped` and
no remaining Audio Policy Mix. Ignore earlier sessions in the log buffer. The excluded-policy
case must produce zero PCM rather than a fallback animation.

## Recorded emulator results

Final build: `hongkong`, HyperOS `OS4.0.24.0.XFRCNXM`, Android API 37, arm64.
Each completed case logged 13 periodic PCM blocks from a new `reader=native`
session after its own source marker. No capture failure/fallback was logged, and
each session stopped and left no Audio Policy Mix.

| Source policy | Input | Observed result |
| --- | --- | --- |
| `ALLOW_CAPTURE_BY_ALL` | 45 / 120 / 320 / 900 / 2800 / 6500 Hz sweep | Six band maxima `[0.3172824, 0.31087166, 0.3025184, 0.27873275, 0.25826865, 0.24194328]` |
| `ALLOW_CAPTURE_BY_SYSTEM` | 9500 Hz | Peak `0.070007324`; sixth band up to `0.24337879`; other bands below `0.00116` |
| `ALLOW_CAPTURE_BY_NONE` | 9500 Hz | All logged peaks and all six bands remained zero |

Elapsed log time divided by the intervening block count was approximately
85.26–85.38 ms/block across these cases. These are averages between periodic log
entries, not measurements of every callback's jitter or audio-to-screen latency.
The source-only instrumentation's unrelated-process hidden format check remained
inaccessible and is not counted as a passing check.

Local raw evidence is stored in `hyperos-re/evidence/native-capture-*` (ignored
research artifacts): source instrumentation results, SystemUI PCM logs, post-stop
AudioPolicy dumps and a machine-readable case summary. The existing component
instrumentation also passed artwork, native bounds, corner paths and concurrent
DSP checks during this change.

The following APK hashes identify the build used for final runtime checks:

- Module APK SHA-256: `c0392f3fde198ce2f1b0b3931eb0e5625c5abddfbe46ddada0b59f8f26ae5f05`.
- Instrumentation APK SHA-256: `4ec9a0e981ec2007afd130c85864895b46ca8e7e30619a5d2b73993a6eda98d7`.

## Performance limits

This removes the 4096-float Java PCM array and its per-block transfer into Rust,
and avoids the shared analyzer mutex in the streaming path. A block is 16 KiB;
at the nominal 11.71875 blocks/s cadence, the removed Java-to-Rust PCM transfer
alone accounts for 192,000 bytes/s. AudioRecord's direct-buffer entry and stop-state
callbacks still cross JNI, and each read creates a ByteBuffer wrapper. No
before/after CPU, allocation profiler or end-to-end
latency measurement is asserted by this change. It does not reduce the existing
85.33 ms audio block interval or establish parity with an iPhone's total latency.
