package dev.hyperwave.module;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.drawable.Icon;
import android.graphics.drawable.Drawable;
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
import dev.hyperwave.AppearanceSettings;
import dev.hyperwave.AppearanceSettings.Style;
import dev.hyperwave.audio.NativeDsp;
import dev.hyperwave.ui.ArtworkBackdrop;
import dev.hyperwave.ui.WaveformView;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HyperwaveModule extends XposedModule {
    private static final String TAG = "Hyperwave";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String PLUGIN = "miui.systemui.plugin";
    private static final String HOLDER = "miui.systemui.dynamicisland.module.IslandIconViewHolder";
    private final Set<ClassLoader> installed = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<Object, Entry> entries = new WeakHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private Style style = Style.HYPEROS;
    private ContentObserver settingsObserver;
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
                        try { if (replace(chain.getThisObject(), palette, name.equals("playBigAnimation"))) return null; }
                        catch (Throwable error) { report("Music replacement failed; keeping original", error); }
                        return chain.proceed();
                    });
                    methods++;
                } else if (name.equals("bind")) {
                    hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object holder = chain.getThisObject();
                            if (!replace(holder, palette, null)) remove(holder);
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

    private boolean replace(Object holder, Palette palette, Boolean big) throws ReflectiveOperationException {
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
        observeSettings(context);
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
            FrameLayout.LayoutParams nativeParams = nativeLayout(holder, Boolean.TRUE.equals(big), context);
            container.addView(view, new FrameLayout.LayoutParams(nativeParams));
            entry = new Entry(container, view, palette, nativeParams);
            entries.put(holder, entry);
            log(Log.INFO, TAG, "replaced " + pic + " for " + source + " uid=" + uid);
        }
        if (big != null) entry.nativeParams = nativeLayout(holder, big, context);
        updateArtwork(entry, extras, source);
        applyAppearance(entry);
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

    private void observeSettings(Context context) {
        if (settingsObserver != null) return;
        Context application = context.getApplicationContext();
        Context target = application == null ? context : application;
        settingsObserver = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) { readSettings(target); }
        };
        try {
            target.getContentResolver().registerContentObserver(AppearanceSettings.URI, false, settingsObserver);
            readSettings(target);
        } catch (RuntimeException error) {
            settingsObserver = null;
            report("Cannot observe appearance settings", error);
        }
    }

    private void readSettings(Context context) {
        background.execute(() -> {
            try (Cursor cursor = context.getContentResolver().query(AppearanceSettings.URI, null, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) return;
                Style next = Style.parse(cursor.getString(cursor.getColumnIndexOrThrow(AppearanceSettings.COLUMN_STYLE)));
                main.post(() -> {
                    style = next;
                    for (Entry entry : entries.values()) applyAppearance(entry);
                    Log.i(TAG, "appearance applied style=" + next.value);
                });
            } catch (RuntimeException error) { report("Cannot read appearance settings; retaining current style", error); }
        });
    }

    private void applyAppearance(Entry entry) {
        entry.view.setStyle(style);
        FrameLayout.LayoutParams desired;
        if (style == Style.HYPEROS) desired = new FrameLayout.LayoutParams(entry.nativeParams);
        else {
            float density = entry.view.getResources().getDisplayMetrics().density;
            int size = Math.round((entry.nativeParams.width / density <= 16 ? 16 : 22) * density);
            desired = new FrameLayout.LayoutParams(size, size, Gravity.CENTER);
        }
        ViewGroup.LayoutParams current = entry.view.getLayoutParams();
        if (!(current instanceof FrameLayout.LayoutParams params) || params.width != desired.width
                || params.height != desired.height || params.gravity != desired.gravity
                || params.leftMargin != desired.leftMargin || params.rightMargin != desired.rightMargin)
            entry.view.setLayoutParams(desired);
        prepareArtwork(entry);
    }

    private FrameLayout.LayoutParams nativeLayout(Object holder, boolean big, Context context) {
        String[] fields = big ? new String[]{"lottieBigView", "lottieBigViewStub"}
                : new String[]{"lottieView", "lottieViewStub"};
        for (String field : fields) {
            try {
                Object value = Reflect.field(holder, field);
                if (value instanceof View view && view.getLayoutParams() instanceof FrameLayout.LayoutParams params
                        && params.width > 0 && params.height > 0)
                    return new FrameLayout.LayoutParams(params);
            } catch (ReflectiveOperationException ignored) { }
        }
        String name = big ? "island_big_icon_size" : "island_fix_icon_size";
        int resource = context.getResources().getIdentifier(name, "dimen", PLUGIN);
        int size = resource == 0 ? Math.round((big ? 26 : 20) * context.getResources().getDisplayMetrics().density)
                : context.getResources().getDimensionPixelSize(resource);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, Gravity.CENTER);
        if (!big) params.leftMargin = params.rightMargin = Math.round(context.getResources().getDisplayMetrics().density);
        return params;
    }

    private void updateArtwork(Entry entry, Bundle extras, String source) {
        Bundle pictures = extras.getBundle("miui.focus.pics");
        Icon icon = pictures == null ? null : pictures.getParcelable("miui_media_album_icon", Icon.class);
        if (!source.equals(entry.source) || icon != entry.artworkIcon) {
            entry.source = source;
            entry.artworkIcon = icon;
            entry.artworkGeneration++;
            entry.preparingArtworkSize = -1;
            java.util.Arrays.fill(entry.artworkBackdrops, null);
            java.util.Arrays.fill(entry.artworkPrepared, false);
            showArtwork(entry, null);
        }
    }

    private static void showArtwork(Entry entry, Bitmap backdrop) {
        if (entry.shownArtwork == backdrop) return;
        entry.shownArtwork = backdrop;
        entry.view.setArtwork(backdrop);
    }

    private void prepareArtwork(Entry entry) {
        if (style != Style.IOS || entry.artworkIcon == null) {
            if (entry.preparingArtworkSize != -1) {
                entry.artworkGeneration++;
                entry.preparingArtworkSize = -1;
            }
            return;
        }
        Context context = entry.view.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int sizeIndex = entry.nativeParams.width / density <= 16 ? 0 : 1;
        if (entry.artworkPrepared[sizeIndex]) {
            if (entry.preparingArtworkSize != -1) {
                entry.artworkGeneration++;
                entry.preparingArtworkSize = -1;
            }
            showArtwork(entry, entry.artworkBackdrops[sizeIndex]);
            return;
        }
        if (entry.preparingArtworkSize == sizeIndex) return;
        int token = ++entry.artworkGeneration;
        entry.preparingArtworkSize = sizeIndex;
        Icon icon = entry.artworkIcon;
        String source = entry.source;
        float logicalSize = sizeIndex == 0 ? 16 : 22;
        background.execute(() -> {
            try {
                if (token != entry.artworkGeneration) return;
                Drawable drawable = icon.loadDrawable(context);
                if (token != entry.artworkGeneration) return;
                Bitmap backdrop = drawable == null ? null : ArtworkBackdrop.prepare(drawable, logicalSize);
                main.post(() -> {
                    if (token != entry.artworkGeneration) return;
                    entry.preparingArtworkSize = -1;
                    entry.artworkBackdrops[sizeIndex] = backdrop;
                    entry.artworkPrepared[sizeIndex] = true;
                    showArtwork(entry, backdrop);
                    Log.i(TAG, "artwork applied source=" + source + " ready=" + (backdrop != null));
                });
            } catch (RuntimeException error) {
                main.post(() -> {
                    if (token == entry.artworkGeneration) entry.preparingArtworkSize = -1;
                });
                report("Cannot prepare waveform artwork", error);
            }
        });
    }

    private void remove(Object holder) {
        Entry entry = entries.remove(holder);
        if (entry == null) return;
        entry.artworkGeneration++;
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
        FrameLayout.LayoutParams nativeParams;
        Icon artworkIcon;
        final Bitmap[] artworkBackdrops = new Bitmap[2];
        final boolean[] artworkPrepared = new boolean[2];
        Bitmap shownArtwork;
        int preparingArtworkSize = -1;
        String source;
        volatile int artworkGeneration;
        Entry(FrameLayout container, WaveformView view, Palette palette, FrameLayout.LayoutParams nativeParams) {
            this.container = container;
            this.view = view;
            this.palette = palette;
            this.nativeParams = nativeParams;
        }
    }
}
