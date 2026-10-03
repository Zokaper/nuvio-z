package com.nuvio.app.features.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Social on/off is one value per profile, shared by every platform family.
 *
 * Debug 83 QA, profile "finalguy": OFF on the phone, ON on the desktop - the desktop kept the Social
 * tab, the Watch Together controls and a published presence, because the setting lived in two
 * platform-family blobs. These cases simulate both families against ONE fake backend.
 */
class SharedSocialPreferenceTest {

    private val profile = "11111111-2222-3333-4444-555555555555"

    // ------------------------------------------------------------------------------------ fakes

    private class FakeRemote(var value: Boolean? = null) : SharedSocialRemote {
        var offline = false
        var sets = 0

        /** Runs inside `set`, before it looks at the row: lets a test play the other family's migration. */
        var beforeSet: (() -> Unit)? = null

        override suspend fun get(profileId: String): Result<Boolean?> =
            if (offline) Result.failure(IllegalStateException("offline")) else Result.success(value)

        override suspend fun set(profileId: String, enabled: Boolean, ifAbsent: Boolean): Result<Boolean> {
            if (offline) return Result.failure(IllegalStateException("offline"))
            beforeSet?.invoke()
            sets++
            if (!(ifAbsent && value != null)) value = enabled
            return Result.success(checkNotNull(value))
        }
    }

    /** What each family's settings copy still holds from before the shared value existed. */
    private class FamilyBlobs(
        var mobile: Boolean? = null,
        var desktop: Boolean? = null,
        var unreadable: Set<String> = emptySet(),
    ) {
        fun values(own: String) = object : FamilySocialValues {
            override val ownFamily = own
            override val otherFamily = if (own == "mobile") "desktop" else "mobile"
            override suspend fun read(profileIndex: Int, family: String): Result<Boolean?> =
                if (family in unreadable) Result.failure(IllegalStateException("cannot read $family"))
                else Result.success(if (family == "mobile") mobile else desktop)
        }
    }

    private class FakeLocal(var stored: Boolean? = null, var pending: Boolean? = null) : SharedSocialLocal {
        override fun stored(profileId: String) = stored
        override fun pending(profileId: String) = pending
        override fun adopt(profileId: String, value: Boolean): Boolean {
            stored = value
            pending = null
            return true
        }

        /** The user moves the switch: what `SocialFeaturePreferencesRepository.setEnabled` records. */
        fun choose(value: Boolean) {
            stored = value
            pending = value
        }
    }

    private class Device(family: String, val local: FakeLocal, remote: FakeRemote, blobs: FamilyBlobs) {
        val sync = SharedSocialPreferenceSync(local, remote, blobs.values(family))
    }

    private fun device(family: String, remote: FakeRemote, blobs: FamilyBlobs, local: FakeLocal = FakeLocal()) =
        Device(family, local, remote, blobs)

    // ------------------------------------------------------------------- propagation, both ways

    @Test
    fun `mobile turns social off and desktop receives off`() = runTest {
        val remote = FakeRemote(value = true)
        val blobs = FamilyBlobs()
        val mobile = device("mobile", remote, blobs, FakeLocal(stored = true))
        val desktop = device("desktop", remote, blobs, FakeLocal(stored = true))

        mobile.local.choose(false)
        assertEquals(SharedSocialOutcome.Pushed, mobile.sync.reconcile(1, profile))
        assertEquals(false, remote.value)

        assertEquals(SharedSocialOutcome.Applied, desktop.sync.reconcile(1, profile))
        assertEquals(false, desktop.local.stored)
        assertNull(desktop.local.pending)
    }

    @Test
    fun `desktop turns social on and mobile receives on`() = runTest {
        val remote = FakeRemote(value = false)
        val blobs = FamilyBlobs()
        val desktop = device("desktop", remote, blobs, FakeLocal(stored = false))
        val mobile = device("mobile", remote, blobs, FakeLocal(stored = false))

        desktop.local.choose(true)
        desktop.sync.reconcile(1, profile)
        assertEquals(true, remote.value)

        mobile.sync.reconcile(1, profile)
        assertEquals(true, mobile.local.stored)
    }

    // ------------------------------------------------------------------------------- migration

