# API 102 hot reload

## Lifecycle

`META-INF/xposed/module.prop` opts into APK-update reloads with `autoHotReload=true`. The framework must implement API 102 hot reload and the currently running module must accept it. Selecting API 102 alone does not make a module reloadable.

`onHotReloading` runs in the old generation:

1. Atomically reject the request if any admitted hook invocation is still executing; otherwise pause callbacks and further hook registration. This prevents a scoped capture operation from losing its generation's validator halfway through the call.
2. On the main thread, save host ClassLoaders and music holders, native layout parameters, palette, host visibility, and appearance. Remove the module's views, restore hidden native children, and unregister the settings observer.
3. Stop and wait for all capture sessions, including sessions already stopping after a visibility or source change. Shut down and wait for all background executors, including any still retiring after a previously rejected reload. Each wait has a five-second budget. Capture teardown releases the recorder and unregisters its audio policy before the thread exits.
4. Clear pending UI callbacks and retire this generation's native copy. Save only bootstrap collections, primitive values, and host/framework objects; no module-defined view, enum, callback, or state class crosses ClassLoaders.
5. Return `true` only after cleanup succeeds. On a cleanup failure, reject the request and restore the old generation's saved music holders.

`onHotReloaded` runs in the new generation. The framework does not replay `onModuleLoaded`, `onSystemServerStarting`, or package lifecycle callbacks. The module explicitly reinstalls capture hooks, discovers plugin loaders, and restores existing holders. `ReloadableHooks` matches old handles by executable and stable ID, calls `replaceHook` for atomic interceptor replacement, and unhooks handles absent from the new version. Paused old interceptors proceed to the original method.

SystemUI and `system_server` reload independently; this is not a transaction across processes. The capture rule tag remains compatible between generations.

## Native code

`System.load` associates a native library path with its ClassLoader. Loading the original path again under a new ClassLoader can fail even when the APK's native library did not change.

Initial load uses the installed library. Each reload makes a real, uniquely named read-only copy of the new APK's library in the host's device-protected `code_cache/hyperwave-native` directory, then loads that absolute path from the new generation. It does not fall back to the old generation's path on failure. Each generation deletes only its own copy after capture and artwork JNI calls return. No manual `dlclose` is performed; ART owns unloading and does not guarantee immediate collection.

The Rust code starts no native threads and retains no persistent Java global references. Returning from all JNI invocations is sufficient for its current teardown contract. Adding persistent JNI callbacks, global references, or native workers requires extending that contract.

## First upgrade and fallback

A running version without `onHotReloading` returns the API default, `false`. Installing this implementation cannot replace that old instance automatically. After the first upgrade, enable both scopes and reboot once. Subsequent APK updates can request hot reload.

If the framework lacks hot reload support, a target rejects it, or the new generation fails initialization, restart the affected process. Restart the device for `system_server`. Enabling scopes for the first time still requires loading the module into the target processes.

Successful generations log `old generation ready for hot reload` and `hot reload complete in ...` under `HyperWave`. An in-flight hook rejects the request before cleanup and logs `Hot reload rejected; hook invocation is still in flight`. Cleanup rejection logs `Hot reload rejected; restoring current generation`. Check the framework's diagnostics as well: new-generation initialization can fail after old-generation cleanup, and the framework does not promise rollback.

## Verification

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug

android install --device=emulator-5580 --apks=app/build/outputs/apk/debug/app-debug.apk
android install --device=emulator-5580 --apks=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w -e native_reload true \
  ng.lst.hyperwave.test/ng.lst.hyperwave.ui.PerformanceInstrumentation
```

JVM regression tests cover executable/ID matching, atomic handle replacement, removal of obsolete handles, paused callback behavior, admission cleanup on exceptions, and a scoped capture operation whose nested validator must survive a rejected reload request. All 34 JVM tests passed, including the six new hook-reload tests. APK/test-APK assembly and Android lint passed.

On the Android 17 / API 37 arm64 HyperOS emulator, the native reload runner passed three distinct ClassLoader generations while preceding generations remained reachable. It exercised DSP, brightness and blur JNI calls in every generation, idempotent loading within a generation, and native-copy cleanup. It returned `INSTRUMENTATION_CODE: -1` and `checks=PASS`.

This runner executes in the companion app process. It does not establish that the SystemUI SELinux domain permits loading its own code-cache copy, nor does it exercise real framework lifecycle delivery or the two-process hook handoff. Full acceptance requires updating the APK while music plays with the module active in both scopes, confirming both reload logs without PID changes, continuing PCM output and appearance updates, and absence of duplicate capture threads or old waveform views. Repeat the update to cover a second handoff. Also cover paused/hidden and expanded islands, multiple plugin ClassLoaders, and a cleanup rejection.

API contract: [libxposed lifecycle documentation](https://github.com/libxposed/api/blob/master/_autodocs/api-reference/XposedModuleInterface.md).
