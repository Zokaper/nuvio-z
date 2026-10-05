package com.nuvio.z.iossetup

enum class ErrorCode {
    /** The device helper could not run or spoke a protocol we do not understand. */
    HELPER_UNAVAILABLE,
    /** Apple's device service (usbmuxd) is not answering. */
    TRANSPORT_DOWN,
    /** The service is up but no iPhone is attached over USB. */
    NO_DEVICE,
    /** An iPhone is attached but has not (yet) trusted this computer. */
    TRUST_PENDING,
    /** The phone trusts us but one of the read probes failed (locked screen, timeout). */
    PROBE_FAILED,
}

/** A failure the user can act on. [rawDetail] is for diagnostics only and is never the headline. */
data class SetupError(val code: ErrorCode, val rawDetail: String = "") {
    val headline: String get() = when (code) {
        ErrorCode.HELPER_UNAVAILABLE -> "The device checker could not start"
        ErrorCode.TRANSPORT_DOWN -> "Apple device communication is not ready"
        ErrorCode.NO_DEVICE -> "Waiting for your iPhone"
        ErrorCode.TRUST_PENDING -> "Waiting for you to tap Trust on the iPhone"
        ErrorCode.PROBE_FAILED -> "Couldn’t read everything from the iPhone"
    }

    fun recovery(isMac: Boolean): List<TroubleTip> = when (code) {
        ErrorCode.HELPER_UNAVAILABLE -> listOf(
            TroubleTip("The checker did not start", listOf(
                "Close and reopen this app.",
                "If it keeps happening, security software may be blocking it; use Help & diagnostics and continue in manual mode.",
            )),
        )
        ErrorCode.TRANSPORT_DOWN -> if (isMac) listOf(
            TroubleTip("macOS’s iPhone service is not responding", listOf("Unplug the iPhone, restart the Mac, then reconnect and unlock it.")),
        ) else listOf(
            TroubleTip("Apple Mobile Device Service is not running", listOf(
                "Use Repair with Apple’s installer, or restart Windows.",
                "Open Apple Devices or iTunes once, then reconnect the unlocked iPhone.",
            )),
        )
        ErrorCode.NO_DEVICE -> listOf(
            TroubleTip("No iPhone is detected", buildList {
                add("Unlock the phone and keep its screen on.")
                add("Try another USB port and a data-capable cable; some cables only charge.")
                add("Plug straight into the computer, not a hub or adapter.")
                if (isMac) add("On Apple silicon Macs, click Allow if macOS asks whether the accessory may connect.")
            }),
        )
        ErrorCode.TRUST_PENDING -> listOf(
            TroubleTip("‘Trust This Computer?’ did not appear", listOf(
                "Unplug and reconnect while the iPhone is unlocked.",
                "If you previously tapped Don’t Trust, reset Location & Privacy in iPhone Settings → General → Transfer or Reset iPhone → Reset, then reconnect.",
            )),
        )
        ErrorCode.PROBE_FAILED -> listOf(
            TroubleTip("Some checks could not run", listOf(
                "Keep the iPhone unlocked with the screen on.",
                "Anything we couldn’t check will ask for your confirmation instead.",
            )),
        )
    }

    companion object {
        /** The most useful single explanation for what the helper reported, or null when all is well. */
        fun from(status: HelperStatus?): SetupError? {
            if (status == null) return SetupError(ErrorCode.HELPER_UNAVAILABLE)
            if (!status.usbmuxdReachable) return SetupError(ErrorCode.TRANSPORT_DOWN, status.errors["usbmuxd"].orEmpty())
            val device = status.device ?: return SetupError(ErrorCode.NO_DEVICE)
            if (device.trust != TrustState.VALID) return SetupError(ErrorCode.TRUST_PENDING, status.errors["trust"].orEmpty())
            val failed = status.errors.filterKeys { it != "trust" }
            return if (failed.isEmpty()) null else SetupError(ErrorCode.PROBE_FAILED, failed.entries.joinToString("; ") { "${it.key}: ${it.value}" })
        }
    }
}