    @Test
    fun `existing mobile off and desktop on migrates to on whichever family arrives first`() = runTest {
        for (first in listOf("mobile", "desktop")) {
            val remote = FakeRemote()
            val blobs = FamilyBlobs(mobile = false, desktop = true)
            val mobile = device("mobile", remote, blobs, FakeLocal(stored = false))
            val desktop = device("desktop", remote, blobs, FakeLocal(stored = true))
            val order = if (first == "mobile") listOf(mobile, desktop) else listOf(desktop, mobile)

            assertEquals(SharedSocialOutcome.Migrated, order[0].sync.reconcile(1, profile), first)
            order[1].sync.reconcile(1, profile)

            assertEquals(true, remote.value, first)
            assertEquals(true, mobile.local.stored, first)
            assertEquals(true, desktop.local.stored, first)
        }
    }

    @Test
    fun `existing mobile on and desktop missing migrates to on`() = runTest {
        val remote = FakeRemote()
        val blobs = FamilyBlobs(mobile = true, desktop = null)
        val desktop = device("desktop", remote, blobs)
        val mobile = device("mobile", remote, blobs, FakeLocal(stored = true))

        // Either family may be the one that reaches the backend first; the answer is the same.
        assertEquals(SharedSocialOutcome.Migrated, desktop.sync.reconcile(1, profile))
        assertEquals(true, remote.value)
        mobile.sync.reconcile(1, profile)
        assertEquals(true, mobile.local.stored)
        assertEquals(true, desktop.local.stored)
    }

    @Test
    fun `existing desktop off and mobile missing migrates to off`() = runTest {
        val remote = FakeRemote()
        val blobs = FamilyBlobs(mobile = null, desktop = false)
        val mobile = device("mobile", remote, blobs)
        val desktop = device("desktop", remote, blobs, FakeLocal(stored = false))

        assertEquals(SharedSocialOutcome.Migrated, mobile.sync.reconcile(1, profile))
        assertEquals(false, remote.value)
        desktop.sync.reconcile(1, profile)
        assertEquals(false, mobile.local.stored)
        assertEquals(false, desktop.local.stored)
    }

    @Test
    fun `both families agreeing keep their answer`() = runTest {
        for (value in listOf(true, false)) {
            val remote = FakeRemote()
            val d = device("desktop", remote, FamilyBlobs(mobile = value, desktop = value), FakeLocal(stored = value))
            d.sync.reconcile(1, profile)
            assertEquals(value, remote.value)
        }
    }

    @Test
    fun `a family's local answer outranks its own stale remote copy during migration`() = runTest {
        val remote = FakeRemote()
        // Desktop switched OFF locally, never pushed; mobile says ON: disagreement, so ON.
        val d = device("desktop", remote, FamilyBlobs(mobile = true, desktop = true), FakeLocal(stored = false))
        d.sync.reconcile(1, profile)
        assertEquals(true, remote.value)
        // And with mobile also OFF, the local OFF stands against desktop's stale remote ON.
        val remote2 = FakeRemote()
        val d2 = device("desktop", remote2, FamilyBlobs(mobile = false, desktop = true), FakeLocal(stored = false))
        d2.sync.reconcile(1, profile)
        assertEquals(false, remote2.value)
    }

    @Test
    fun `nobody ever answered leaves the shared value absent`() = runTest {
        val remote = FakeRemote()
        val d = device("desktop", remote, FamilyBlobs())
        assertEquals(SharedSocialOutcome.NothingToShare, d.sync.reconcile(1, profile))
        assertNull(remote.value)
        assertEquals(0, remote.sets)
        assertNull(d.local.stored)
    }

    @Test
    fun `migration is one time and the shared value is never re-merged from family copies`() = runTest {
        val remote = FakeRemote()
        val blobs = FamilyBlobs(mobile = false, desktop = true)
        val desktop = device("desktop", remote, blobs, FakeLocal(stored = true))
        desktop.sync.reconcile(1, profile)
        assertEquals(true, remote.value)

        // A family copy changes afterwards - an older build still writing it, say. It is not consulted.
        blobs.mobile = false
        blobs.desktop = false
        assertEquals(SharedSocialOutcome.Applied, desktop.sync.reconcile(1, profile))
        assertEquals(true, remote.value)
        assertEquals(true, desktop.local.stored)
    }

