package io.github.leset0ng.hyperwave.module

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Executable

/** Replaces matching old-generation hooks without stacking interceptors. */
internal class ReloadableHooks(private val module: XposedInterface) {
    private val previous = ArrayList<HookHandle>()
    @Volatile var paused = false
        private set

    private var inFlight = 0

    @Synchronized
    fun pause(): Boolean {
        if (inFlight != 0) return false
        paused = true
        return true
    }

    @Synchronized
    fun resume() { paused = false }

    @Synchronized
    fun adopt(handles: List<HookHandle>) {
        previous.addAll(handles)
    }

    @Synchronized
    fun install(method: Executable, id: String, hooker: Hooker) {
        if (paused) return
        val guarded = Hooker { chain ->
            val admitted = synchronized(this) {
                if (paused) false else {
                    inFlight++
                    true
                }
            }
            if (!admitted) chain.proceed() else try {
                hooker.intercept(chain)
            } finally {
                synchronized(this) { inFlight-- }
            }
        }
        val old = previous.firstOrNull { it.executable == method && it.id == id }
        if (old != null) {
            old.replaceHook(guarded)
            previous.remove(old)
        } else {
            module.hook(method).setId(id).intercept(guarded)
        }
    }

    @Synchronized
    fun finishAdoption() {
        for (handle in previous) handle.unhook()
        previous.clear()
    }
}
