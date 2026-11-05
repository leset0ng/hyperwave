package io.github.leset0ng.hyperwave.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCaptureFallbackTest {
    @Test
    fun successfulPrivilegedCaptureDoesNotTryLegacy() {
        val attempts = mutableListOf<Boolean>()
        runCaptureWithFallback({ false }, { true }, { attempts.add(it) }, { error("Unexpected fallback") })
        assertEquals(listOf(true), attempts)
    }

    @Test
    fun rejectedPrivilegedAttemptIsCleanedUpBeforeLegacyStarts() {
        val events = mutableListOf<String>()
        runCaptureWithFallback({ false }, { true }, { privileged ->
            try {
                events.add("start:$privileged")
                if (privileged) throw IllegalArgumentException("Missing server hook")
            } finally {
                events.add("cleanup:$privileged")
            }
        }, { events.add("retry") })
        assertEquals(listOf("start:true", "cleanup:true", "retry", "start:false", "cleanup:false"), events)
    }

    @Test
    fun twoFailuresEscapeOnceWithTheOriginalRejectionRetained() {
        val first = IllegalArgumentException("Privileged rejected")
        val second = IllegalStateException("Legacy rejected")
        val attempts = mutableListOf<Boolean>()
        var retries = 0
        try {
            runCaptureWithFallback({ false }, { true }, { privileged ->
                attempts.add(privileged)
                throw if (privileged) first else second
            }, { retries++ })
            error("Both capture failures must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(second, actual)
            assertEquals(listOf(first), actual.suppressed.toList())
        }
        assertEquals(listOf(true, false), attempts)
        assertEquals(1, retries)
    }

    @Test
    fun stoppingDuringCleanupDoesNotStartLegacyOrReportFailure() {
        var stopped = false
        val attempts = mutableListOf<Boolean>()
        runCaptureWithFallback({ stopped }, { true }, { privileged ->
            attempts.add(privileged)
            try {
                throw IllegalArgumentException("Privileged rejected")
            } finally {
                stopped = true
            }
        }, { error("Stopped capture must not retry") })
        assertEquals(listOf(true), attempts)
    }

    @Test
    fun stoppingBeforeOrDuringTheRetryNotificationCancelsWork() {
        var attempted = false
        runCaptureWithFallback({ true }, { true }, { attempted = true }, { error("Unexpected retry") })
        assertFalse(attempted)

        var stopped = false
        var attempts = 0
        runCaptureWithFallback({ stopped }, { true }, { _ ->
            attempts++
            throw IllegalArgumentException("Privileged rejected")
        }, { stopped = true })
        assertEquals(1, attempts)
    }

    @Test
    fun streamingErrorsAndIncompleteCleanupDoNotRetry() {
        // Production permits retry only before recording starts and after cleanup succeeds.
        for (state in listOf(true to true, false to false)) {
            val (started, cleanedUp) = state
            var retried = false
            val failure = IllegalStateException("Cannot continue capture")
            try {
                runCaptureWithFallback({ false }, { !started && cleanedUp }, { throw failure }, { retried = true })
                error("Non-retryable failure must propagate")
            } catch (actual: IllegalStateException) {
                assertSame(failure, actual)
            }
            assertFalse(retried)
        }
    }

    @Test
    fun fatalErrorsDoNotTriggerAnotherRecordingAttempt() {
        val fatal = LinkageError("Native failure")
        var retried = false
        try {
            runCaptureWithFallback({ false }, { true }, { throw fatal }, { retried = true })
            error("Fatal error must propagate")
        } catch (actual: LinkageError) {
            assertSame(fatal, actual)
        }
        assertFalse(retried)
    }

    @Test
    fun repeatedExceptionInstancesDoNotCauseSelfSuppression() {
        val failure = IllegalStateException("Shared failure")
        try {
            runCaptureWithFallback({ false }, { true }, { throw failure }, {})
            error("Both failures must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
            assertTrue(actual.suppressed.isEmpty())
        }
    }
}
