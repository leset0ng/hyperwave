package io.github.leset0ng.hyperwave.module

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Executable
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ReloadableHooksTest {
    private val first = Target::class.java.getDeclaredMethod("first")
    private val second = Target::class.java.getDeclaredMethod("second")

    @Test
    fun adoptionMatchesBothExecutableAndIdAndReplacesTheOldHook() {
        val framework = FakeFramework()
        val hooks = ReloadableHooks(framework.module)
        val differentMethod = FakeHandle(second, "music")
        val differentId = FakeHandle(first, "color")
        val matching = FakeHandle(first, "music")
        hooks.adopt(listOf(differentMethod, differentId, matching))

        hooks.install(first, "music") { "new generation" }

        assertEquals(0, framework.hookCalls)
        assertEquals(1, matching.replacements)
        assertEquals(0, differentMethod.replacements)
        assertEquals(0, differentId.replacements)
        assertNotNull(matching.hooker)
        assertEquals("new generation", matching.hooker!!.intercept(originalChain()))
    }

    @Test
    fun unmatchedHooksRegisterWithTheirId() {
        val framework = FakeFramework()
        val hooks = ReloadableHooks(framework.module)
        hooks.adopt(listOf(FakeHandle(second, "music"), FakeHandle(first, "color")))

        hooks.install(first, "music") { "installed" }

        assertEquals(1, framework.hookCalls)
        val registered = framework.registered.single()
        assertEquals(first, registered.getExecutable())
        assertEquals("music", registered.getId())
        assertEquals("installed", registered.hooker!!.intercept(originalChain()))
    }

    @Test
    fun finishingAdoptionUnhooksOnlyUnusedHandlesAndClearsThem() {
        val framework = FakeFramework()
        val hooks = ReloadableHooks(framework.module)
        val replaced = FakeHandle(first, "music")
        val removed = FakeHandle(second, "obsolete")
        hooks.adopt(listOf(replaced, removed))
        hooks.install(first, "music") { "new generation" }

        hooks.finishAdoption()
        hooks.finishAdoption()

        assertEquals(0, replaced.unhooks)
        assertEquals(1, removed.unhooks)
        assertEquals("new generation", replaced.hooker!!.intercept(originalChain()))
    }

    @Test
    fun pauseBypassesCallbacksAndInstallationUntilResume() {
        val framework = FakeFramework()
        val hooks = ReloadableHooks(framework.module)
        var callbackCalls = 0
        hooks.install(first, "music") {
            callbackCalls++
            "hooked"
        }
        val callback = framework.registered.single().hooker!!
        assertEquals("hooked", callback.intercept(originalChain()))
        assertEquals(1, callbackCalls)

        hooks.pause()
        assertTrue(hooks.paused)
        assertEquals("original", callback.intercept(originalChain()))
        assertEquals(1, callbackCalls)
        hooks.install(second, "ignored") { "unexpected" }
        assertEquals(1, framework.hookCalls)
        assertEquals(1, framework.registered.size)

        hooks.resume()
        assertFalse(hooks.paused)
        assertEquals("hooked", callback.intercept(originalChain()))
        assertEquals(2, callbackCalls)
        hooks.install(second, "resumed") { "resumed" }
        assertEquals(2, framework.hookCalls)
        assertEquals("resumed", framework.registered.last().hooker!!.intercept(originalChain()))
    }

    @Test
    fun reloadCannotPauseBetweenAScopedOperationAndItsNestedValidator() {
        val framework = FakeFramework()
        val hooks = ReloadableHooks(framework.module)
        val scope = CaptureFormatScope()
        val format = Any()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<Any?>()
        hooks.install(second, "validate") { scope.allows(format) }
        val validator = framework.registered.last().hooker!!
        hooks.install(first, "policy") {
            scope.withFormat(format) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                validator.intercept(originalChain())
            }
        }
        val policy = framework.registered.last().hooker!!
        val worker = Thread {
            try {
                result.set(policy.intercept(originalChain()))
            } catch (error: Throwable) {
                result.set(error)
            }
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(hooks.pause())
            assertFalse(hooks.paused)
        } finally {
            release.countDown()
            worker.join(5000)
        }
        assertFalse(worker.isAlive)
        assertEquals(true, result.get())
        assertTrue(hooks.pause())
    }

    @Test
    fun throwingInterceptorsReleaseTheirReloadAdmission() {
        val framework = FakeFramework()
        val hooks = ReloadableHooks(framework.module)
        hooks.install(first, "throwing") { throw IllegalStateException("hook failed") }
        try {
            framework.registered.single().hooker!!.intercept(originalChain())
            throw AssertionError("Expected interceptor failure")
        } catch (expected: IllegalStateException) {
            assertEquals("hook failed", expected.message)
        }
        assertTrue(hooks.pause())
    }

    private class Target {
        fun first() {}
        fun second() {}
    }

    private class FakeHandle(
            private val method: Executable,
            private val hookId: String,
    ) : HookHandle {
        var replacements = 0
        var unhooks = 0
        var hooker: Hooker? = null

        override fun getExecutable(): Executable = method
        override fun getId(): String = hookId
        override fun unhook() { unhooks++ }
        override fun replaceHook(hooker: Hooker): HookHandle {
            replacements++
            this.hooker = hooker
            return FakeHandle(method, hookId).also { it.hooker = hooker }
        }
    }

    private class FakeFramework {
        var hookCalls = 0
        val registered = ArrayList<FakeHandle>()
        val module = proxy<XposedInterface> { name, args ->
            check(name == "hook") { "Unexpected module call: $name" }
            hookCalls++
            val method = args[0] as Executable
            var id: String? = null
            lateinit var builder: HookBuilder
            builder = proxy { builderName, builderArgs ->
                when (builderName) {
                    "setId" -> {
                        id = builderArgs[0] as String
                        builder
                    }
                    "intercept" -> FakeHandle(method, checkNotNull(id)).also {
                        it.hooker = builderArgs[0] as Hooker
                        registered.add(it)
                    }
                    else -> error("Unexpected builder call: $builderName")
                }
            }
            builder
        }
    }

    companion object {
        private fun originalChain(): Chain = proxy { name, _ ->
            check(name == "proceed") { "Unexpected chain call: $name" }
            "original"
        }

        private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
                Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                    invoke(method.name, args ?: emptyArray())
                } as T
    }
}
