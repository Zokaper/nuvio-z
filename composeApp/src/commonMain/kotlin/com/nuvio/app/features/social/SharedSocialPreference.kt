package com.nuvio.app.features.social

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.sync.ProfileSettingsSync
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Whether Social is on is ONE value per profile, shared by every platform family.
 *
 * ⚠ **Why this is not in the settings blob.** Upstream's profile settings are split by platform
 * family (`mobile` / `desktop`) on a server we do not control, so a preference stored there is
 * really two preferences. For Social that meant a profile OFF on the phone and ON on the desktop:
 * the desktop kept the Social tab, the Watch Together controls and a published Watching Now
 * presence (Debug 83 QA, profile "finalguy"). The canonical copy lives in the Z backend
 * (`profile_preferences`), read and written by every platform; the family blobs no longer carry
 * the field. Nothing else is shared - every other setting stays per family, exactly as before.
 *
 * The engine below is plain logic over three small ports so the two families can be simulated
 * against one fake backend in a test.
 */

/**
 * The one-time rule for a profile that predates the shared value.
 *
 * Only a family that has actually *answered* has a value. One answer is used as it stands; two that
 * agree stand; two that disagree migrate to **ON** - the owner's call, 2026-10-03. Once written
 * there is a single authoritative value and this is never consulted again.
 */
internal fun migrateSharedSocialPreference(own: Boolean?, other: Boolean?): Boolean? = when {
    own == null -> other
    other == null -> own
    own == other -> own
    else -> true
}

/**
 * What a family's settings copy says about Social: the one field the migration reads, and nothing
 * else - every other key in the copy is a device setting that stays with its family.
 */
internal fun socialValueInFamilyCopy(copy: kotlinx.serialization.json.JsonObject?): Boolean? =
    ((copy?.get("features") as? kotlinx.serialization.json.JsonObject)
        ?.get("social_features") as? kotlinx.serialization.json.JsonObject)
        ?.get("social_features_enabled")
        ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
        ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
        ?.takeUnless { it.isString }
        ?.content?.toBooleanStrictOrNull()

/** The Z backend's copy. A failure means "could not ask", which is never the same as "absent". */
internal interface SharedSocialRemote {
    suspend fun get(profileId: String): Result<Boolean?>

    /** Returns the value now in force, so a writer that lost an `ifAbsent` race adopts the winner. */
    suspend fun set(profileId: String, enabled: Boolean, ifAbsent: Boolean): Result<Boolean>
}

/** What a family's own settings blob still says about Social. Read only by the migration. */
internal interface FamilySocialValues {
    val ownFamily: String
    val otherFamily: String

    /** `success(null)` is "that family has no answer"; a failure is "could not read it". */
    suspend fun read(profileIndex: Int, family: String): Result<Boolean?>
}

/** This device's cache of the canonical value, and a change the backend has not yet heard. */
internal interface SharedSocialLocal {
    fun stored(profileId: String): Boolean?
    fun pending(profileId: String): Boolean?

    /** Makes [value] the local value and clears any pending change. False when the profile moved on. */
    fun adopt(profileId: String, value: Boolean): Boolean
}

internal enum class SharedSocialOutcome {
    /** The backend's value was applied (or already matched). */
    Applied,

    /** This device's pending change reached the backend. */
    Pushed,

    /** The one-time migration wrote a value. */
    Migrated,

    /** Nobody has ever answered; nothing to share yet. */
    NothingToShare,

    /** The backend or a family copy could not be read; try again later. Nothing was changed. */
    Offline,
}

