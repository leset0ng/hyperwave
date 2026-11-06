package io.github.leset0ng.hyperwave.module

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import io.github.leset0ng.hyperwave.AppearanceSettings
import io.github.leset0ng.hyperwave.ui.FlippingArtworkView
import java.util.WeakHashMap

/** Independent of waveform entries: left artwork and right waveform use separate holders. */
internal class ArtworkFlipHooks(
        private val hooks: ReloadableHooks,
        private val observeSettings: (Context) -> Unit,
        private val report: (String, Throwable) -> Unit,
) {
    private val entries = WeakHashMap<Any, Entry>()
    private val imageGenerations = WeakHashMap<Icon, Long>()
    private var nextImageGeneration = 0L
    private var metadataController: MediaController? = null
    var enabled = AppearanceSettings.DEFAULT_ARTWORK_FLIP
        private set

    fun installMetadata(loader: ClassLoader) {
        try {
            val controller = Class.forName(
                    "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaControllerImpl",
                    false, loader)
            val method = controller.declaredMethods.single {
                it.name == "generateRightImage" && it.parameterCount == 3
            }
            hooks.install(method, "artwork.seed") { chain ->
                val result = chain.proceed()
                try {
                    val media = chain.getArg(1) ?: return@install result
                    val pictures = chain.getArg(2) as? Bundle ?: return@install result
                    val context = chain.thisObject?.let { Reflect.field(it, "context") } as? Context
                    val token = Reflect.field(media, "token") as? MediaSession.Token
                    val seed = Bundle()
                    seed.putString("song", Reflect.field(media, "song")?.toString())
                    seed.putString("artist", Reflect.field(media, "artist")?.toString())
                    seed.putParcelable("session", token)
                    val artwork = Reflect.field(media, "artwork") as? Icon
                    seed.putBoolean("has_artwork", artwork != null)
                    if (artwork != null) seed.putLong("image_generation", imageGeneration(artwork))
                    if (context != null && token != null) {
                        // Metadata IDs distinguish tracks with identical titles when the player supplies one.
                        try {
                            val metadata = controller(context, token).metadata
                            seed.putString("media_id", metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
                        } catch (ignored: RuntimeException) {
                            // Song/artist/session still provide stable identity during bitmap refreshes.
                        }
                    }
                    pictures.putBundle(SEED, seed)
                } catch (error: Throwable) {
                    report("Cannot attach artwork identity", error)
                }
                result
            }
        } catch (error: Throwable) {
            report("Stable artwork identity unavailable; using native icon identity", error)
        }
    }

    fun install(holderClass: Class<*>) {
        val method = holderClass.declaredMethods.single {
            it.name == "setFixIcon" && it.parameterCount == 1
        }
        hooks.install(method, "artwork.image") { chain ->
            val result = chain.proceed()
            chain.thisObject?.let { holder ->
                try { capture(holder) } catch (error: Throwable) {
                    remove(holder)
                    report("Cannot animate music artwork; retaining native image", error)
                }
            }
            result
        }
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        for (holder in entries.keys.toList()) {
            val entry = entries[holder] ?: continue
            if (!value) {
                entry.view?.dispose()
                entry.view?.let { entry.container.removeView(it) }
                entry.view = null
                entry.imageToken = null
                entry.imageSeed = null
                if (entry.original.visibility == View.INVISIBLE) entry.original.visibility = View.VISIBLE
            } else {
                capture(holder)
            }
        }
    }

    fun onBound(holder: Any) {
        val entry = entries[holder] ?: return
        if (!eligible(holder) || Reflect.field(holder, "fixIcon") !== entry.original ||
                Reflect.field(holder, "iconContainer") !== entry.container) {
            // The native bind already selected another icon; do not resurrect the old artwork.
            remove(holder, false)
        } else {
            val view = entry.view ?: return
            val params = entry.original.layoutParams as FrameLayout.LayoutParams
            if (!sameAbsoluteLayout(view.layoutParams as FrameLayout.LayoutParams, params)) {
                view.layoutParams = FrameLayout.LayoutParams(params)
            }
        }
    }

    fun setVisible(holder: Any, visible: Boolean) {
        entries[holder]?.let {
            it.visible = visible
            it.view?.setHostVisible(visible)
        }
    }

    fun remove(holder: Any, restore: Boolean = true) {
        val entry = entries.remove(holder) ?: return
        entry.view?.dispose()
        entry.view?.let { entry.container.removeView(it) }
        if (restore && entry.original.visibility == View.INVISIBLE) entry.original.visibility = View.VISIBLE
    }

    fun snapshot(): ArrayList<ArrayList<Any>> = ArrayList(entries.map { (holder, entry) ->
        arrayListOf(holder, entry.visible)
    })

    fun clear() {
        entries.keys.toList().forEach { remove(it) }
        synchronized(this) { metadataController = null }
    }

    fun restore(rows: List<*>) {
        for (value in rows) {
            val row = value as? List<*> ?: continue
            val holder = row.getOrNull(0) ?: continue
            try {
                capture(holder)
                setVisible(holder, row.getOrNull(1) as? Boolean ?: true)
            } catch (error: Throwable) {
                remove(holder)
                report("Cannot restore artwork after reload", error)
            }
        }
    }

    private fun eligible(holder: Any): Boolean {
        val pic = Reflect.field(holder, "picInfo") ?: return false
        return Reflect.call(pic, "getPic") == "miui_media_album_icon" &&
                Reflect.call(pic, "getType") == 1
    }

    private fun capture(holder: Any) {
        if (hooks.paused || Looper.myLooper() != Looper.getMainLooper() || !eligible(holder)) return
        val original = Reflect.field(holder, "fixIcon") as? ImageView ?: return
        val container = Reflect.field(holder, "iconContainer") as? FrameLayout ?: return
        observeSettings(container.context)
        var entry = entries[holder]
        if (entry == null || entry.original !== original || entry.container !== container) {
            remove(holder)
            entry = Entry(container, original)
            entries[holder] = entry
        }
        if (!enabled) return
        val data = Reflect.field(holder, "data") ?: return
        val extras = Reflect.call(data, "getExtras") as? Bundle ?: return
        val pictures = extras.getBundle("miui.focus.pics")
        val metadata = pictures?.getBundle(SEED)
        val icon = pictures?.getParcelable("miui_media_album_icon", Icon::class.java)
        val identity: Any? = if (metadata != null) identity(
                extras.getString("miui.pkg.name"), extras.getInt("miui.user.id"),
                metadata.getParcelable("session", MediaSession.Token::class.java),
                metadata.getString("media_id"), metadata.getString("song"), metadata.getString("artist"))
        else icon
        val drawable = original.drawable.takeIf {
            original.visibility == View.VISIBLE && metadata?.getBoolean("has_artwork", true) != false
        }
        val imageToken: Any? = if (drawable == null) null
                else if (metadata?.containsKey("image_generation") == true) metadata.getLong("image_generation")
                else icon ?: drawable
        when (delivery(entry.imageSeed, entry.imageToken, identity, imageToken)) {
            Delivery.HOLD -> {
                // Metadata can arrive with the preceding artwork or an app placeholder.
                // Keep the old face and its seed until a decoded image event actually arrives.
                if (entry.view != null && original.visibility == View.VISIBLE) original.visibility = View.INVISIBLE
                return
            }
            Delivery.CLEAR -> {
                entry.view?.update(null, null)
                entry.imageToken = null
                entry.imageSeed = null
                return
            }
            Delivery.INSTALL -> Unit
        }
        checkNotNull(drawable)
        // Start preparing the decoded bitmap for its first hardware draw before turning.
        (drawable as? BitmapDrawable)?.bitmap?.prepareToDraw()
        val view = entry.view ?: FlippingArtworkView(original).also {
            entry.view = it
            it.setHostVisible(entry.visible)
            container.addView(it, container.indexOfChild(original) + 1,
                    FrameLayout.LayoutParams(original.layoutParams as FrameLayout.LayoutParams))
        }
        view.update(drawable, identity ?: imageToken)
        entry.imageToken = imageToken
        entry.imageSeed = identity
        original.visibility = View.INVISIBLE
    }

    @Synchronized
    private fun controller(context: Context, token: MediaSession.Token): MediaController {
        val current = metadataController
        if (current != null && current.sessionToken == token) return current
        return MediaController(context, token).also { metadataController = it }
    }

    @Synchronized
    private fun imageGeneration(icon: Icon): Long =
            imageGenerations.getOrPut(icon) { ++nextImageGeneration }

    private class Entry(val container: FrameLayout, val original: ImageView) {
        var view: FlippingArtworkView? = null
        var visible = true
        var imageToken: Any? = null
        var imageSeed: Any? = null
    }

    internal enum class Delivery { HOLD, CLEAR, INSTALL }

    internal companion object {
        private const val SEED = "io.github.leset0ng.hyperwave.artwork_seed"

        // Relative margins retain hidden unresolved state; keep their original copy path.
        internal fun sameAbsoluteLayout(a: FrameLayout.LayoutParams, b: FrameLayout.LayoutParams): Boolean =
                !a.isMarginRelative && !b.isMarginRelative &&
                a.width == b.width && a.height == b.height && a.gravity == b.gravity &&
                a.leftMargin == b.leftMargin && a.topMargin == b.topMargin &&
                a.rightMargin == b.rightMargin && a.bottomMargin == b.bottomMargin &&
                a.layoutDirection == b.layoutDirection

        internal fun delivery(previousSeed: Any?, previousImage: Any?, seed: Any?, image: Any?): Delivery {
            if (image == null) return if (previousImage != null && seed != null && previousSeed != seed)
                Delivery.HOLD else Delivery.CLEAR
            return if (previousImage == image) Delivery.HOLD else Delivery.INSTALL
        }

        internal fun identity(source: String?, user: Int, session: Any?, mediaId: String?,
                              song: String?, artist: String?): List<Any?> =
                listOf(source, user, session) + if (!mediaId.isNullOrBlank()) listOf(mediaId)
                else listOf(song, artist)
    }
}
