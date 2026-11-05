# Architecture

## Pipeline

```text
SystemUI / MIUISystemUIPlugin
    → musicWave / musicPause hooks
    → UID-filtered AudioPolicy + AudioRecord
    → 48 kHz mono Float32, native direct-buffer read loop, 4096-frame blocks
    → Rust Hann + FFT + six-band response → JNI six-bar delivery
    → Canvas geometry (iOS / MiOS six bars / native HyperOS five bars) + interruptible transitions
```

Kotlin owns the Android integration: module registration, hooks, audio policy setup, `AudioRecord` lifetime and Canvas drawing. Rust owns the audio-thread read loop, the FFT and the six-band mapping. PCM never enters a JVM array; only the six bar values cross JNI.

## Module and hooks

- API 102 module registration with `XposedModule.onModuleLoaded` / `onPackageReady`, `hook(...).intercept(...)` and `META-INF/xposed/` configuration. No legacy XposedBridge API. The only Xposed dependency is `compileOnly("io.github.libxposed:api:102.0.0")`.
- API 102 hot reload is enabled with `autoHotReload=true`. The old generation pauses interceptors, unregisters its settings observer, removes its views while restoring native child visibility, and waits for every capture session and background executor to exit. Saved state uses bootstrap collections and host/framework objects only. The new generation explicitly reinstalls capture and plugin-loader hooks, atomically replaces matching executable/ID pairs through `HookHandle.replaceHook`, unhooks obsolete handles, and recreates existing music-island views. Initial load and package callbacks are not replayed by the framework.
- Native hot reload loads a distinct read-only file copy per new ClassLoader from SystemUI's device-protected code cache. A generation deletes only its own copy after its JNI work exits; ART retains responsibility for library unloading. See `hot-reload.md` for migration and validation limits.
- SystemUI handles UID audio loopback and drawing; system_server only relaxes the 48 kHz Float32 privileged capture format check for the module-tagged music loopback, keeping its original permission validation.
- Scopes: `com.android.systemui` and the system framework `system` (system_server).
- SystemUI plugin `ClassLoader` discovery for `MIUISystemUIPlugin`.
- Only the music template animation is replaced. Other island events and static icon states are left untouched.
- `IslandIconViewHolder.getLottieColor(Bitmap)` is hooked to read the native top/bottom gradient colors while returning the original value unchanged. HyperOS and MiOS modes keep the system colors and their 60% opacity; color and appearance updates never restart capture.

## Audio capture

- Loopback capture matched on player UID and `USAGE_MEDIA`; `RENDER | LOOP_BACK` keeps the original playback output intact.
- Privileged 48 kHz mono Float32 capture is allowed by a coordinated two-process exception: a dedicated rule tag, a single application UID, `USAGE_MEDIA` only, `RENDER | LOOP_BACK` only. The format exception applies only inside the matching operation on the current thread, and system_server additionally verifies that the caller is SystemUI.
- The rule tag is what the hooks key on; unrelated capture requests keep the stock format validation.
- If system-level capture setup fails, the module releases the recorder, unregisters the policy and retries once with the previous non-privileged capture rule. Both attempts keep UID filtering, `USAGE_MEDIA` and 48 kHz mono Float32. Only after both fail does the renderer fall back to the pre-rendered animation. Read/DSP errors after capture has started do not trigger the legacy retry.
- Each capture session owns its PCM and FFT workspace. Stopping marks the session cancelled and starts one per-session stop worker, so hiding the island never waits for audio services on the UI thread. Recorder publication/start, stop and release share a background-only lock; the blocking read never holds it. The reader checks the stop flag after the read returns, then releases the recorder and unregisters the policy. A session remains active until its stop worker has also exited, so hot reload waits for both threads.
- Zero-valued capture shows low bars rather than a fake animation, so real silence and policy-excluded silence look the same.

## DSP

- Periodic Hann window over the first 2048 samples, zero-padded to 2048, FFT scaling with packed DC/Nyquist.
- Six real bin groups, magnitude summation, non-linear mapping to bar heights.
- Native code calls the public `AudioRecord.read(ByteBuffer, int, int)` overload through cached JNI method IDs, reading into a stable 4096-float allocation. Whole Float32 samples only; no partial block is analyzed.
- Each delivered six-band array is a distinct allocation, so a queued UI update cannot observe the next block overwriting it. JNI local references are deleted before the slice borrow ends.
- Only bins 0..851 are computed in production; reference tests still compute and validate all 2048 magnitudes.

## Rendering

- Geometry transitions run 80 ms ease-out from the currently displayed state; pause uses its own 900 ms transition.
- Capture stops when the view is hidden or removed, or when the playback source changes. Updates from an invalidated session never write into a new view state.
- Fallback animations preserve the current displayed geometry and continue from there on exit.

### iOS

- Six bars, live base width `5S/48`, pre-rendered base width `S/8`, dynamic thickening, corner radii rounded to whole pixels.
- Cover art gets HSP brightness, saturation, scrim and blur, then is composited through a bar-shaped mask. Artwork is prepared only for this appearance.
- Fallback: three real trajectories, 2.66 s loop, non-uniform `keyTimes` with cubic Hermite interpolation, sixth bar pinned to minimum height. The interpolation was verified against on-device public QuartzCore presentation samples.

### HyperOS

- Five bars with fixed width and corner radius, inheriting the original Lottie `View`/`Stub` size, margin and gravity.
- The six DSP bands are resampled to five display bars; the DSP formula itself is unchanged.
- Fallback reuses the native `music_wave` heights and gradient trajectory; pause uses the native heights with a reversed gradient.

### MiOS

- iOS six-bar geometry, continuous corners, dynamic thickening and fallback, with the native HyperOS cover gradient and its opacity instead of the iOS artwork treatment.
- The gradient spans each bar's own height and reverses on pause.

## Settings

- Stored in device-protected storage, synced into SystemUI through a read-only `ContentProvider` plus `ContentObserver`.
- Appearance (`iOS` / `HyperOS` / `MiOS`, default `HyperOS`) and spectrum height (50%–200%, default 150%) apply live without restarting SystemUI.
- Artwork processing and settings reads run off the main thread.
- The "hide launcher entry" switch only hides the launcher icon; the settings screen stays reachable from the LSPosed module page.
