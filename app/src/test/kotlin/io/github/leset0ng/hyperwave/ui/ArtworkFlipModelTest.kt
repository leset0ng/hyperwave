package io.github.leset0ng.hyperwave.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ArtworkFlipModelTest {
    private fun ready(ratio: Double = 1.0): ArtworkFlipModel<String> =
            ArtworkFlipModel<String>().apply {
                update("old", "old-seed", ratio, 0.0, animate = false)
            }

    @Test
    fun firstImageFadesAndResizesWithoutTurning() {
        val model = ArtworkFlipModel<String>()
        model.update("first", "first-seed", 2.0, 0.0)
        assertEquals("first", model.displayed)
        assertNull(model.pending)
        assertEquals(0.0, model.opacity, 0.0)
        assertEquals(1.0, model.aspectRatio, 0.0)
        model.advance(0.125)
        assertEquals(0.5, model.opacity, 0.00001)
        assertEquals(1.5, model.aspectRatio, 0.00001)
        assertEquals(0.0, model.angle, 0.0)
        assertEquals(0.0, model.targetAngle, 0.0)
        model.advance(0.250)
        assertEquals(1.0, model.opacity, 0.0)
        assertEquals(2.0, model.aspectRatio, 0.0)
        assertFalse(model.animating(0.250))
    }

    @Test
    fun sameSeedRefreshIsImmediateAndDoesNotTurn() {
        val model = ready()
        model.update("higher-resolution", "old-seed", 1.5, 0.1)
        assertEquals("higher-resolution", model.displayed)
        assertEquals(1.5, model.aspectRatio, 0.0)
        assertEquals(0.0, model.targetAngle, 0.0)
        assertNull(model.pending)
        assertFalse(model.animating(0.1))
    }

    @Test
    fun forwardAndBackwardRevealAtEightyOneDegreesWithoutResettingRotation() {
        for (direction in ArtworkFlipModel.Direction.entries) {
            val model = ready()
            model.update("new", "new-seed", 1.0, 0.0, direction)
            assertEquals(direction.halfTurn, model.targetAngle, 0.0)
            var time = 0.0
            var previousAngle = 0.0
            while (model.pending != null && time < 0.3) {
                previousAngle = abs(model.angle)
                time += 0.0001
                model.advance(time)
            }
            assertEquals("new", model.displayed)
            assertNull(model.pending)
            assertTrue("Old image must persist until 9 degrees before edge-on", previousAngle < 81.0)
            assertTrue("Reveal must not be delayed to edge-on", previousAngle > 80.9)
            assertTrue(abs(model.angle) >= 81.0 && abs(model.angle) < 81.1)
            assertTrue(model.angle * direction.halfTurn > 0.0)
            assertTrue(abs(model.velocity) > 0.0)
            assertEquals(1.0, model.reveal, 0.00001)
            val revealedAngle = abs(model.angle)
            model.advance(time + 0.075)
            assertTrue(abs(model.angle) > revealedAngle)
            assertTrue(model.reveal > 0.0 && model.reveal < 1.0)
            model.advance(time + 0.151)
            assertEquals(0.0, model.reveal, 0.0)
        }
    }

    @Test
    fun faceRoundingUsesTiesAwayFromZeroAcrossMultipleHalfTurns() {
        assertEquals(0, ArtworkFlipModel.roundFace(89.999))
        assertEquals(0, ArtworkFlipModel.roundFace(-89.999))
        assertEquals(1, ArtworkFlipModel.roundFace(90.0))
        assertEquals(-1, ArtworkFlipModel.roundFace(-90.0))
        assertEquals(2, ArtworkFlipModel.roundFace(270.0))
        assertEquals(-2, ArtworkFlipModel.roundFace(-270.0))
    }

    @Test
    fun renderedAngleChangesFacesWithoutShowingAMirroredBack() {
        for (direction in ArtworkFlipModel.Direction.entries) {
            val model = ready()
            model.update("new", "new-seed", 1.0, 0.0, direction)
            var time = 0.0
            while (abs(model.angle) < 90.0 && time < 0.4) {
                assertEquals(model.angle, model.renderedAngle, 0.00001)
                time += 0.0001
                model.advance(time)
            }
            assertTrue(abs(model.angle) >= 90.0)
            assertTrue(abs(model.renderedAngle) <= 90.0)
            assertTrue(model.renderedAngle * model.angle < 0.0)
            assertEquals(180.0, abs(model.angle - model.renderedAngle), 0.00001)
        }
    }

    @Test
    fun pendingUpdatesMergeAndOnlyLatestBitmapIsRevealed() {
        val model = ready()
        model.update("second", "second-seed", 1.0, 0.0)
        model.update("third", "third-seed", 2.0, 0.05,
                ArtworkFlipModel.Direction.BACKWARD)
        model.update("third-sharp", "third-seed", 2.0, 0.1)
        assertEquals("old", model.displayed)
        assertEquals("third-sharp", model.pending)
        assertEquals(-180.0, model.targetAngle, 0.0)
        model.advance(0.2)
        assertEquals("third-sharp", model.displayed)
        assertNull(model.pending)
        model.advance(0.4)
        assertEquals(2.0, model.aspectRatio, 0.0)
    }

    @Test
    fun newSeedAfterRevealAppendsWhileSpringStillMovesAndUsesCumulativeEdge() {
        val model = ready()
        model.update("second", "second-seed", 1.0, 0.0)
        model.advance(0.2)
        assertEquals("second", model.displayed)
        assertNull(model.pending)
        assertTrue(model.animating(0.2))
        val angle = model.angle
        val velocity = model.velocity
        model.update("third", "third-seed", 1.0, 0.2)
        assertEquals(-360.0, model.targetAngle, 0.0)
        assertEquals(angle, model.angle, 0.00001)
        assertEquals(velocity, model.velocity, 0.00001)
        model.advance(0.2001)
        assertEquals("second", model.displayed)
        assertEquals("third", model.pending)
        var time = 0.2001
        var previousAngle = model.angle
        while (model.pending != null && time < 1.0) {
            previousAngle = model.angle
            time += 0.0001
            model.advance(time)
        }
        assertEquals("third", model.displayed)
        assertTrue("The second reveal must use -261 degrees, not -81", previousAngle > -261.0)
        assertTrue(previousAngle < -260.8)
        assertTrue(model.angle <= -261.0)
    }

    @Test
    fun reversingAfterRevealRetainsPresentationAndVelocity() {
        val model = ready()
        model.update("second", "second-seed", 1.0, 0.0)
        model.advance(0.2)
        val angle = model.angle
        val velocity = model.velocity
        model.update("previous", "previous-seed", 1.0, 0.2,
                ArtworkFlipModel.Direction.BACKWARD)
        assertEquals(0.0, model.targetAngle, 0.0)
        assertEquals(angle, model.angle, 0.00001)
        assertEquals(velocity, model.velocity, 0.00001)
        assertTrue(velocity < 0.0)
        model.advance(0.201)
        assertTrue("Retargeting must not erase the existing momentum", model.angle < angle)
        model.advance(2.5)
        assertEquals("previous", model.displayed)
        assertNull(model.pending)
        assertEquals(0.0, model.angle, 0.01)
    }

    @Test
    fun springIsNotForcedToFinishAtThreeHundredMillisecondsOrClamped() {
        val model = ready()
        model.update("new", "new-seed", 1.0, 0.0)
        model.advance(0.3)
        assertTrue(model.animating(0.3))
        assertTrue(abs(model.angle - model.targetAngle) > 1.0)
        model.advance(0.53)
        assertTrue("The recovered underdamped spring must overshoot", model.angle < -180.0)
        model.advance(3.0)
        assertEquals(-180.0, model.angle, 0.01)
        assertFalse(model.animating(3.0))
    }

    @Test
    fun removalFadesBeforeStartingASeparateAspectRatioReset() {
        val model = ready(2.0)
        model.update(null, null, 1.0, 0.0)
        model.advance(0.125)
        assertEquals("old", model.displayed)
        assertEquals(0.5, model.opacity, 0.00001)
        assertEquals(2.0, model.aspectRatio, 0.0)
        model.advance(0.250)
        assertNull(model.displayed)
        assertEquals(0.0, model.opacity, 0.0)
        assertEquals(2.0, model.aspectRatio, 0.00001)
        model.advance(0.375)
        assertEquals(1.5, model.aspectRatio, 0.00001)
        model.advance(0.500)
        assertEquals(1.0, model.aspectRatio, 0.0)
        assertFalse(model.animating(0.500))
    }

    @Test
    fun newImageDuringRemovalCancelsStalePlaceholderCompletion() {
        val model = ready(2.0)
        model.update(null, null, 1.0, 0.0)
        model.advance(0.125)
        val opacity = model.opacity
        model.update("replacement", "replacement-seed", 3.0, 0.125)
        assertEquals(opacity, model.opacity, 0.00001)
        model.advance(0.250)
        assertEquals("replacement", model.displayed)
        model.advance(0.500)
        assertEquals("replacement", model.displayed)
        assertEquals(3.0, model.aspectRatio, 0.0)
        assertEquals(1.0, model.opacity, 0.0)
        assertEquals(0.0, model.targetAngle, 0.0)
    }

    @Test
    fun newImageDuringRatioResetIsNotOverwrittenByTheOldReset() {
        val model = ready(2.0)
        model.update(null, null, 1.0, 0.0)
        model.advance(0.375)
        model.update("replacement", "replacement-seed", 3.0, 0.375)
        model.advance(0.700)
        assertEquals("replacement", model.displayed)
        assertEquals(3.0, model.aspectRatio, 0.0)
        assertEquals(1.0, model.opacity, 0.0)
    }

    @Test
    fun disablingResolvesLatestImageAndCancelsEveryActiveEffect() {
        val model = ready()
        model.update("pending", "pending-seed", 2.0, 0.0)
        model.advance(0.1)
        model.update("latest", "latest-seed", 3.0, 0.1, animate = false)
        assertEquals("latest", model.displayed)
        assertNull(model.pending)
        assertEquals(0.0, model.angle, 0.0)
        assertEquals(0.0, model.velocity, 0.0)
        assertEquals(0.0, model.targetAngle, 0.0)
        assertEquals(0.0, model.reveal, 0.0)
        assertEquals(1.0, model.opacity, 0.0)
        assertEquals(3.0, model.aspectRatio, 0.0)
        assertFalse(model.animating(0.1))
        model.advance(1.0)
        assertEquals("latest", model.displayed)
        assertEquals(3.0, model.aspectRatio, 0.0)
    }
}