internal class SharedSocialPreferenceSync(
    private val local: SharedSocialLocal,
    private val remote: SharedSocialRemote,
    private val families: FamilySocialValues,
) {
    private val mutex = Mutex()

    suspend fun reconcile(profileIndex: Int, profileId: String): SharedSocialOutcome = mutex.withLock {
        // A choice the user made on this device and the backend has not heard outranks whatever the
        // backend holds: the last person to touch the switch is the one who is standing in front of it.
        local.pending(profileId)?.let { pending ->
            val written = remote.set(profileId, pending, ifAbsent = false).getOrElse { return@withLock SharedSocialOutcome.Offline }
            local.adopt(profileId, written)
            return@withLock SharedSocialOutcome.Pushed
        }

        val canonical = remote.get(profileId).getOrElse { return@withLock SharedSocialOutcome.Offline }
        if (canonical != null) {
            if (local.stored(profileId) != canonical) local.adopt(profileId, canonical)
            return@withLock SharedSocialOutcome.Applied
        }

        // No canonical value yet: migrate, from facts only. A family that could not be read is not a
        // family with no answer, and concluding anything from half the picture is how a user ends
        // up ON when one of their devices said OFF.
        val ownRemote = families.read(profileIndex, families.ownFamily)
            .getOrElse { return@withLock SharedSocialOutcome.Offline }
        val otherRemote = families.read(profileIndex, families.otherFamily)
            .getOrElse { return@withLock SharedSocialOutcome.Offline }
        val migrated = migrateSharedSocialPreference(
            own = local.stored(profileId) ?: ownRemote,
            other = otherRemote,
        ) ?: return@withLock SharedSocialOutcome.NothingToShare

        val written = remote.set(profileId, migrated, ifAbsent = true).getOrElse { return@withLock SharedSocialOutcome.Offline }
        local.adopt(profileId, written)
        SharedSocialOutcome.Migrated
    }
}

// ---------------------------------------------------------------------------------- live wiring

private object LiveSharedSocialRemote : SharedSocialRemote {
    override suspend fun get(profileId: String) = SocialRepository.sharedSocialPreference(profileId)
    override suspend fun set(profileId: String, enabled: Boolean, ifAbsent: Boolean) =
        SocialRepository.setSharedSocialPreference(profileId, enabled, ifAbsent)
}

private object LiveFamilySocialValues : FamilySocialValues {
    override val ownFamily: String get() = ProfileSettingsSync.ownFamilyPlatform
    override val otherFamily: String get() = ProfileSettingsSync.otherFamilyPlatform

    override suspend fun read(profileIndex: Int, family: String): Result<Boolean?> =
        ProfileSettingsSync.fetchFamilySocialValue(profileIndex, family)
}

private object LiveSharedSocialLocal : SharedSocialLocal {
    private fun activeIs(profileId: String) = ProfileRepository.state.value.activeProfile?.id == profileId
    override fun stored(profileId: String) =
        if (activeIs(profileId)) SocialFeaturePreferencesRepository.uiState.value.storedPreference else null
    override fun pending(profileId: String) =
        if (activeIs(profileId)) SocialFeaturePreferencesRepository.pendingPreference() else null
    override fun adopt(profileId: String, value: Boolean): Boolean {
        if (!activeIs(profileId)) return false
        SocialFeaturePreferencesRepository.adoptShared(value)
        return true
    }
}

/**
 * Runs the reconcile for the active profile: at profile entry, on foreground, and right after the
 * user changes the switch. Best effort and never blocking - the local cache answers immediately,
 * and an unreachable backend only means the other platforms hear about it later.
 */
object SharedSocialPreference {
    private val log = Logger.withTag("SharedSocialPreference")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = SharedSocialPreferenceSync(LiveSharedSocialLocal, LiveSharedSocialRemote, LiveFamilySocialValues)
    private var lastCompletedAtMs = 0L

    fun requestReconcile(force: Boolean = false) {
        val auth = AuthRepository.state.value
        if (auth !is AuthState.Authenticated || auth.isAnonymous) return
        val profileIndex = ProfileRepository.activeProfileId
        val profileId = ProfileRepository.state.value.activeProfile?.id?.takeIf(String::isNotBlank) ?: return
        val now = com.nuvio.app.features.watchprogress.WatchProgressClock.nowEpochMs()
        if (!force && now - lastCompletedAtMs < MIN_INTERVAL_MS) return
        scope.launch {
            SocialFeaturePreferencesRepository.ensureLoaded()
            val outcome = engine.reconcile(profileIndex, profileId)
            if (outcome != SharedSocialOutcome.Offline) lastCompletedAtMs = now
            log.i { "reconcile profile=${profileId.take(8)} outcome=$outcome" }
        }
    }

    private const val MIN_INTERVAL_MS = 20_000L
}
