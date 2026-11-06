package io.github.leset0ng.hyperwave.module

import android.app.Instrumentation
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Parcel
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import io.github.leset0ng.hyperwave.ui.FlippingArtworkView
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Proxy

/** Uses real framework sessions and LayoutParams, with no Xposed hooks installed. */
internal object ArtworkHooksDeviceCheck {
    fun verify(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        val framework = Proxy.newProxyInstance(XposedInterface::class.java.classLoader,
                arrayOf(XposedInterface::class.java)) { _, method, _ ->
            error("Unexpected framework call: ${method.name}")
        } as XposedInterface
        val hooks = ArtworkFlipHooks(ReloadableHooks(framework), {}, { message, cause ->
            throw AssertionError(message, cause)
        })
        val controller = ArtworkFlipHooks::class.java.getDeclaredMethod("controller",
                android.content.Context::class.java, MediaSession.Token::class.java).apply { isAccessible = true }
        fun read(token: MediaSession.Token): MediaController = controller.invoke(hooks, context, token) as MediaController
        val first = MediaSession(context, "HyperWave-first")
        val second = MediaSession(context, "HyperWave-second")
        fun metadata(id: String) = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Same title")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Same artist")
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, id).build()
        try {
            first.setMetadata(metadata("first"))
            second.setMetadata(metadata("second"))
            val cached = read(first.sessionToken)
            check(read(first.sessionToken) === cached) { "same session controller was rebuilt" }
            val parcel = Parcel.obtain()
            val copiedToken = try {
                first.sessionToken.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                MediaSession.Token.CREATOR.createFromParcel(parcel)
            } finally { parcel.recycle() }
            check(read(copiedToken) === cached) { "equivalent session token missed controller" }
            check(cached.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) == "first")
            first.setMetadata(metadata("changed"))
            check(read(first.sessionToken) === cached)
            check(cached.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) == "changed") {
                "cached controller retained stale metadata"
            }
            instrumentation.runOnMainSync { hooks.setEnabled(false) }
            first.setMetadata(metadata("while-disabled"))
            check(read(first.sessionToken).metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) == "while-disabled")
            instrumentation.runOnMainSync { hooks.setEnabled(true) }
            check(read(first.sessionToken) === cached) { "enabling changed session identity" }
            val changed = read(second.sessionToken)
            check(changed !== cached && changed.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) == "second") {
                "session switch reused the previous controller"
            }
            instrumentation.runOnMainSync { hooks.clear() }
            check(read(second.sessionToken) !== changed) { "retirement retained the cached controller" }
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
            val workers = Array(4) { index -> Thread {
                try {
                    val token = if (index % 2 == 0) first.sessionToken else second.sessionToken
                    repeat(100) { check(read(token).sessionToken == token) }
                } catch (error: Throwable) { failure.compareAndSet(null, error) }
            } }
            workers.forEach { it.start() }
            workers.forEach { it.join(5000); check(!it.isAlive) { "controller cache deadlocked" } }
            failure.get()?.let { throw AssertionError("concurrent controller access", it) }
            verifyLayout()
            instrumentation.runOnMainSync { verifyBoundLayout(hooks, context) }
        } finally {
            instrumentation.runOnMainSync { hooks.clear() }
            first.release()
            second.release()
        }
    }

    private class Picture {
        fun getPic() = "miui_media_album_icon"
        fun getType() = 1
    }

    private class Holder(val fixIcon: ImageView, val iconContainer: FrameLayout) {
        val picInfo = Picture()
    }

    private fun verifyBoundLayout(hooks: ArtworkFlipHooks, context: android.content.Context) {
        val original = ImageView(context)
        val container = FrameLayout(context)
        container.addView(original, FrameLayout.LayoutParams(48, 64, Gravity.CENTER))
        val renderer = FlippingArtworkView(original)
        container.addView(renderer, FrameLayout.LayoutParams(original.layoutParams as FrameLayout.LayoutParams))
        val holder = Holder(original, container)
        val entryType = Class.forName("io.github.leset0ng.hyperwave.module.ArtworkFlipHooks\$Entry")
        val entry = entryType.getDeclaredConstructor(FrameLayout::class.java, ImageView::class.java)
                .apply { isAccessible = true }.newInstance(container, original)
        entryType.getDeclaredField("view").apply { isAccessible = true }.set(entry, renderer)
        @Suppress("UNCHECKED_CAST")
        val entries = ArtworkFlipHooks::class.java.getDeclaredField("entries").apply { isAccessible = true }
                .get(hooks) as MutableMap<Any, Any>
        entries[holder] = entry
        try {
            val unchanged = renderer.layoutParams
            repeat(60) { hooks.onBound(holder) }
            check(renderer.layoutParams === unchanged) { "unchanged bind replaced artwork layout" }
            (original.layoutParams as FrameLayout.LayoutParams).height = 96
            hooks.onBound(holder)
            check(renderer.layoutParams !== unchanged && renderer.layoutParams.height == 96) {
                "changed bind did not update artwork layout"
            }
            val relative = original.layoutParams as FrameLayout.LayoutParams
            relative.marginStart = 7
            container.layoutDirection = View.LAYOUT_DIRECTION_RTL
            original.layoutDirection = View.LAYOUT_DIRECTION_RTL
            renderer.layoutDirection = View.LAYOUT_DIRECTION_RTL
            relative.resolveLayoutDirection(View.LAYOUT_DIRECTION_RTL)
            hooks.onBound(holder)
            val copied = renderer.layoutParams as FrameLayout.LayoutParams
            check(copied !== relative && copied.marginStart == 7 && copied.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                "relative bound layout did not follow the RTL host"
            }
            hooks.onBound(holder)
            check(renderer.layoutParams !== copied) { "relative-margin bind skipped its conservative copy path" }
        } finally {
            hooks.remove(holder)
        }
    }

    private fun verifyLayout() {
        val original = FrameLayout.LayoutParams(48, 64, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            setMargins(1, 2, 3, 4)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        check(ArtworkFlipHooks.sameAbsoluteLayout(original, FrameLayout.LayoutParams(original)))
        val changes: List<(FrameLayout.LayoutParams) -> Unit> = listOf(
                { it.width++ }, { it.height++ }, { it.gravity = Gravity.CENTER },
                { it.leftMargin++ }, { it.topMargin++ }, { it.rightMargin++ }, { it.bottomMargin++ },
                { it.layoutDirection = View.LAYOUT_DIRECTION_RTL }, { it.marginStart = 1 }, { it.marginEnd = 3 })
        for (change in changes) {
            val modified = FrameLayout.LayoutParams(original).also(change)
            check(!ArtworkFlipHooks.sameAbsoluteLayout(original, modified)) { "layout change was ignored" }
            check(!ArtworkFlipHooks.sameAbsoluteLayout(modified, original)) { "layout change comparison was asymmetric" }
        }
        val relative = FrameLayout.LayoutParams(original).apply { marginStart = 7; marginEnd = 9 }
        for (direction in intArrayOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            relative.resolveLayoutDirection(direction)
            check(!ArtworkFlipHooks.sameAbsoluteLayout(relative, FrameLayout.LayoutParams(relative))) {
                "relative margin unresolved state was assumed equal"
            }
        }
    }
}
