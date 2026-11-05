package dev.hyperwave.module

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
import dev.hyperwave.AppearanceSettings
import dev.hyperwave.AppearanceSettings.Style
import dev.hyperwave.audio.NativeDsp
import dev.hyperwave.ui.ArtworkBackdrop
import dev.hyperwave.ui.WaveformView
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HyperwaveModule : XposedModule() {
    private val installed: MutableSet<ClassLoader> = Collections.newSetFromMap(WeakHashMap())
    private val entries = WeakHashMap<Any, Entry>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val background: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
    private var style = Style.HYPEROS
    private var heightPercent = AppearanceSettings.DEFAULT_HEIGHT_PERCENT
    private var settingsObserver: ContentObserver? = null
    private var nativeReady = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        if (param.isSystemServer) {
            log(Log.INFO, TAG, "loaded API $apiVersion in system_server")
            return
        }
        if (SYSTEM_UI != param.processName) return
        try {
            FullRateCaptureHooks(this, false).install(javaClass.classLoader!!)
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
        try {
            FullRateCaptureHooks(this, true).install(param.classLoader)
        } catch (error: Throwable) {
            report("Full-rate system capture unavailable; retaining format restrictions", error)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!nativeReady || SYSTEM_UI != param.packageName) return
        val host = param.classLoader
        try {
            val factory = Class.forName("com.android.systemui.shared.plugins.PluginInstance\$PluginFactory",
                    false, host)
            val createLoader = factory.getDeclaredMethod("createClassLoader")
            hook(createLoader).intercept { chain ->
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
            if (cache is Map<*, *>) (cache[PLUGIN] as? ClassLoader)?.let(::installPlugin)
            log(Log.INFO, TAG, "watching SystemUI plugin ClassLoader")
        } catch (error: Throwable) {
            report("SystemUI build is unsupported", error)
        }
    }

    @Synchronized
    private fun installPlugin(loader: ClassLoader) {
        if (installed.contains(loader)) return
        try {
            val holderClass = Class.forName(HOLDER, false, loader)
            val palette = Palette()
            installColorHook(holderClass, palette)
            var methods = 0
            for (method in holderClass.declaredMethods) {
                val name = method.name
                if (name == "playAnimation" || name == "playBigAnimation") {
                    hook(method).intercept { chain ->
                        try {
                            if (replace(chain.thisObject ?: error("Hook without a receiver"), palette,
                                            name == "playBigAnimation")) return@intercept null
                        } catch (error: Throwable) {
                            report("Music replacement failed; keeping original", error)
                        }
                        chain.proceed()
                    }
                    methods++
                } else if (name == "bind") {
                    hook(method).intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val holder = chain.thisObject ?: error("Hook without a receiver")
                            if (!replace(holder, palette, null)) remove(holder)
                        } catch (error: Throwable) {
                            report("Music bind failed", error)
                        }
                        result
                    }
                } else if (name == "onHidden" || name == "onDetach") {
                    hook(method).intercept { chain ->
                        chain.thisObject?.let { entries[it]?.view?.setHostVisible(false) }
                        chain.proceed()
                    }
                } else if (name == "onShow") {
                    hook(method).intercept { chain ->
                        val result = chain.proceed()
                        try {
                            chain.thisObject?.let { entries[it]?.view?.setHostVisible(true) }
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
        } catch (error: Throwable) {
            report("Cannot install plugin hooks", error)
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
            hook(getColor).intercept { chain ->
                val result = chain.proceed()
                try {
                    if (result != null) {
                        val topColor = (first.get(result) as Number).toInt()
                        val bottomColor = (second.get(result) as Number).toInt()
                        // HyperOS computes the palette on IO; views are updated on main.
                        main.post {
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

    private fun replace(holder: Any, palette: Palette, big: Boolean?): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
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
            val nativeParams = nativeLayout(holder, big == true, context)
            container.addView(view, FrameLayout.LayoutParams(nativeParams))
            entry = Entry(container, view, palette, nativeParams)
            entries[holder] = entry
            log(Log.INFO, TAG, "replaced $pic for $source uid=$uid")
        }
        if (big != null) entry.nativeParams = nativeLayout(holder, big, context)
        updateArtwork(entry, extras, source)
        applyAppearance(entry)
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child === entry.view) continue
            if (child.visibility == View.VISIBLE) child.visibility = View.GONE
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
            readSettings(target)
        } catch (error: RuntimeException) {
            settingsObserver = null
            report("Cannot observe appearance settings", error)
        }
    }

    private fun readSettings(context: Context) {
        background.execute {
            try {
                context.contentResolver.query(AppearanceSettings.URI, null, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@execute
                    val next = Style.parse(cursor.getString(
                            cursor.getColumnIndexOrThrow(AppearanceSettings.COLUMN_STYLE)))
                    val heightColumn = cursor.getColumnIndex(AppearanceSettings.COLUMN_HEIGHT_PERCENT)
                    val nextHeight = (if (heightColumn >= 0) cursor.getInt(heightColumn)
                            else AppearanceSettings.DEFAULT_HEIGHT_PERCENT)
                            .coerceIn(AppearanceSettings.MIN_HEIGHT_PERCENT, AppearanceSettings.MAX_HEIGHT_PERCENT)
                    main.post {
                        style = next
                        heightPercent = nextHeight
                        for (entry in entries.values) applyAppearance(entry)
                        Log.i(TAG, "appearance applied style=${next.value} height=$nextHeight%")
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
        background.execute {
            try {
                if (token != entry.artworkGeneration) return@execute
                val drawable = icon.loadDrawable(context)
                if (token != entry.artworkGeneration) return@execute
                val backdrop = if (drawable == null) null
                        else ArtworkBackdrop.prepare(drawable, logicalSize)
                main.post {
                    if (token != entry.artworkGeneration) return@post
                    entry.preparingArtworkSize = -1
                    entry.artworkBackdrops[sizeIndex] = backdrop
                    entry.artworkPrepared[sizeIndex] = true
                    showArtwork(entry, backdrop)
                    Log.i(TAG, "artwork applied source=$source ready=${backdrop != null}")
                }
            } catch (error: RuntimeException) {
                main.post {
                    if (token == entry.artworkGeneration) entry.preparingArtworkSize = -1
                }
                report("Cannot prepare waveform artwork", error)
            }
        }
    }

    private fun remove(holder: Any) {
        val entry = entries.remove(holder) ?: return
        entry.artworkGeneration++
        entry.view.setHostVisible(false)
        entry.container.removeView(entry.view)
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
        const val TAG = "Hyperwave"
        const val SYSTEM_UI = "com.android.systemui"
        const val PLUGIN = "miui.systemui.plugin"
        const val HOLDER = "miui.systemui.dynamicisland.module.IslandIconViewHolder"
    }
}
