package io.github.leset0ng.hyperwave.module

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.leset0ng.hyperwave.AppearanceSettings
import io.github.leset0ng.hyperwave.AppearanceSettings.Style
import io.github.leset0ng.hyperwave.audio.NativeDsp
import io.github.leset0ng.hyperwave.audio.PlaybackCapture
import io.github.leset0ng.hyperwave.ui.ArtworkBackdrop
import io.github.leset0ng.hyperwave.ui.WaveformView
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class HyperWaveModule : XposedModule() {
    private val installed: MutableSet<ClassLoader> = Collections.newSetFromMap(WeakHashMap())
    private val entries = WeakHashMap<Any, Entry>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val hooks = ReloadableHooks(this)
    private val artworkFlips by lazy { ArtworkFlipHooks(hooks, ::observeSettings, ::report) }
    private var background: ExecutorService? = null
    private val workers = ArrayList<ExecutorService>()
    private var hostLoader: ClassLoader? = null
    private var serverLoader: ClassLoader? = null
    private val palettes = WeakHashMap<ClassLoader, Palette>()
    private var style = Style.HYPEROS
    private var heightPercent = AppearanceSettings.DEFAULT_HEIGHT_PERCENT
    private var settingsObserver: ContentObserver? = null
    private var settingsContext: Context? = null
    private var nativeReady = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        if (param.isSystemServer) {
            log(Log.INFO, TAG, "loaded API $apiVersion in system_server")
            return
        }
        if (SYSTEM_UI != param.processName) return
        try {
            FullRateCaptureHooks(this, hooks, false).install(javaClass.classLoader!!)
        } catch (error: Throwable) {
            report("Full-rate capture hooks unavailable; legacy capture remains available", error)
        }
        try {
            NativeDsp.load(moduleApplicationInfo.nativeLibraryDir)
            nativeReady = true
            log(Log.INFO, TAG, "loaded API $apiVersion in ${param.processName}")
        } catch (error: Throwable) {
            report("Rust library failed to load; original island retained", error)
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        installServer(param.classLoader)
    }

    private fun installServer(loader: ClassLoader): Boolean {
        serverLoader = loader
        return try {
            FullRateCaptureHooks(this, hooks, true).install(loader)
            true
        } catch (error: Throwable) {
            report("Full-rate system capture unavailable; retaining format restrictions", error)
            false
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (SYSTEM_UI != param.packageName) return
        hostLoader = param.classLoader
        if (nativeReady) installHost(param.classLoader)
    }

    private fun installHost(host: ClassLoader): Boolean {
        hostLoader = host
        return try {
            artworkFlips.installMetadata(host)
            val factory = Class.forName("com.android.systemui.shared.plugins.PluginInstance\$PluginFactory",
                    false, host)
            val createLoader = factory.getDeclaredMethod("createClassLoader")
            hooks.install(createLoader, "plugin.loader") { chain ->
                val result = chain.proceed()
                try {
                    val info = Reflect.field(chain.thisObject ?: error("Hook without a receiver"),
                            "pluginAppInfo") as ApplicationInfo
                    if (PLUGIN == info.packageName && result is ClassLoader) installPlugin(result)
                } catch (error: Throwable) {
                    report("Cannot inspect plugin loader", error)
                }
                result
            }
            val injector = Class.forName("com.miui.systemui.plugin.PluginInstanceInjector", false, host)
            val cacheField = injector.getDeclaredField("sClassLoaders")
            cacheField.isAccessible = true
            val cache = cacheField.get(null)
            if (cache is Map<*, *>) (cache[PLUGIN] as? ClassLoader)?.let {
                check(installPlugin(it)) { "Cannot install cached plugin hooks" }
            }
            log(Log.INFO, TAG, "watching SystemUI plugin ClassLoader")
            true
        } catch (error: Throwable) {
            report("SystemUI build is unsupported", error)
            false
        }
    }

    @Synchronized
    private fun installPlugin(loader: ClassLoader): Boolean {
        if (hooks.paused) return false
        if (installed.contains(loader)) return true
        return try {
            val holderClass = Class.forName(HOLDER, false, loader)
            val palette = palettes.getOrPut(loader) { Palette() }
            installColorHook(holderClass, palette)
            try {
                artworkFlips.install(holderClass)
            } catch (error: Throwable) {
                report("Artwork flip hooks unavailable; retaining native artwork", error)
            }
            var methods = 0
            for (method in holderClass.declaredMethods) {
                val name = method.name
                if (name == "playAnimation" || name == "playBigAnimation") {
                    hooks.install(method, "island.$name") { chain ->
                        try {
                            if (replace(chain.thisObject ?: error("Hook without a receiver"), palette,
                                            name == "playBigAnimation")) return@install null
                        } catch (error: Throwable) {
                            report("Music replacement failed; keeping original", error)
                        }
                        chain.proceed()
                    }
                    methods++
                } else if (name == "bind") {
                    hooks.install(method, "island.bind") { chain ->
                        val result = chain.proceed()
                        try {
                            val holder = chain.thisObject ?: error("Hook without a receiver")
                            if (!replace(holder, palette, null)) remove(holder)
                            artworkFlips.onBound(holder)
                        } catch (error: Throwable) {
                            report("Music bind failed", error)
                        }
                        result
                    }
                } else if (name == "onHidden" || name == "onDetach") {
                    hooks.install(method, "island.$name") { chain ->
                        chain.thisObject?.let {
                            setHostVisible(it, false)
                            if (name == "onDetach") artworkFlips.remove(it)
                            else artworkFlips.setVisible(it, false)
                        }
                        chain.proceed()
                    }
                } else if (name == "onShow") {
                    hooks.install(method, "island.onShow") { chain ->
                        val result = chain.proceed()
                        try {
                            chain.thisObject?.let {
                                setHostVisible(it, true)
                                artworkFlips.setVisible(it, true)
                            }
                        } catch (error: Throwable) {
                            report("Music show failed", error)
                        }
                        result
                    }
                }
            }
            check(methods == 2) { "Expected two island animation methods, found $methods" }
            installed.add(loader)
            log(Log.INFO, TAG, "music island hooks installed for plugin $loader")
            true
        } catch (error: Throwable) {
            report("Cannot install plugin hooks", error)
            false
        }
    }

    private fun installColorHook(holderClass: Class<*>, palette: Palette) {
        try {
            val getColor = holderClass.getDeclaredMethod("getLottieColor", Bitmap::class.java)
            val first = pairField(getColor.returnType, "first", "a")
            val second = pairField(getColor.returnType, "second", "b")
            val top = holderClass.getDeclaredField("gradientTopColor")
            val bottom = holderClass.getDeclaredField("gradientBottomColor")
            top.isAccessible = true
            bottom.isAccessible = true
            palette.top = top.getInt(null)
            palette.bottom = bottom.getInt(null)
            hooks.install(getColor, "island.color") { chain ->
                val result = chain.proceed()
                try {
                    if (result != null) {
                        val topColor = (first.get(result) as Number).toInt()
                        val bottomColor = (second.get(result) as Number).toInt()
                        // HyperOS computes the palette on IO; views are updated on main.
                        postUpdate {
                            palette.top = topColor
                            palette.bottom = bottomColor
                            for (entry in entries.values) {
                                if (entry.palette === palette) entry.view.setColors(topColor, bottomColor)
                            }
                            Log.i(TAG, "native island colors top=" + Integer.toHexString(topColor) +
                                    " bottom=" + Integer.toHexString(bottomColor))
                        }
                    }
                } catch (error: Throwable) {
                    report("Cannot read native island colors", error)
                }
                result
            }
            log(Log.INFO, TAG, "native island color hook installed")
        } catch (error: Throwable) {
            report("Native island colors unavailable; keeping white bars", error)
        }
    }

    private fun pairField(type: Class<*>, name: String, obfuscatedName: String): Field {
        val field = try {
            type.getDeclaredField(name)
        } catch (ignored: NoSuchFieldException) {
            type.getDeclaredField(obfuscatedName)
        }
        field.isAccessible = true
        return field
    }

    private fun replace(
            holder: Any,
            palette: Palette,
            big: Boolean?,
            restoredParams: FrameLayout.LayoutParams? = null,
            restoredVisible: Boolean = true,
    ): Boolean {
        if (hooks.paused || Looper.myLooper() != Looper.getMainLooper()) return false
        val picInfo = Reflect.field(holder, "picInfo") ?: return false
        val pic = Reflect.call(picInfo, "getPic") as? String
        if (pic != "musicWave" && pic != "musicPause") return false
        val data = Reflect.field(holder, "data") ?: return false
        val extras = Reflect.call(data, "getExtras") as? Bundle ?: return false
        val source = extras.getString("miui.pkg.name")
        if (source.isNullOrEmpty()) return false
        val container = Reflect.field(holder, "iconContainer") as? FrameLayout ?: return false
        val context = container.context
        observeSettings(context)
        val uid = try {
            val user = extras.getInt("miui.user.id", Process.myUid() / 100000)
            val appId = context.packageManager.getApplicationInfo(source, 0).uid % 100000
            user * 100000 + appId
        } catch (error: PackageManager.NameNotFoundException) {
            -1
        }
        var entry = entries[holder]
        if (entry == null || entry.container !== container) {
            remove(holder)
            val view = WaveformView(context)
            view.setHostVisible(restoredVisible)
            val nativeParams = restoredParams ?: nativeLayout(holder, big == true, context)
            container.addView(view, FrameLayout.LayoutParams(nativeParams))
            entry = Entry(container, view, palette, nativeParams)
            entry.hostVisible = restoredVisible
            entries[holder] = entry
            log(Log.INFO, TAG, "replaced $pic for $source uid=$uid")
        }
        if (big != null) entry.nativeParams = nativeLayout(holder, big, context)
        updateArtwork(entry, extras, source)
        applyAppearance(entry)
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child === entry.view) continue
            if (child.visibility == View.VISIBLE) {
                entry.hiddenViews.putIfAbsent(child, child.visibility)
                child.visibility = View.GONE
            }
        }
        entry.view.visibility = View.VISIBLE
        entry.view.setColors(palette.top, palette.bottom)
        entry.view.configure(uid, pic == "musicWave")
        return true
    }

    private fun observeSettings(context: Context) {
        if (settingsObserver != null) return
        val application = context.applicationContext
        val target = application ?: context
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                readSettings(target)
            }
        }
        settingsObserver = observer
        try {
            target.contentResolver.registerContentObserver(AppearanceSettings.URI, false, observer)
            settingsContext = target
            readSettings(target)
        } catch (error: RuntimeException) {
            settingsObserver = null
            report("Cannot observe appearance settings", error)
        }
    }

    private fun readSettings(context: Context) {
        inBackground {
            try {
                context.contentResolver.query(AppearanceSettings.URI, null, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@inBackground
                    val next = Style.parse(cursor.getString(
                            cursor.getColumnIndexOrThrow(AppearanceSettings.COLUMN_STYLE)))
                    val heightColumn = cursor.getColumnIndex(AppearanceSettings.COLUMN_HEIGHT_PERCENT)
                    val nextHeight = (if (heightColumn >= 0) cursor.getInt(heightColumn)
                            else AppearanceSettings.DEFAULT_HEIGHT_PERCENT)
                            .coerceIn(AppearanceSettings.MIN_HEIGHT_PERCENT, AppearanceSettings.MAX_HEIGHT_PERCENT)
                    val flipColumn = cursor.getColumnIndex(AppearanceSettings.COLUMN_ARTWORK_FLIP)
                    val nextFlip = if (flipColumn >= 0) cursor.getInt(flipColumn) != 0
                            else AppearanceSettings.DEFAULT_ARTWORK_FLIP
                    postUpdate {
                        style = next
                        heightPercent = nextHeight
                        artworkFlips.setEnabled(nextFlip)
                        for (entry in entries.values) applyAppearance(entry)
                        Log.i(TAG, "appearance applied style=${next.value} height=$nextHeight% artworkFlip=$nextFlip")
                    }
                }
            } catch (error: RuntimeException) {
                report("Cannot read appearance settings; retaining current style", error)
            }
        }
    }

    private fun applyAppearance(entry: Entry) {
        entry.view.setStyle(style, minOf(entry.nativeParams.width, entry.nativeParams.height).toFloat())
        entry.view.setHeightScale(heightPercent / 100f)
        val desired: FrameLayout.LayoutParams
        if (style == Style.HYPEROS) {
            desired = FrameLayout.LayoutParams(entry.nativeParams)
        } else {
            val density = entry.view.resources.displayMetrics.density
            val size = Math.round((if (entry.nativeParams.width / density <= 16) 16 else 22) * density)
            desired = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        }
        val current = entry.view.layoutParams
        if (current !is FrameLayout.LayoutParams || current.width != desired.width ||
                current.height != desired.height || current.gravity != desired.gravity ||
                current.leftMargin != desired.leftMargin || current.rightMargin != desired.rightMargin)
            entry.view.layoutParams = desired
        prepareArtwork(entry)
    }

    private fun nativeLayout(holder: Any, big: Boolean, context: Context): FrameLayout.LayoutParams {
        val fields = if (big) arrayOf("lottieBigView", "lottieBigViewStub")
                else arrayOf("lottieView", "lottieViewStub")
        for (field in fields) {
            try {
                val value = Reflect.field(holder, field)
                val params = (value as? View)?.layoutParams
                if (params is FrameLayout.LayoutParams && params.width > 0 && params.height > 0)
                    return FrameLayout.LayoutParams(params)
            } catch (ignored: ReflectiveOperationException) {
            }
        }
        val name = if (big) "island_big_icon_size" else "island_fix_icon_size"
        val density = context.resources.displayMetrics.density
        val resource = context.resources.getIdentifier(name, "dimen", PLUGIN)
        val size = if (resource == 0) Math.round((if (big) 26 else 20) * density)
                else context.resources.getDimensionPixelSize(resource)
        val params = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        if (!big) {
            val margin = Math.round(density)
            params.leftMargin = margin
            params.rightMargin = margin
        }
        return params
    }

    private fun updateArtwork(entry: Entry, extras: Bundle, source: String) {
        val pictures = extras.getBundle("miui.focus.pics")
        val icon = pictures?.getParcelable("miui_media_album_icon", Icon::class.java)
        if (source != entry.source || icon !== entry.artworkIcon) {
            entry.source = source
            entry.artworkIcon = icon
            entry.artworkGeneration++
            entry.preparingArtworkSize = -1
            entry.artworkBackdrops.fill(null)
            entry.artworkPrepared.fill(false)
            showArtwork(entry, null)
        }
    }

    private fun showArtwork(entry: Entry, backdrop: Bitmap?) {
        if (entry.shownArtwork === backdrop) return
        entry.shownArtwork = backdrop
        entry.view.setArtwork(backdrop)
    }

    private fun prepareArtwork(entry: Entry) {
        if (style != Style.IOS || entry.artworkIcon == null) {
            if (entry.preparingArtworkSize != -1) {
                entry.artworkGeneration++
                entry.preparingArtworkSize = -1
            }
            return
        }
        val context = entry.view.context
        val density = context.resources.displayMetrics.density
        val sizeIndex = if (entry.nativeParams.width / density <= 16) 0 else 1
        if (entry.artworkPrepared[sizeIndex]) {
            if (entry.preparingArtworkSize != -1) {
                entry.artworkGeneration++
                entry.preparingArtworkSize = -1
            }
            showArtwork(entry, entry.artworkBackdrops[sizeIndex])
            return
        }
        if (entry.preparingArtworkSize == sizeIndex) return
        val token = ++entry.artworkGeneration
        entry.preparingArtworkSize = sizeIndex
        val icon = entry.artworkIcon ?: return
        val source = entry.source
        val logicalSize = if (sizeIndex == 0) 16f else 22f
        inBackground {
            try {
                if (hooks.paused || token != entry.artworkGeneration) return@inBackground
                val drawable = icon.loadDrawable(context)
                if (hooks.paused || token != entry.artworkGeneration) return@inBackground
                val backdrop = if (drawable == null) null
                        else ArtworkBackdrop.prepare(drawable, logicalSize)
                postUpdate {
                    if (token != entry.artworkGeneration) return@postUpdate
                    entry.preparingArtworkSize = -1
                    entry.artworkBackdrops[sizeIndex] = backdrop
                    entry.artworkPrepared[sizeIndex] = true
                    showArtwork(entry, backdrop)
                    Log.i(TAG, "artwork applied source=$source ready=${backdrop != null}")
                }
            } catch (error: RuntimeException) {
                postUpdate {
                    if (token == entry.artworkGeneration) entry.preparingArtworkSize = -1
                }
                report("Cannot prepare waveform artwork", error)
            }
        }
    }

    private fun remove(holder: Any) {
        val entry = entries.remove(holder) ?: return
        entry.artworkGeneration++
        entry.view.dispose()
        entry.container.removeView(entry.view)
        for ((view, visibility) in entry.hiddenViews) view.visibility = visibility
        entry.hiddenViews.clear()
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        if (!hooks.pause()) {
            log(Log.WARN, TAG, "Hot reload rejected; hook invocation is still in flight")
            return false
        }
        var state: Map<String, Any>? = null
        val retiredViews = ArrayList<WaveformView>()
        return try {
            state = onMain {
                val snapshot = saveReloadState()
                state = snapshot
                retiredViews.addAll(entries.values.map { it.view })
                settingsObserver?.let { settingsContext?.contentResolver?.unregisterContentObserver(it) }
                settingsObserver = null
                artworkFlips.clear()
                for (holder in entries.keys.toList()) remove(holder)
                snapshot
            }
            val retiringWorkers = synchronized(this) {
                background = null
                ArrayList(workers)
            }
            retiringWorkers.forEach { it.shutdownNow() }
            check(PlaybackCapture.stopAllAndAwait(RELOAD_TIMEOUT_MS)) { "Audio capture did not retire" }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RELOAD_TIMEOUT_MS)
            for (worker in retiringWorkers) {
                check(worker.awaitTermination(maxOf(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                    "Background work did not retire"
                }
            }
            onMain {
                retiredViews.forEach { it.dispose() }
                main.removeCallbacksAndMessages(null)
            }
            param.setSavedInstanceState(state)
            NativeDsp.retireReloadCopy()
            synchronized(this) {
                installed.clear()
                palettes.clear()
                workers.clear()
            }
            log(Log.INFO, TAG, "old generation ready for hot reload")
            true
        } catch (error: Throwable) {
            report("Hot reload rejected; restoring current generation", error)
            hooks.resume()
            state?.let { snapshot -> onMain { restoreEntries(snapshot) } }
            false
        }
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        val state = param.savedInstanceState as? Map<*, *> ?: emptyMap<Any, Any>()
        hooks.adopt(param.oldHookHandles)
        try {
            if (param.isSystemServer) {
                val loader = state["serverLoader"] as? ClassLoader
                        ?: error("Missing system_server ClassLoader")
                check(installServer(loader)) { "System capture hook restoration failed" }
            } else if (param.processName == SYSTEM_UI) {
                val context = state["context"] as? Context ?: currentApplication()
                NativeDsp.loadForReload(context, moduleApplicationInfo.nativeLibraryDir)
                nativeReady = true
                try {
                    FullRateCaptureHooks(this, hooks, false).install(javaClass.classLoader!!)
                } catch (error: Throwable) {
                    report("Full-rate capture hooks unavailable after reload; legacy capture remains available", error)
                }
                val host = state["hostLoader"] as? ClassLoader
                        ?: error("Missing SystemUI ClassLoader")
                check(installHost(host)) { "SystemUI hook restoration failed" }
                (state["plugins"] as? List<*>)?.filterIsInstance<ClassLoader>()?.forEach {
                    check(installPlugin(it)) { "Plugin hook restoration failed" }
                }
                onMain { restoreEntries(state) }
            }
            log(Log.INFO, TAG, "hot reload complete in ${param.processName}")
        } finally {
            // Hooks removed from the new version must not keep old code reachable.
            hooks.finishAdoption()
        }
    }

    /** Only framework/host objects and bootstrap collections cross generations. */
    @Synchronized
    private fun saveReloadState(): Map<String, Any> {
        val state = HashMap<String, Any>()
        hostLoader?.let { state["hostLoader"] = it }
        serverLoader?.let { state["serverLoader"] = it }
        settingsContext?.let { state["context"] = it }
        state["plugins"] = ArrayList(installed)
        state["style"] = style.value
        state["height"] = heightPercent
        state["artworkFlip"] = artworkFlips.enabled
        state["artworkHolders"] = artworkFlips.snapshot()
        val holders = ArrayList<ArrayList<Any>>()
        for ((holder, entry) in entries) {
            val row = ArrayList<Any>()
            row.add(holder)
            row.add(FrameLayout.LayoutParams(entry.nativeParams))
            row.add(entry.palette.top)
            row.add(entry.palette.bottom)
            row.add(entry.hostVisible)
            holders.add(row)
        }
        state["holders"] = holders
        return state
    }

    private fun restoreEntries(state: Map<*, *>) {
        style = Style.parse(state["style"] as? String)
        heightPercent = (state["height"] as? Int ?: AppearanceSettings.DEFAULT_HEIGHT_PERCENT)
                .coerceIn(AppearanceSettings.MIN_HEIGHT_PERCENT, AppearanceSettings.MAX_HEIGHT_PERCENT)
        artworkFlips.setEnabled(state["artworkFlip"] as? Boolean ?: AppearanceSettings.DEFAULT_ARTWORK_FLIP)
        for (value in state["holders"] as? List<*> ?: emptyList<Any>()) {
            val row = value as? List<*> ?: continue
            val holder = row.getOrNull(0) ?: continue
            try {
                val loader = holder.javaClass.classLoader ?: continue
                val palette = palettes.getOrPut(loader) { Palette() }
                palette.top = row[2] as Int
                palette.bottom = row[3] as Int
                replace(holder, palette, null,
                        FrameLayout.LayoutParams(row[1] as FrameLayout.LayoutParams), row[4] as Boolean)
            } catch (error: Throwable) {
                report("Cannot restore music island after reload", error)
            }
        }
        artworkFlips.restore(state["artworkHolders"] as? List<*> ?: emptyList<Any>())
    }

    private fun currentApplication(): Context =
            Class.forName("android.app.ActivityThread").getMethod("currentApplication")
                    .invoke(null) as? Context ?: error("SystemUI application is not ready")

    private fun <T> onMain(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val task = FutureTask(action)
        check(main.post(task)) { "Main looper is shutting down" }
        return task.get()
    }

    @Synchronized
    private fun postUpdate(action: () -> Unit) {
        if (!hooks.paused) main.post { if (!hooks.paused) action() }
    }

    @Synchronized
    private fun inBackground(action: () -> Unit) {
        if (hooks.paused) return
        val worker = background ?: Executors.newSingleThreadExecutor().also {
            background = it
            workers.removeAll { previous -> previous.isTerminated }
            workers.add(it)
        }
        worker.execute { if (!hooks.paused) action() }
    }

    private fun setHostVisible(holder: Any, visible: Boolean) {
        if (hooks.paused) return
        entries[holder]?.let {
            it.hostVisible = visible
            it.view.setHostVisible(visible)
        }
    }

    private fun report(message: String, error: Throwable) {
        log(Log.ERROR, TAG, message + ": " + Log.getStackTraceString(error))
    }

    private class Palette {
        var top = Color.WHITE
        var bottom = Color.WHITE
    }

    private class Entry(
            val container: FrameLayout,
            val view: WaveformView,
            val palette: Palette,
            var nativeParams: FrameLayout.LayoutParams,
    ) {
        var hostVisible = true
        val hiddenViews = java.util.IdentityHashMap<View, Int>()
        var artworkIcon: Icon? = null
        var artworkBackdrops: Array<Bitmap?> = arrayOfNulls(2)
        var artworkPrepared = BooleanArray(2)
        var shownArtwork: Bitmap? = null
        var preparingArtworkSize = -1
        var source: String? = null

        @Volatile
        var artworkGeneration = 0
    }

    private companion object {
        const val RELOAD_TIMEOUT_MS = 5000L
        const val TAG = "HyperWave"
        const val SYSTEM_UI = "com.android.systemui"
        const val PLUGIN = "miui.systemui.plugin"
        const val HOLDER = "miui.systemui.dynamicisland.module.IslandIconViewHolder"
    }
}
