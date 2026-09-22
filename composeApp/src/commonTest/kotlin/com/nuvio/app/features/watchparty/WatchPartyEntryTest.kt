package com.nuvio.app.features.watchparty

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WatchPartyEntryTest {
    @Test
    fun existingPartyJoinAlwaysOpensPrePlaybackLobby() {
        val party = party("joined", contentId = "same-title")

        assertEquals(
            ExistingPartyJoinOutcome.OpenPrePlaybackLobby(party.id),
            existingPartyJoinOutcome(party),
        )
    }
    private val target = PartyContent(contentId = "tt14", contentType = "movie", videoId = "tt14", title = "Stage 14")
    private val otherTarget = PartyContent(contentId = "tt99", contentType = "movie", videoId = "tt99", title = "Other Movie")

    @Test fun heldActivePartyReopensWithoutRestoreOrCreate() = runBlocking {
        val held = party("held")
        var restored = false
        var created = false

        val result = resolveWatchPartyEntry(
            targetContent = target,
            heldParty = held,
            restoreActive = { restored = true; Result.success(null) },
            createParty = { created = true; Result.success(party("created")) },
        )

        assertSame(held, result.getOrThrow())
        assertFalse(restored)
        assertFalse(created)
    }

    @Test fun heldPartyForDifferentTitleIsDepartedAndNewPartyCreated() = runBlocking {
        val held = party("held")
        var departed = false
        var created = false

        val result = resolveWatchPartyEntry(
            targetContent = otherTarget,
            heldParty = held,
            restoreActive = { Result.success(null) },
            departOldParty = { departed = true; Result.success(Unit) },
            createParty = { created = true; Result.success(party("created", contentId = "tt99")) },
        )

        assertEquals("created", result.getOrThrow().id)
        assertTrue(departed)
        assertTrue(created)
    }

    @Test fun backendActivePartyReopensInsteadOfCreatingASecondParty() = runBlocking {
        val restoredParty = party("restored")
        var created = false

        val result = resolveWatchPartyEntry(
            targetContent = target,
            heldParty = null,
            restoreActive = { Result.success(restoredParty) },
            createParty = { created = true; Result.success(party("created")) },
        )

        assertSame(restoredParty, result.getOrThrow())
        assertFalse(created)
    }

    @Test fun backendPartyForDifferentTitleIsDepartedAndNewPartyCreated() = runBlocking {
        val restoredParty = party("restored")
        var departed = false
        var created = false

        val result = resolveWatchPartyEntry(
            targetContent = otherTarget,
            heldParty = null,
            restoreActive = { Result.success(restoredParty) },
            departOldParty = { departed = true; Result.success(Unit) },
            createParty = { created = true; Result.success(party("created", contentId = "tt99")) },
        )

        assertEquals("created", result.getOrThrow().id)
        assertTrue(departed)
        assertTrue(created)
    }

    @Test fun partyIsCreatedOnlyWhenNoActivePartyExists() = runBlocking {
        val createdParty = party("created")

        val result = resolveWatchPartyEntry(
            targetContent = target,
            heldParty = party("ended", WatchPartyStatus.ended),
            restoreActive = { Result.success(null) },
            createParty = { Result.success(createdParty) },
        )

        assertSame(createdParty, result.getOrThrow())
    }

    @Test fun restoreFailureDoesNotAttemptConflictingCreation() = runBlocking {
        var created = false
        val failure = IllegalStateException("restore failed")

        val result = resolveWatchPartyEntry(
            targetContent = target,
            heldParty = null,
            restoreActive = { Result.failure(failure) },
            createParty = { created = true; Result.success(party("created")) },
        )

        assertTrue(result.isFailure)
        assertEquals(failure, result.exceptionOrNull())
        assertFalse(created)
    }

    private fun party(
        id: String,
        status: WatchPartyStatus = WatchPartyStatus.lobby,
        contentId: String = "tt14",
    ) = WatchPartyState(
        id = id,
        hostProfileId = "host",
        status = status,
        controlMode = WatchPartyControlMode.host_only,
        contentGeneration = 1,
        content = PartyContent(contentId = contentId, contentType = "movie", videoId = contentId, title = "Stage 14"),
        positionMs = 0,
        durationMs = 0,
        playbackSpeed = 1f,
        sequence = 1,
        stateUpdatedAt = "2026-09-08T00:00:00Z",
    )
}
