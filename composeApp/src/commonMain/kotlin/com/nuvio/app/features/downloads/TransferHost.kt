package com.nuvio.app.features.downloads

/**
 * Who runs a download's bytes on this platform, as one value (Phase 9, stage 4).
 *
 * The platform contract used to carry seven members for this - `recoversSystemPauses`,
 * `maxConcurrentTransfers`, `ownsTransferLiveness`, `schedulingDeferredToPlatform`,
 * `requestTransferInventory`, `suspendTransfer`, `cancelTransfer` - and five of them were no-ops
 * everywhere except iOS. The engine now asks one question, and only iOS answers the second half.
 */
internal sealed interface TransferHost {
    /**
     * Android and desktop: the app runs every transfer itself, so the queue's slot count is a real
     * concurrency limit (the device's downloads-at-once setting) and the queue's silence watchdog
     * applies.
     *
     * [recoversSystemPauses] says whether something on the platform brings a system-paused item
     * back. Android does (the background host and the foreground hook); desktop has no such pair,
     * so the queue takes those items back itself.
     */
    data class InProcess(val recoversSystemPauses: Boolean) : TransferHost

    /**
     * iOS: transfers are handed to the system's background session.
     *
     * [window] is the size of the submitted window, not a concurrency cap and not a user setting:
     * the system decides how many actually run, and only tasks submitted before the app left the
     * foreground keep moving while the phone is locked (`.46`). A held task's silence is the
     * system's business, so the queue's watchdog does not apply. Everything that exists only
     * because of this model goes through [coordinator] and [SystemOwnedTransfers].
     */
    class SystemOwned(val window: Int, val coordinator: SystemTransferCoordinator) : TransferHost
}

/** The platform half of [TransferHost.SystemOwned]. Implemented on iOS only. */
internal interface SystemTransferCoordinator {
    /** True while the app is backgrounded and the session, not the engine, fills freed slots. */
    fun isBackgrounded(): Boolean

    /** The tasks the session really holds, or null where it cannot say. May answer on any thread. */
    fun requestInventory(onResult: (List<IosBackgroundTransferReconciler.LiveTransfer>?) -> Unit)

    /** Stops a held task, keeping its bytes. */
    fun suspend(downloadId: String)

    /** Drops a held task outright. */
    fun cancel(downloadId: String)
}

internal val TransferHost.systemOwned: TransferHost.SystemOwned?
    get() = this as? TransferHost.SystemOwned

/** Whether a held transfer's silence belongs to the platform rather than the queue's watchdog. */
internal val TransferHost.ownsTransferLiveness: Boolean
    get() = this is TransferHost.SystemOwned

/** Whether something other than the queue restarts a system-paused item. */
internal val TransferHost.recoversSystemPauses: Boolean
    get() = when (this) {
        is TransferHost.InProcess -> recoversSystemPauses
        // `.43` made backgrounding leave transfers alone; nothing on iOS resumes a system pause.
        is TransferHost.SystemOwned -> false
    }

/** The queue's slot count: the submitted window on iOS, the device setting elsewhere. */
internal fun TransferHost.slotCount(settings: DownloadDeviceSettings): Int = when (this) {
    is TransferHost.InProcess -> settings.effectiveMaxConcurrent
    is TransferHost.SystemOwned -> window
}

/** True while the platform, not the engine, decides what starts next (iOS in the background). */
internal val TransferHost.schedulingDeferredToPlatform: Boolean
    get() = systemOwned?.coordinator?.isBackgrounded() == true
