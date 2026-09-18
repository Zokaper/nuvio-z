package com.nuvio.app.features.watchparty

import co.touchlab.kermit.Logger
import com.nuvio.app.core.debug.isDebugBuild
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Debug-build-only correlation for the Watch Together latency path.
 *
 * Keep this deliberately boring and privacy-safe. It may identify a party, profile, command, and
 * generation, but it must never receive or print a source descriptor, URL, header, invite code, or
 * credential. The T0-T4 names are stable so logs from two clients can be joined mechanically.
 */
internal object WatchPartyDiagnostics {
    private val log = Logger.withTag("WatchPartyTrace")
    private var channelSerial = 0L
    private var channelInstanceId: String? = null
    private var realtimeState = "detached"
    private var polling = false
    private var apiState = "unknown"

    @OptIn(ExperimentalUuidApi::class)
    fun input(
        kind: PartyCommandKind,
        positionMs: Long,
        party: WatchPartyState?,
        actorProfileId: String?,
        source: String,
    ): String {
        if (!isDebugBuild) return ""
        val inputId = Uuid.random().toString()
        log.i {
            "T0 input=$inputId atEpochMs=${currentEpochMs()} kind=$kind posMs=$positionMs " +
                "source=$source ${identity(party, actorProfileId)} ${transportFacts()}"
        }
        return inputId
    }

    fun accepted(inputId: String?, command: PartyCommand, party: WatchPartyState) {
        accepted(inputId, command, party.id)
    }

    fun accepted(inputId: String?, command: PartyCommand, partyId: String) {
        if (!isDebugBuild) return
        log.i {
            "T1 input=${inputId ?: "none"} atEpochMs=${currentEpochMs()} result=accepted " +
                "${commandFacts(command, partyId)} ${transportFacts()}"
        }
    }

    fun rejected(
        inputId: String,
        kind: PartyCommandKind,
        party: WatchPartyState?,
        actorProfileId: String?,
        reason: String,
    ) {
        if (!isDebugBuild) return
        log.i {
            "T1 input=$inputId atEpochMs=${currentEpochMs()} result=rejected reason=$reason kind=$kind " +
                "${identity(party, actorProfileId)} ${transportFacts()}"
        }
    }

    fun send(command: PartyCommand, partyId: String, startedAtEpochMs: Long, outcome: String) {
        if (!isDebugBuild) return
        val finishedAt = currentEpochMs()
        log.i {
            "T2 atEpochMs=$finishedAt durationMs=${(finishedAt - startedAtEpochMs).coerceAtLeast(0L)} " +
                "outcome=$outcome ${commandFacts(command, partyId)} ${transportFacts()}"
        }
    }

    fun received(command: PartyCommand, partyId: String, outcome: String) {
        if (!isDebugBuild) return
        log.i {
            "T3 atEpochMs=${currentEpochMs()} outcome=$outcome ${commandFacts(command, partyId)} " +
                transportFacts()
        }
    }

    fun applied(command: PartyCommand, partyId: String?, outcome: String) {
        if (!isDebugBuild) return
        log.i {
            "T4 atEpochMs=${currentEpochMs()} outcome=$outcome ${commandFacts(command, partyId)} " +
                "clockUsable=${WatchPartySync.isClockUsable()} clockPrecise=${WatchPartySync.isPrecise()} " +
                "tickAgeMs=${WatchPartySync.tickAgeMs()} ${transportFacts()}"
        }
    }

    fun channelAttached(partyId: String): String {
        if (!isDebugBuild) return "disabled"
        channelSerial += 1L
        val id = "ch-$channelSerial"
        channelInstanceId = id
        transport("channel-attached", partyId, realtime = "attached")
        return id
    }

    fun channelDetached(partyId: String?) {
        if (!isDebugBuild) return
        transport("channel-detached", partyId, realtime = "detached")
        channelInstanceId = null
    }

    fun transport(event: String, partyId: String?, realtime: String, detail: String? = null) {
        if (!isDebugBuild) return
        realtimeState = realtime
        log.i {
            "transport atEpochMs=${currentEpochMs()} event=$event party=${partyId.shortId()} " +
                "realtime=$realtime ${transportFacts()}${detail?.let { " detail=$it" } ?: ""}"
        }
    }

    fun poll(partyId: String?, running: Boolean, api: String, sequence: Long? = null, durationMs: Long? = null) {
        if (!isDebugBuild) return
        polling = running
        if (api != "idle") apiState = api
        log.i {
            "durable atEpochMs=${currentEpochMs()} event=poll party=${partyId.shortId()} " +
                "polling=$running api=$api sequence=${sequence ?: -1L} durationMs=${durationMs ?: -1L} " +
                transportFacts()
        }
    }

    fun durableCommand(command: WatchPartyCommand, party: WatchPartyState?, outcome: String, durationMs: Long) {
        if (!isDebugBuild) return
        log.i {
            "durable atEpochMs=${currentEpochMs()} event=command outcome=$outcome durationMs=$durationMs " +
                "command=${command.commandId} kind=${command.type} ${identity(party, null)} ${transportFacts()}"
        }
    }

    fun durableState(partyId: String, sequence: Long, status: WatchPartyStatus, applied: Boolean) {
        if (!isDebugBuild) return
        log.i {
            "durable atEpochMs=${currentEpochMs()} event=broadcast party=${partyId.shortId()} " +
                "sequence=$sequence status=$status applied=$applied ${transportFacts()}"
        }
    }

    fun hold(
        partyId: String?,
        profileId: String,
        event: String,
        engineState: WatchPartyStatus?,
        telemetryAgeMs: Long,
        holdAgeMs: Long,
        classification: String,
    ) {
        if (!isDebugBuild) return
        log.i {
            "hold atEpochMs=${currentEpochMs()} event=$event party=${partyId.shortId()} " +
                "member=${profileId.shortId()} engine=${engineState ?: "unknown"} " +
                "telemetryAgeMs=$telemetryAgeMs holdAgeMs=$holdAgeMs classification=$classification " +
                "reason=guest-buffering"
        }
    }

    private fun transportFacts(): String =
        "channel=${channelInstanceId ?: "none"} realtime=$realtimeState polling=$polling api=$apiState"

    private fun commandFacts(command: PartyCommand, partyId: String?): String =
        "party=${partyId.shortId()} command=${command.commandId} actor=${command.issuedByProfileId.shortId()} " +
            "counter=${command.counter} generation=${command.contentGeneration}/${command.sourceGeneration}/${command.authorityEpoch} " +
            "kind=${command.kind}"

    private fun identity(party: WatchPartyState?, actorProfileId: String?): String =
        "party=${party?.id.shortId()} actor=${actorProfileId.shortId()} " +
            "generation=${party?.contentGeneration ?: -1L}/${party?.sourceGeneration ?: -1L}/${party?.authorityEpoch ?: -1L}"
}
