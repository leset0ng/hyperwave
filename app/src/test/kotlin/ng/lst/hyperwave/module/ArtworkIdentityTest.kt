package ng.lst.hyperwave.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import ng.lst.hyperwave.module.ArtworkFlipHooks.Delivery
import ng.lst.hyperwave.ui.ArtworkFlipModel
import org.junit.Test

class ArtworkIdentityTest {
    @Test
    fun metadataAndPlaceholderWaitForTheNextDecodedArtwork() {
        val model = ArtworkFlipModel<String>()
        model.update("old-image", "old-song", 1.0, 0.0, animate = false)
        assertEquals(Delivery.HOLD, ArtworkFlipHooks.delivery("old-song", 1L, "new-song", 1L))
        assertEquals(Delivery.HOLD, ArtworkFlipHooks.delivery("old-song", 1L, "new-song", null))
        model.advance(1.0)
        assertEquals("old-image", model.displayed)
        assertNull(model.pending)
        assertEquals(0.0, model.targetAngle, 0.0)
        assertEquals(Delivery.INSTALL, ArtworkFlipHooks.delivery("old-song", 1L, "new-song", 2L))
        model.update("decoded-new-image", "new-song", 1.0, 1.0)
        assertEquals("old-image", model.displayed)
        assertEquals("decoded-new-image", model.pending)
        assertEquals(-180.0, model.targetAngle, 0.0)
    }

    @Test
    fun artworkRefreshesStillReachTheModelWithoutAddingAnotherTurn() {
        val model = ArtworkFlipModel<String>()
        model.update("old-image", "song", 1.0, 0.0, animate = false)
        assertEquals(Delivery.INSTALL, ArtworkFlipHooks.delivery("song", 1L, "song", 2L))
        model.update("higher-resolution", "song", 1.0, 0.1)
        assertEquals("higher-resolution", model.displayed)
        assertEquals(0.0, model.targetAngle, 0.0)
        assertEquals(Delivery.HOLD, ArtworkFlipHooks.delivery("song", 2L, "song", 2L))
        assertEquals(Delivery.CLEAR, ArtworkFlipHooks.delivery("song", 2L, "song", null))
        assertEquals(Delivery.CLEAR, ArtworkFlipHooks.delivery("song", 2L, null, null))
    }

    @Test
    fun stableMediaIdSurvivesMetadataCompletion() {
        val early = ArtworkFlipHooks.identity("player", 0, "session", "track-id", "Song", null)
        val complete = ArtworkFlipHooks.identity("player", 0, "session", "track-id", "Song", "Artist")
        assertEquals(early, complete)
    }

    @Test
    fun distinctMediaIdsDisambiguateIdenticalTitles() {
        assertNotEquals(ArtworkFlipHooks.identity("player", 0, "session", "first", "Song", "Artist"),
                ArtworkFlipHooks.identity("player", 0, "session", "second", "Song", "Artist"))
    }

    @Test
    fun missingMediaIdFallsBackToSongAndKeepsSessionsAndUsersSeparate() {
        val seed = ArtworkFlipHooks.identity("player", 0, "session", null, "Song", "Artist")
        assertEquals(seed, ArtworkFlipHooks.identity("player", 0, "session", "", "Song", "Artist"))
        assertNotEquals(seed, ArtworkFlipHooks.identity("player", 0, "session", null, "Other", "Artist"))
        assertNotEquals(seed, ArtworkFlipHooks.identity("player", 1, "session", null, "Song", "Artist"))
        assertNotEquals(seed, ArtworkFlipHooks.identity("player", 0, "other", null, "Song", "Artist"))
    }
}
