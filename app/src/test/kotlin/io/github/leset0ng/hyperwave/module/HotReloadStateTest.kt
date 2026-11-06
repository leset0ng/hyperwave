package io.github.leset0ng.hyperwave.module

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.lang.reflect.Proxy

class HotReloadStateTest {
    @Test
    fun failedInitializationRetainsTheEntireHandoffAcrossGenerations() {
        val loader = javaClass.classLoader!!
        val holder = Any()
        var state: Map<String, Any> = hashMapOf(
                "hostLoader" to loader,
                "plugins" to arrayListOf(loader),
                "holders" to arrayListOf(arrayListOf(holder, 16, 22, true)),
                "artworkHolders" to arrayListOf(arrayListOf(holder, false)),
                "style" to "hyperos",
                "height" to 65,
                "artworkFlip" to true,
                "context" to Any(),
        )
        val expected = HashMap(state).apply { remove("context") }
        repeat(3) {
            val module = HyperWaveModule()
            // A missing server loader fails before any Android or framework hook work.
            val error = assertThrows(IllegalStateException::class.java) {
                module.onHotReloaded(reload(state, systemServer = true))
            }
            assertEquals("Missing system_server ClassLoader", error.message)
            state = snapshot(module)
            assertEquals(expected, state)
            assertSame(loader, state["hostLoader"])
            assertFalse(state.containsKey("context"))
        }
    }

    @Test
    fun successfulInitializationStopsForwardingTheIncomingSnapshot() {
        val module = HyperWaveModule()
        module.attachFramework(proxy<XposedInterface> { name ->
            check(name == "log") { "Unexpected framework call: $name" }
            null
        }) {}
        // An unrelated process needs no Android initialization, but completes the lifecycle.
        module.onHotReloaded(reload(mapOf("stale" to "previous generation")))

        val state = snapshot(module)
        assertFalse(state.containsKey("stale"))
        assertEquals(emptyList<Any>(), state["holders"])
        assertEquals(emptyList<Any>(), state["plugins"])
    }

    @Test
    fun failedInitializationIncludesPluginLoadersDiscoveredBeforeRetry() {
        val oldLoader = javaClass.classLoader!!
        val newLoader = object : ClassLoader(oldLoader) {}
        val module = HyperWaveModule()
        assertThrows(IllegalStateException::class.java) {
            module.onHotReloaded(reload(mapOf("plugins" to arrayListOf(oldLoader)), systemServer = true))
        }
        @Suppress("UNCHECKED_CAST")
        val installed = HyperWaveModule::class.java.getDeclaredField("installed").apply {
            isAccessible = true
        }.get(module) as MutableSet<ClassLoader>
        installed.add(oldLoader)
        installed.add(newLoader)

        assertEquals(listOf(oldLoader, newLoader), snapshot(module)["plugins"])
    }

    @Test
    fun liveHolderRowsReplaceSnapshotsByIdentityAndPreserveUnrestoredHolders() {
        val restored = EqualHolder()
        val unrestored = EqualHolder()
        val discovered = Any()
        val unrestoredRow = arrayListOf(unrestored, false)
        val liveRow = arrayListOf(restored, true)
        val newRow = arrayListOf(discovered, true)
        val rows = mergeReloadHolders(
                arrayListOf(arrayListOf(restored, false), unrestoredRow),
                listOf(liveRow, newRow))

        assertEquals(3, rows.size)
        assertSame(unrestoredRow, rows[0])
        assertSame(liveRow, rows[1])
        assertSame(newRow, rows[2])
    }

    private class EqualHolder {
        override fun equals(other: Any?): Boolean = other is EqualHolder
        override fun hashCode(): Int = 0
    }

    @Suppress("UNCHECKED_CAST")
    private fun snapshot(module: HyperWaveModule): Map<String, Any> =
            HyperWaveModule::class.java.getDeclaredMethod("saveReloadState").apply {
                isAccessible = true
            }.invoke(module) as Map<String, Any>

    private fun reload(state: Map<String, Any>, systemServer: Boolean = false): HotReloadedParam =
            proxy { name ->
                when (name) {
                    "getSavedInstanceState" -> state
                    "getOldHookHandles" -> emptyList<XposedInterface.HookHandle>()
                    "isSystemServer" -> systemServer
                    "getProcessName" -> if (systemServer) "system" else "unrelated.process"
                    else -> error("Unexpected lifecycle call: $name")
                }
            }

    private inline fun <reified T> proxy(crossinline invoke: (String) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
                invoke(method.name)
            } as T
}
