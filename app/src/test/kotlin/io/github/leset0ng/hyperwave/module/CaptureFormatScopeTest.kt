package io.github.leset0ng.hyperwave.module

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class CaptureFormatScopeTest {
    @Test
    fun exemptionRequiresTheExactFormatObjectAndEndsWithTheOperation() {
        val scope = CaptureFormatScope()
        val format = Any()
        assertFalse(scope.allows(format))
        scope.withFormat(format) {
            assertTrue(scope.allows(format))
            assertFalse(scope.allows(Any()))
            assertFalse(scope.allows(null))
        }
        assertFalse(scope.allows(format))
    }

    @Test
    fun nestedOperationsAndExceptionsRestoreThePreviousScope() {
        val scope = CaptureFormatScope()
        val outer = Any()
        val inner = Any()
        scope.withFormat(outer) {
            try {
                scope.withFormat(inner) {
                    assertFalse(scope.allows(outer))
                    assertTrue(scope.allows(inner))
                    throw IllegalStateException("test")
                }
            } catch (expected: IllegalStateException) {
                assertTrue(scope.allows(outer))
                assertFalse(scope.allows(inner))
            }
        }
        assertFalse(scope.allows(outer))
    }

    @Test
    fun exemptionDoesNotReachAnotherThread() {
        val scope = CaptureFormatScope()
        val format = Any()
        val leaked = AtomicBoolean(true)
        scope.withFormat(format) {
            val other = Thread { leaked.set(scope.allows(format)) }
            other.start()
            other.join(5000)
            assertFalse(other.isAlive)
            assertFalse(leaked.get())
            assertTrue(scope.allows(format))
        }
    }
}
