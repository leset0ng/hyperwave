package io.github.leset0ng.hyperwave.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** Owns a recorder without making the caller of stop wait for audio services. */
internal class CaptureLifecycle<T : Any>(
        threadName: String,
        private val stopRecord: (T) -> Unit,
        private val releaseRecord: (T) -> Unit,
) {
    private val recordLock = Any()
    private val stopRequested = AtomicBoolean(false)
    private val stopFinished = CountDownLatch(1)
    private var record: T? = null
    private var recordStopped = false
    private var stopFailure: Throwable? = null
    private val stopThread = Thread({
        try {
            synchronized(recordLock) { stopRecordLocked() }
        } catch (error: Throwable) {
            stopFailure = error
        } finally {
            stopFinished.countDown()
        }
    }, threadName)

    val isStopped: Boolean get() = stopRequested.get()

    /** Publishes even a cancelled recorder so the capture thread can release it. */
    fun start(next: T, startRecord: (T) -> Unit): Boolean = synchronized(recordLock) {
        check(record == null) { "Previous recorder has not been released" }
        record = next
        recordStopped = false
        if (isStopped) return@synchronized false
        startRecord(next)
        true
    }

    /** Never acquires recordLock: start, stop and release may block inside it. */
    fun stop() {
        if (stopRequested.compareAndSet(false, true)) stopThread.start()
    }

    /** Called only by the capture thread, after its blocking read has returned. */
    fun release() = synchronized(recordLock) {
        val current = record ?: return@synchronized
        try {
            stopRecordLocked()
        } finally {
            record = null
            releaseRecord(current)
        }
    }

    /** Retires the stop worker as well as the recorder before hot reload proceeds. */
    fun awaitStopped(): Throwable? {
        stop()
        var interrupted = false
        try {
            while (true) {
                try {
                    // The latch also covers stop() being preempted before Thread.start().
                    stopFinished.await()
                    stopThread.join()
                    return stopFailure
                } catch (ignored: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun stopRecordLocked() {
        val current = record ?: return
        if (!recordStopped) {
            stopRecord(current)
            recordStopped = true
        }
    }
}
