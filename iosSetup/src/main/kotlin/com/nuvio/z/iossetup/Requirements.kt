package com.nuvio.z.iossetup

import kotlinx.serialization.Serializable

/**
 * What must be true for Nuvio Z to run on the phone, in dependency order. Replaces the v1 14-step
 * enum: progress is derived from what the computer can see, not from which page the user reached.
 */
@Serializable
enum class Requirement(val title: String, val phase: SetupPhase, val manualOnly: Boolean = false) {
    COMPUTER("Computer ready", SetupPhase.GET_READY),
    DEVICE("iPhone connected", SetupPhase.CONNECT),
    TRUST("Computer trusted on iPhone", SetupPhase.CONNECT),
    LOOPBACK_APP("LocalDevVPN installed", SetupPhase.SET_UP_SIDESTORE),
    SIDESTORE("SideStore installed", SetupPhase.SET_UP_SIDESTORE),
    PAIRING("Pairing file placed", SetupPhase.SET_UP_SIDESTORE),
    DEVELOPER_MODE("Developer Mode on", SetupPhase.SET_UP_SIDESTORE),
    PROFILE_TRUST("Developer profile trusted", SetupPhase.SET_UP_SIDESTORE, manualOnly = true),
    SIDESTORE_READY("SideStore signed in and refreshed", SetupPhase.SET_UP_SIDESTORE, manualOnly = true),
    SOURCE_ADDED("Nuvio Z source added", SetupPhase.INSTALL_NUVIO, manualOnly = true),
    NUVIO("Nuvio Z installed", SetupPhase.INSTALL_NUVIO),
}

enum class RequirementStatus {
    /** Verified by the computer. */
    SATISFIED,
    /** Verified absent: the user has something to do. */
    MISSING,
    /** The computer cannot tell (no probe, or the probe failed). Falls back to the user's confirmation. */
    UNKNOWN,
    /** Cannot be evaluated until an earlier requirement is met, e.g. no phone is attached. */
    BLOCKED,
}

data class RequirementResult(
    val requirement: Requirement,
    val status: RequirementStatus,
    /** True when the user, not the computer, vouched for this requirement. */
    val confirmedByUser: Boolean = false,
    val detail: String = "",
) {
    val done: Boolean get() = status == RequirementStatus.SATISFIED
}

/** Everything the evaluator needs, gathered by the platform layer. Null means "not probed yet". */
data class WorldSnapshot(
    val computer: ComputerCheck? = null,
    val helper: HelperStatus? = null,
)

object Requirements {
    /** Evaluates every requirement. Pure: the same inputs always give the same answer. */
    fun evaluate(world: WorldSnapshot, channel: SetupChannel, confirmed: Set<Requirement>): List<RequirementResult> {
        val device = world.helper?.device
        val trusted = device?.trust == TrustState.VALID
        val results = mutableListOf<RequirementResult>()

        fun add(requirement: Requirement, status: RequirementStatus, detail: String = "") {
            // A user confirmation only ever stands in for something the computer could not decide.
            val vouched = status == RequirementStatus.UNKNOWN && requirement in confirmed
            results += RequirementResult(requirement, if (vouched) RequirementStatus.SATISFIED else status, vouched, detail)
        }
        // Without a helper (not bundled, crashed, or manual mode) nothing about the phone can be probed,
        // so each phone requirement falls back to the user's own confirmation instead of blocking.
        val noHelper = world.helper == null
        val blocked = if (noHelper) RequirementStatus.UNKNOWN else RequirementStatus.BLOCKED
        fun phoneStatus(known: Boolean?, whenNoPhone: RequirementStatus = blocked): RequirementStatus = when {
            device == null || !trusted -> whenNoPhone
            known == null -> RequirementStatus.UNKNOWN
            known -> RequirementStatus.SATISFIED
            else -> RequirementStatus.MISSING
        }
        fun app(app: InstalledApp?): RequirementStatus = when {
            device == null || !trusted -> blocked
            !device.appsKnown -> RequirementStatus.UNKNOWN
            app != null -> RequirementStatus.SATISFIED
            else -> RequirementStatus.MISSING
        }

        add(
            Requirement.COMPUTER,
            when {
                world.computer == null -> RequirementStatus.UNKNOWN
                world.computer.canContinue -> RequirementStatus.SATISFIED
                // Only Apple's driver went undetected: the user may say it is installed, and the real
                // USB check on the next requirement settles it.
                world.computer.supportedOs.state == CheckState.PASS && world.computer.internet.state != CheckState.FAIL -> RequirementStatus.UNKNOWN
                else -> RequirementStatus.MISSING
            },
        )
        add(
            Requirement.DEVICE,
            when {
                world.helper == null -> RequirementStatus.UNKNOWN
                device != null -> RequirementStatus.SATISFIED
                else -> RequirementStatus.MISSING
            },
        )
        add(
            Requirement.TRUST,
            when {
                device == null -> blocked
                trusted -> RequirementStatus.SATISFIED
                else -> RequirementStatus.MISSING
            },
        )
        add(Requirement.LOOPBACK_APP, app(device?.localDevVpn))
        add(Requirement.SIDESTORE, app(device?.sidestore))
        add(
            Requirement.PAIRING,
            when {
                device == null || !trusted -> blocked
                device.sidestore == null && device.appsKnown -> RequirementStatus.BLOCKED
                else -> when (device.pairingFile) {
                    PairingFileState.PRESENT -> RequirementStatus.SATISFIED
                    PairingFileState.ABSENT -> RequirementStatus.MISSING
                    PairingFileState.SIDESTORE_MISSING -> RequirementStatus.BLOCKED
                    PairingFileState.UNKNOWN -> RequirementStatus.UNKNOWN
                }
            },
        )
        add(Requirement.DEVELOPER_MODE, phoneStatus(device?.developerMode))

        // Nothing here can be probed: the user's word is the only evidence, and only once SideStore exists.
        val sideStoreReady = results.first { it.requirement == Requirement.SIDESTORE }.done
        for (manual in listOf(Requirement.PROFILE_TRUST, Requirement.SIDESTORE_READY, Requirement.SOURCE_ADDED)) {
            add(manual, if (sideStoreReady) RequirementStatus.UNKNOWN else RequirementStatus.BLOCKED)
        }

        val nuvio = if (channel == SetupChannel.STABLE) device?.nuvioStable else device?.nuvioDebug
        add(Requirement.NUVIO, app(nuvio), nuvio?.version.orEmpty())
        return results
    }

    /** The first requirement still needing the user's attention, or null when everything is met. */
    fun focus(results: List<RequirementResult>): RequirementResult? = results.firstOrNull { !it.done }

    /**
     * Drops confirmations that no longer make sense: a user who vouched for "source added" did so
     * against a SideStore that may since have been deleted. Called on resume and after every probe.
     */
    fun reconcile(confirmed: Set<Requirement>, results: List<RequirementResult>): Set<Requirement> {
        val byRequirement = results.associateBy { it.requirement }
        val sideStoreGone = byRequirement[Requirement.SIDESTORE]?.status == RequirementStatus.MISSING
        return confirmed.filterTo(mutableSetOf()) { requirement ->
            val manual = requirement.manualOnly
            !(manual && sideStoreGone)
        }
    }
}
