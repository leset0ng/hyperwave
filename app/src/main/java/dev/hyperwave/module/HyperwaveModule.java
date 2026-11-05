package dev.hyperwave.module;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import dev.hyperwave.audio.NativeDsp;
import dev.hyperwave.ui.WaveformView;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class HyperwaveModule extends XposedModule {
    private static final String TAG = "Hyperwave";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String PLUGIN = "miui.systemui.plugin";
    private static final String HOLDER = "miui.systemui.dynamicisland.module.IslandIconViewHolder";
    private final Set<ClassLoader> installed = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<Object, Entry> entries = new WeakHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean nativeReady;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        if (!SYSTEM_UI.equals(param.getProcessName())) return;
        try {
            NativeDsp.load(getModuleApplicationInfo().nativeLibraryDir);
            nativeReady = true;
            log(Log.INFO, TAG, "loaded API " + getApiVersion() + " in " + param.getProcessName());
        } catch (Throwable error) {
            report("Rust library failed to load; original island retained", error);
        }
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        if (!nativeReady || !SYSTEM_UI.equals(param.getPackageName())) return;
        ClassLoader host = param.getClassLoader();
        try {
            Class<?> factory = Class.forName("com.android.systemui.shared.plugins.PluginInstance$PluginFactory", false, host);
            Method createLoader = factory.getDeclaredMethod("createClassLoader");
            hook(createLoader).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    ApplicationInfo info = (ApplicationInfo) Reflect.field(chain.getThisObject(), "pluginAppInfo");
                    if (PLUGIN.equals(info.packageName) && result instanceof ClassLoader loader) installPlugin(loader);
                } catch (Throwable error) { report("Cannot inspect plugin loader", error); }
                return result;
            });
            Class<?> injector = Class.forName("com.miui.systemui.plugin.PluginInstanceInjector", false, host);
            Field cacheField = injector.getDeclaredField("sClassLoaders");
            cacheField.setAccessible(true);
            Object cache = cacheField.get(null);
            if (cache instanceof Map<?, ?> map && map.get(PLUGIN) instanceof ClassLoader loader) installPlugin(loader);
            log(Log.INFO, TAG, "watching SystemUI plugin ClassLoader");
        } catch (Throwable error) { report("SystemUI build is unsupported", error); }
    }

    private synchronized void installPlugin(ClassLoader loader) {
        if (installed.contains(loader)) return;
        try {
            Class<?> holderClass = Class.forName(HOLDER, false, loader);
            Palette palette = new Palette();
            installColorHook(holderClass, palette);
            int methods = 0;
            for (Method method : holderClass.getDeclaredMethods()) {
                String name = method.getName();
                if (name.equals("playAnimation") || name.equals("playBigAnimation")) {
                    hook(method).intercept(chain -> {
                        try { if (replace(chain.getThisObject(), palette)) return null; }
                        catch (Throwable error) { report("Music replacement failed; keeping original", error); }
                        return chain.proceed();
                    });
                    methods++;
                } else if (name.equals("bind")) {
                    hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object holder = chain.getThisObject();
                            if (!replace(holder, palette)) remove(holder);
                        } catch (Throwable error) { report("Music bind failed", error); }
                        return result;
                    });
                } else if (name.equals("onHidden") || name.equals("onDetach")) {
                    hook(method).intercept(chain -> {
                        Entry entry = entries.get(chain.getThisObject());
                        if (entry != null) entry.view.setHostVisible(false);
                        return chain.proceed();
                    });
                } else if (name.equals("onShow")) {
                    hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Entry entry = entries.get(chain.getThisObject());
                            if (entry != null) entry.view.setHostVisible(true);
                        } catch (Throwable error) { report("Music show failed", error); }
                        return result;
                    });
                }
            }
            if (methods != 2) throw new IllegalStateException("Expected two island animation methods, found " + methods);
            installed.add(loader);
            log(Log.INFO, TAG, "music island hooks installed for plugin " + loader);
        } catch (Throwable error) { report("Cannot install plugin hooks", error); }
    }

    private void installColorHook(Class<?> holderClass, Palette palette) {
        try {
            Method getColor = holderClass.getDeclaredMethod("getLottieColor", Bitmap.class);
            Field first = pairField(getColor.getReturnType(), "first", "a");
            Field second = pairField(getColor.getReturnType(), "second", "b");
            Field top = holderClass.getDeclaredField("gradientTopColor");
            Field bottom = holderClass.getDeclaredField("gradientBottomColor");
            top.setAccessible(true);
            bottom.setAccessible(true);
            palette.top = top.getInt(null);
            palette.bottom = bottom.getInt(null);
            hook(getColor).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    if (result != null) {
                        int topColor = ((Number) first.get(result)).intValue();
                        int bottomColor = ((Number) second.get(result)).intValue();
                        // HyperOS computes the palette on IO; views are updated on main.
                        main.post(() -> {
                            palette.top = topColor;
                            palette.bottom = bottomColor;
                            for (Entry entry : entries.values()) {
                                if (entry.palette == palette) entry.view.setColors(topColor, bottomColor);
                            }
                            Log.i(TAG, "native island colors top=" + Integer.toHexString(topColor)
                                    + " bottom=" + Integer.toHexString(bottomColor));
                        });
                    }
                } catch (Throwable error) { report("Cannot read native island colors", error); }
                return result;
            });
            log(Log.INFO, TAG, "native island color hook installed");
        } catch (Throwable error) { report("Native island colors unavailable; keeping white bars", error); }
    }

    private static Field pairField(Class<?> type, String name, String obfuscatedName) throws NoSuchFieldException {
        Field field;
        try { field = type.getDeclaredField(name); }
        catch (NoSuchFieldException ignored) { field = type.getDeclaredField(obfuscatedName); }
        field.setAccessible(true);
        return field;
    }

    private boolean replace(Object holder, Palette palette) throws ReflectiveOperationException {
        if (Looper.myLooper() != Looper.getMainLooper()) return false;
        Object picInfo = Reflect.field(holder, "picInfo");
        if (picInfo == null) return false;
        String pic = (String) Reflect.call(picInfo, "getPic");
        if (!"musicWave".equals(pic) && !"musicPause".equals(pic)) return false;
        Object data = Reflect.field(holder, "data");
        if (data == null) return false;
        Bundle extras = (Bundle) Reflect.call(data, "getExtras");
        if (extras == null) return false;
        String source = extras.getString("miui.pkg.name");
        if (source == null || source.isEmpty()) return false;
        FrameLayout container = (FrameLayout) Reflect.field(holder, "iconContainer");
        if (container == null) return false;
        Context context = container.getContext();
        int uid;
        try {
            int user = extras.getInt("miui.user.id", Process.myUid() / 100000);
            int appId = context.getPackageManager().getApplicationInfo(source, 0).uid % 100000;
            uid = user * 100000 + appId;
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            uid = -1;
        }
        Entry entry = entries.get(holder);
        if (entry == null || entry.container != container) {
            remove(holder);
            WaveformView view = new WaveformView(context);
            int size = Math.round(22 * context.getResources().getDisplayMetrics().density);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, Gravity.CENTER);
            container.addView(view, params);
            entry = new Entry(container, view, palette);
            entries.put(holder, entry);
            log(Log.INFO, TAG, "replaced " + pic + " for " + source + " uid=" + uid);
        }
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child == entry.view) continue;
            if (child.getVisibility() == View.VISIBLE) child.setVisibility(View.GONE);
        }
        entry.view.setVisibility(View.VISIBLE);
        entry.view.setColors(palette.top, palette.bottom);
        entry.view.configure(uid, "musicWave".equals(pic));
        return true;
    }

    private void remove(Object holder) {
        Entry entry = entries.remove(holder);
        if (entry == null) return;
        entry.view.setHostVisible(false);
        entry.container.removeView(entry.view);
    }

    private void report(String message, Throwable error) {
        log(Log.ERROR, TAG, message + ": " + Log.getStackTraceString(error));
    }

    private static final class Palette {
        int top = Color.WHITE;
        int bottom = Color.WHITE;
    }

    private static final class Entry {
        final FrameLayout container;
        final WaveformView view;
        final Palette palette;
        Entry(FrameLayout container, WaveformView view, Palette palette) {
            this.container = container;
            this.view = view;
            this.palette = palette;
        }
    }
}
