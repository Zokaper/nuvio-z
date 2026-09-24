package com.nuvio.app.features.downloads

/**
 * The repository's answer when the platform finds a transfer nobody is listening to.
 *
 * On iOS a background `URLSession` task outlives the code that started it: it keeps
 * running across suspension, and across a relaunch it is simply there. The session asks
 * the repository whether it still wants it; see `IosBackgroundTransferReconciler` for the
 * ownership rules this serves.
 */
internal sealed interface NativeTransferClaim {
    /** The repository now holds this transfer and hears about it through [listener]. */
    data class Adopted(val listener: DownloadTransferListener) : NativeTransferClaim

    /** Wanted, but not now: every slot is taken, or the user paused it. Its bytes are kept. */
    data object Suspend : NativeTransferClaim

    /** The download is gone, finished or failed; the task has nothing left to do. */
    data object Cancel : NativeTransferClaim
}

/** What the background scheduler may start from: queued items in order, and the slots already held. */
internal data class NativeSchedulingSnapshot(
    val prepared: List<IosBackgroundTransferReconciler.IosPreparedTransfer>,
    val claimedIds: Set<String>,
)
