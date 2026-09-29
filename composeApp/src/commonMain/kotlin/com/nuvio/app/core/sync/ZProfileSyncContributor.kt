package com.nuvio.app.core.sync

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

/**
 * A Z-owned preference that rides in the profile settings blob under `z_features.<key>`.
 *
 * **Why a seam instead of another typed field on the blob.** `ProfileSettingsSync.kt` is shared,
 * byte-identical, between `nuvio-z` and `NuvioZDesktop`, and some Z preferences exist on only one of
 * them (Random Episode is mobile-only). A typed field would have to name a repository the other
 * repository does not have. A contributor names nothing: the per-repository list in
 * `ZProfileSyncContributors.kt` is the only file that differs, and it is five lines long.
 *
 * **Tri-state by construction.** [export] returning null writes nothing, and [applyFromSync]
 * receiving null means "the sender has never heard of this preference" - an older Z build, or
 * vanilla Nuvio pushing the same blob without `z_features` at all - so local must be left alone.
 * This is the same rule as `syncKeysToClear` and the social payload, and the reason a pull once wiped
 * every playback setting a remote had not caught up with.
 */
internal interface ZProfileSyncContributor {
    /** The key under `z_features`. Never rename one: an older client's blob is matched by it. */
    val key: String

    /**
     * What the push observer watches: any state whose change can change [export]. It may be broader
     * than the preference - the observer's signature is [export]'s value, not this flow's, so an
     * unrelated change in the same state produces no push.
     */
    val observed: StateFlow<*>

    fun ensureLoaded()

    fun export(): JsonElement?

    fun applyFromSync(element: JsonElement?)
}
