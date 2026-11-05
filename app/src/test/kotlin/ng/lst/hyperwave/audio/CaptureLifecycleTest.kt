package ng.lst.hyperwave.audio

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureLifecycleTest {
    @Test(timeout = 10000)
    fun slowStopDoesNotBlockCallerOrRaceReleaseAndRepeatedRequestsStopOnce() {
        val stopEntered = CountDownLatch(1)
        val allowStop = CountDownLatch(1)
        val stopCalls = AtomicInteger()
        val releaseCalls = AtomicInteger()
        val stopThread = AtomicReference<Thread>()
        val lifecycle = CaptureLifecycle<Any>("test-capture-stop", {
            stopThread.set(Thread.currentThread())
            stopCalls.incrementAndGet()
            stopEntered.countDown()
            allowStop.await()
        }, { releaseCalls.incrementAndGet() })
        lifecycle.start(Any()) {}
        try {
            // Simulate a UI callback and require it to return while stop is blocked.
            val caller = worker { lifecycle.stop() }
            caller.get(2, SECONDS)
            assertTrue(stopEntered.await(2, SECONDS))
            assertTrue(lifecycle.isStopped)
            worker { repeat(10) { lifecycle.stop() } }.get(2, SECONDS)
            assertEquals(1, stopCalls.get())

            val retired = worker {
                lifecycle.release()
                assertNull(lifecycle.awaitStopped())
            }
            assertEquals(0, releaseCalls.get())
            assertFalse(retired.isDone)
            allowStop.countDown()
            retired.get(2, SECONDS)
            assertEquals(1, releaseCalls.get())
            assertFalse(stopThread.get().isAlive)
        } finally {
            allowStop.countDown()
            lifecycle.release()
            lifecycle.awaitStopped()
        }
    }

    @Test(timeout = 10000)
    fun cancellationBeforeRecorderPublicationPreventsStartButStillReleases() {
        val events = mutableListOf<String>()
        val lifecycle = CaptureLifecycle<Any>("test-cancelled-capture",
                { events.add("stop") }, { events.add("release") })
        lifecycle.stop()
        assertNull(lifecycle.awaitStopped())
        assertFalse(lifecycle.start(Any()) { events.add("start") })
        lifecycle.release()
        assertEquals(listOf("stop", "release"), events)
    }

    @Test(timeout = 10000)
    fun cancellationDuringStartReturnsWithoutTakingTheRecorderLock() {
        val startEntered = CountDownLatch(1)
        val allowStart = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val lifecycle = CaptureLifecycle<Any>("test-starting-capture",
                { events.add("stop") }, { events.add("release") })
        val capture = worker {
            try {
                lifecycle.start(Any()) {
                    events.add("start")
                    startEntered.countDown()
                    allowStart.await()
                    events.add("started")
                }
            } finally {
                lifecycle.release()
                assertNull(lifecycle.awaitStopped())
            }
        }
        try {
            assertTrue(startEntered.await(2, SECONDS))
            worker { lifecycle.stop() }.get(2, SECONDS)
            assertEquals(listOf("start"), events.toList())
        } finally {
            allowStart.countDown()
            capture.get(2, SECONDS)
        }
        assertEquals(listOf("start", "started", "stop", "release"), events.toList())
    }

    @Test(timeout = 10000)
    fun slowReleaseDoesNotBlockStopAndTheStopWorkerNeverTouchesAReleasedRecorder() {
        val releaseEntered = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val stopCalls = AtomicInteger()
        val lifecycle = CaptureLifecycle<Any>("test-releasing-capture",
                { stopCalls.incrementAndGet() }, {
                    releaseEntered.countDown()
                    allowRelease.await()
                })
        lifecycle.start(Any()) {}
        val release = worker { lifecycle.release() }
        try {
            assertTrue(releaseEntered.await(2, SECONDS))
            worker { lifecycle.stop() }.get(2, SECONDS)
        } finally {
            allowRelease.countDown()
            release.get(2, SECONDS)
            assertNull(lifecycle.awaitStopped())
        }
        assertEquals(1, stopCalls.get())
    }

    @Test(timeout = 10000)
    fun failedStopStillAllowsReleaseAndReportsFailureAfterWorkerExit() {
        val failure = IllegalStateException("Audio service failed")
        val releaseCalls = AtomicInteger()
        val lifecycle = CaptureLifecycle<Any>("test-failing-stop",
                { throw failure }, { releaseCalls.incrementAndGet() })
        lifecycle.start(Any()) {}
        lifecycle.stop()
        assertSame(failure, lifecycle.awaitStopped())
        try {
            lifecycle.release()
            error("Stop failure must propagate to cleanup")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(1, releaseCalls.get())
        lifecycle.release()
        assertEquals(1, releaseCalls.get())
    }

    @Test(timeout = 10000)
    fun aFailedStartCanBeReleasedBeforeAnotherCaptureAttempt() {
        val events = mutableListOf<String>()
        val lifecycle = CaptureLifecycle<Any>("test-capture-retry",
                { events.add("stop") }, { events.add("release") })
        try {
            lifecycle.start(Any()) { throw IllegalArgumentException("Privileged rejected") }
            error("Start failure must propagate")
        } catch (expected: IllegalArgumentException) {
            lifecycle.release()
        }
        assertTrue(lifecycle.start(Any()) { events.add("legacy") })
        lifecycle.release()
        assertNull(lifecycle.awaitStopped())
        assertEquals(listOf("stop", "release", "legacy", "stop", "release"), events)
    }

    @Test(timeout = 10000)
    fun retirementWaitsDespiteInterruptionAndRestoresTheInterruptFlag() {
        val stopEntered = CountDownLatch(1)
        val allowStop = CountDownLatch(1)
        val lifecycle = CaptureLifecycle<Any>("test-interrupted-retirement", {
            stopEntered.countDown()
            allowStop.await()
        }, {})
        lifecycle.start(Any()) {}
        val retirementEntered = CountDownLatch(1)
        val retirement = worker {
            Thread.currentThread().interrupt()
            retirementEntered.countDown()
            assertNull(lifecycle.awaitStopped())
            assertTrue(Thread.interrupted())
        }
        try {
            assertTrue(retirementEntered.await(2, SECONDS))
            assertTrue(stopEntered.await(2, SECONDS))
            assertFalse(retirement.isDone)
        } finally {
            allowStop.countDown()
            retirement.get(2, SECONDS)
            lifecycle.release()
            lifecycle.awaitStopped()
        }
    }

    private fun worker(action: () -> Unit): FutureTask<Unit> =
            FutureTask<Unit> { action() }.also { Thread(it, "test-capture-worker").start() }
}