    @Test
    fun `two families migrating at once cannot disagree - the first writer wins and the loser adopts it`() = runTest {
        val remote = FakeRemote()
        val blobs = FamilyBlobs(mobile = false, desktop = false)
        val mobile = device("mobile", remote, blobs, FakeLocal(stored = false))
        // Between mobile's read of "absent" and its write, desktop's migration lands ON.
        remote.beforeSet = { remote.value = true; remote.beforeSet = null }

        assertEquals(SharedSocialOutcome.Migrated, mobile.sync.reconcile(1, profile))
        assertEquals(true, remote.value)
        assertEquals(true, mobile.local.stored)
    }

    // ------------------------------------------------------------------------- offline behaviour

    @Test
    fun `an unreachable backend changes nothing`() = runTest {
        val remote = FakeRemote(value = false).also { it.offline = true }
        val d = device("desktop", remote, FamilyBlobs(), FakeLocal(stored = true))
        assertEquals(SharedSocialOutcome.Offline, d.sync.reconcile(1, profile))
        assertEquals(true, d.local.stored)
    }

    @Test
    fun `a change made offline is kept and outranks the backend's value when it can be heard`() = runTest {
        val remote = FakeRemote(value = true).also { it.offline = true }
        val d = device("desktop", remote, FamilyBlobs(), FakeLocal(stored = true))
        d.local.choose(false)
        assertEquals(SharedSocialOutcome.Offline, d.sync.reconcile(1, profile))
        assertEquals(false, d.local.pending)

        remote.offline = false
        assertEquals(SharedSocialOutcome.Pushed, d.sync.reconcile(1, profile))
        assertEquals(false, remote.value)
        assertNull(d.local.pending)
    }

    @Test
    fun `a family copy that cannot be read is not a family with no answer`() = runTest {
        val remote = FakeRemote()
        val blobs = FamilyBlobs(mobile = false, desktop = null, unreadable = setOf("mobile"))
        val desktop = device("desktop", remote, blobs)
        // Reading half the picture could migrate ON when the unread family said OFF.
        assertEquals(SharedSocialOutcome.Offline, desktop.sync.reconcile(1, profile))
        assertNull(remote.value)
        assertEquals(0, remote.sets)
    }

    // ------------------------------------------------------------------------ the migration rule

    @Test
    fun `the migration table`() {
        assertEquals(true, migrateSharedSocialPreference(own = true, other = null))
        assertEquals(false, migrateSharedSocialPreference(own = false, other = null))
        assertEquals(true, migrateSharedSocialPreference(own = null, other = true))
        assertEquals(false, migrateSharedSocialPreference(own = null, other = false))
        assertEquals(true, migrateSharedSocialPreference(own = true, other = true))
        assertEquals(false, migrateSharedSocialPreference(own = false, other = false))
        assertEquals(true, migrateSharedSocialPreference(own = true, other = false))
        assertEquals(true, migrateSharedSocialPreference(own = false, other = true))
        assertNull(migrateSharedSocialPreference(own = null, other = null))
    }

    // ---------------------------------------------------- only the Social field is read from a family

    @Test
    fun `only the social field of a family copy is read and every device setting is ignored`() {
        val copy = buildJsonObject {
            put("version", 4)
            put("features", buildJsonObject {
                put("player_settings", buildJsonObject { put("playback_mode", "INSTANT") })
                put("theme_settings", buildJsonObject { put("theme", "AMOLED") })
                put("social_features", buildJsonObject { put("social_features_enabled", false) })
            })
        }
        assertEquals(false, socialValueInFamilyCopy(copy))
        assertNull(socialValueInFamilyCopy(null))
        assertNull(socialValueInFamilyCopy(JsonObject(emptyMap())))
        assertNull(socialValueInFamilyCopy(buildJsonObject { put("features", buildJsonObject {}) }))
        assertNull(socialValueInFamilyCopy(buildJsonObject {
            put("features", buildJsonObject { put("social_features", buildJsonObject { put("social_features_enabled", JsonNull) }) })
        }))
        assertNull(socialValueInFamilyCopy(buildJsonObject {
            put("features", buildJsonObject { put("social_features", buildJsonObject { put("social_features_enabled", "maybe") }) })
        }))
    }
}
