package com.nuvio.app.features.watchparty

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * What clients say to each other directly, over the party's own websocket.
 *
 * Everything timing-critical used to take two server hops: the host's button reached PostgREST,
 * Postgres committed it, a trigger called `realtime.send`, and only then did it reach a guest. The
 * best measurement of that path was about 225ms and it is a floor, not an average. None of the
 * three messages here touch the database - the party channel is already open and already gated by
 * RLS on `realtime.messages`, so a member can talk to the other members over it for the cost of one
 * hop.
 *
 * The database keeps everything it was already good at: membership, readiness, the host's identity,
 * content, the snapshot a late joiner needs, and every authorization decision. It is simply no
 * longer on the path between a button and a pause.
 *
 * Hand-rolled over [JsonObject] rather than `@Serializable`, for the same reason
 * `core/sync/SyncPreferenceJson.kt` is: it keeps the file compilable outside Gradle with only the
 * JSON runtime, so `scripts/run-pure-suites.sh` can execute the encoder against the decoder rather
 * than trusting them to agree.
 *
 * Field names are short because a tick goes out twice a second per party.
 */

/**
 * Bumped when a payload stops meaning what an older build thinks it means.
 *
 * Desktop and mobile ship separately, so a new client and an old one meet in the field routinely.
 * An unreadable or unknown message decodes to null, the client falls back to the database anchor,
 * and the party works less well instead of not at all.
 */
const val WatchPartySyncProtocolVersion = 2

/** One broadcast event carries all of it, so a single collector sees every message in order. */
const val WatchPartySyncEvent = "sync"

private const val TypeTick = "tick"
private const val TypeCommand = "cmd"
private const val TypeClockPing = "clk_ping"
private const val TypeClockPong = "clk_pong"
private const val TypePeerStatus = "peer"

/**
 * Which of the party's two live topics a message arrived on.
 *
 * Carried into validation rather than read off the payload, because the payload is exactly the
 * thing that cannot be trusted to say. Supabase Realtime authorizes a private channel once, at
 * join, with a stub row whose payload is NULL, and caches the answer - there is no per-broadcast
 * payload hook - so "who sent this" is only knowable from *where it could have been written*. See
 * `watchPartyAuthorityTopic`.
 */
enum class PartyRealtimePlane { Authority, Peer }

/**
 * Whether a decoded message may be acted on, given the plane it arrived on.
 *
 * A whitelist per plane rather than a rejection of the one case that matters today, so a message
 * type added later gets a decision made about it here instead of inheriting whichever branch it
 * happened to fall through.
 */
fun partyMessageIsAdmissible(message: PartySyncMessage, plane: PartyRealtimePlane): Boolean =
    when (plane) {
        // Only the backend can write the authority topic, so only it may carry a command.
        PartyRealtimePlane.Authority -> message is PartyCommandMessage
        // Members write the peer topic, so nothing arriving on it may command the party.
        PartyRealtimePlane.Peer -> message !is PartyCommandMessage
    }

sealed interface PartySyncMessage {
    val partyId: String
    val fromProfileId: String
    val contentGeneration: Int
    val sourceGeneration: Int
    val authorityEpoch: Long
}

/** The host's position, paired with the instant it was read. See [PartyTick]. */
data class PartyTickMessage(
    override val fromProfileId: String,
    val tick: PartyTick,
) : PartySyncMessage {
    override val partyId: String get() = tick.partyId
    override val contentGeneration: Int get() = tick.contentGeneration
    override val sourceGeneration: Int get() = tick.sourceGeneration
    override val authorityEpoch: Long get() = tick.authorityEpoch
}

/** A transport action addressed to a party instant. See [PartyCommand]. */
data class PartyCommandMessage(
    override val partyId: String,
    val command: PartyCommand,
) : PartySyncMessage {
    override val fromProfileId: String get() = command.issuedByProfileId
    override val contentGeneration: Int get() = command.contentGeneration
    override val sourceGeneration: Int get() = command.sourceGeneration
    override val authorityEpoch: Long get() = command.authorityEpoch
}

/** A guest asking the host what time it is. */
data class PartyClockPingMessage(
    override val partyId: String,
    override val fromProfileId: String,
    val exchangeId: String,
    val sentAtMs: Long,
    override val contentGeneration: Int = 0,
    override val sourceGeneration: Int = 0,
    override val authorityEpoch: Long = 0L,
) : PartySyncMessage

/**
 * The host answering.
 *
 * [sentAtMs] is echoed rather than remembered by the asker, so the host holds no per-guest state
 * and a pong that arrives after the guest gave up costs nothing. [toProfileId] is there because a
 * broadcast reaches the whole channel; everyone else drops it.
 */
data class PartyClockPongMessage(
    override val partyId: String,
    override val fromProfileId: String,
    val toProfileId: String,
    val exchangeId: String,
    val sentAtMs: Long,
    val hostAtMs: Long,
    override val contentGeneration: Int = 0,
    override val sourceGeneration: Int = 0,
    override val authorityEpoch: Long = 0L,
) : PartySyncMessage

/**
 * What a member other than the host is doing, so the host can decide whether to wait for them.
 *
 * [rttMs] rides along because the host cannot measure it: a ping carries the *guest's* clock, and
 * the host has no offset for it. The barrier lead is sized from the worst round trip in the party,
 * so the party has to be told what those are. -1 means not measured yet.
 */
data class PartyPeerStatusMessage(
    override val partyId: String,
    override val fromProfileId: String,
    val status: WatchPartyStatus,
    val atPartyMs: Long,
    val rttMs: Long = -1L,
    /**
     * Whether this member's engine has nothing left to play, whatever [status] says.
     *
     * ⚠ **[status] alone cannot answer "has this guest recovered", and reading it as though it
     * could is what made a host resume on a guest that was still starved.** `paused` is produced
     * by two unrelated facts: a member that is full and parked, and a member that is empty and has
     * been *told* to stop by the very hold that is waiting for it. Pausing a starving player stops
     * it looking starved, because `isLoading` is starvation measured against an intent to play and
     * the pause removes the intent. So the host's own stall hold erased the evidence that
     * justified it - see `GuestBufferingWatch.observe`.
     *
     * This is the engine's buffer occupancy instead, which no command can change. False from a
     * build that does not send it, which is exactly the behaviour those builds already have.
     */
    val starved: Boolean = false,
    /**
     * This member is in the party and deliberately not watching. See `PartyPresence.kt`.
     *
     * Orthogonal to [status] and to [starved], and it has to be sent rather than inferred for the
     * same reason [starved] does: a backgrounded member reports `paused` with a full buffer, which
     * is byte-for-byte what a person pressing pause reports. The host read one as the other and
     * held the party for a stall that was somebody pressing Home.
     *
     * False from a build that does not send it, which is what those builds mean.
     */
    val away: Boolean = false,
    override val contentGeneration: Int = 0,
    override val sourceGeneration: Int = 0,
    override val authorityEpoch: Long = 0L,
) : PartySyncMessage

fun encodePartySyncMessage(message: PartySyncMessage): JsonObject = buildJsonObject {
    put("v", WatchPartySyncProtocolVersion)
    put("p", message.partyId)
    put("f", message.fromProfileId)
    put("sender_profile_id", message.fromProfileId)
    put("content_generation", message.contentGeneration)
    put("source_generation", message.sourceGeneration)
    put("authority_epoch", message.authorityEpoch)
    when (message) {
        is PartyTickMessage -> {
            put("t", TypeTick)
            put("type", TypeTick)
            put("g", message.tick.contentGeneration)
            put("q", message.tick.sequence)
            put("s", message.tick.status.name)
            put("pos", message.tick.positionMs)
            put("at", message.tick.capturedAtPartyMs)
            put("spd", message.tick.playbackSpeed)
            put("dur", message.tick.durationMs)
            // Only while a hold is on, so an ordinary tick is byte-for-byte what older builds send.
            if (message.tick.hold.isNotEmpty()) {
                put("hold", JsonArray(message.tick.hold.map(::JsonPrimitive)))
            }
            // Same rule: absent unless somebody is away, so an ordinary tick is unchanged on the
            // wire and an older receiver decodes exactly what it always did.
            if (message.tick.away.isNotEmpty()) {
                put("away", JsonArray(message.tick.away.map(::JsonPrimitive)))
            }
        }
        is PartyCommandMessage -> {
            put("t", TypeCommand)
            put("type", message.command.kind.name)
            put("id", message.command.commandId)
            put("k", message.command.kind.name)
            put("n", message.command.counter)
            put("g", message.command.contentGeneration)
            put("pos", message.command.startPositionMs)
            put("at", message.command.startAtPartyMs)
            put("spd", message.command.playbackSpeed)
            put("run", message.command.playAfter)
        }
        is PartyClockPingMessage -> {
            put("t", TypeClockPing)
            put("type", TypeClockPing)
            put("id", message.exchangeId)
            put("t0", message.sentAtMs)
        }
        is PartyClockPongMessage -> {
            put("t", TypeClockPong)
            put("type", TypeClockPong)
            put("id", message.exchangeId)
            put("to", message.toProfileId)
            put("t0", message.sentAtMs)
            put("t1", message.hostAtMs)
        }
        is PartyPeerStatusMessage -> {
            put("t", TypePeerStatus)
            put("type", TypePeerStatus)
            put("s", message.status.name)
            put("at", message.atPartyMs)
            put("rtt", message.rttMs)
            put("st", message.starved)
            put("aw", message.away)
        }
    }
}

/**
 * Null for anything this build cannot act on: a newer protocol, an unknown type, a field that is
 * missing because the sender is older. Every one of those is a reason to fall back to the database
 * anchor, never a reason to guess.
 */
fun decodePartySyncMessage(payload: JsonObject): PartySyncMessage? {
    fun str(key: String) = payload[key]?.jsonPrimitive?.contentOrNull
    fun long(key: String) = payload[key]?.jsonPrimitive?.longOrNull
    fun int(key: String) = payload[key]?.jsonPrimitive?.intOrNull
    fun float(key: String) = payload[key]?.jsonPrimitive?.floatOrNull
    fun bool(key: String) = payload[key]?.jsonPrimitive?.booleanOrNull

    val version = int("v") ?: return null
    if (version > WatchPartySyncProtocolVersion) return null
    val partyId = str("p") ?: return null
    val from = str("f") ?: return null
    val contentGeneration = int("content_generation") ?: return null
    val sourceGeneration = int("source_generation") ?: return null
    val authorityEpoch = long("authority_epoch") ?: return null

    return when (str("t")) {
        TypeTick -> PartyTickMessage(
            fromProfileId = from,
            tick = PartyTick(
                partyId = partyId,
                contentGeneration = int("g") ?: return null,
                sequence = long("q") ?: return null,
                status = str("s")?.let { name -> runCatching { WatchPartyStatus.valueOf(name) }.getOrNull() }
                    ?: return null,
                positionMs = long("pos") ?: return null,
                capturedAtPartyMs = long("at") ?: return null,
                playbackSpeed = float("spd") ?: return null,
                durationMs = long("dur") ?: 0L,
                // Absent from older builds and from every tick outside a hold. A malformed value is
                // treated as no hold rather than dropping the tick: the position is still good.
                hold = (payload["hold"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    .orEmpty(),
                // Absent from older builds and from every tick with nobody away. Malformed is read
                // as nobody away rather than dropping the tick, exactly as `hold` is: the position
                // in this tick is still the best one anybody has.
                away = (payload["away"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    .orEmpty(),
                sourceGeneration = sourceGeneration,
                authorityEpoch = authorityEpoch,
            ),
        )
        TypeCommand -> PartyCommandMessage(
            partyId = partyId,
            command = PartyCommand(
                commandId = str("id") ?: return null,
                kind = str("k")?.let { name -> runCatching { PartyCommandKind.valueOf(name) }.getOrNull() }
                    ?: return null,
                issuedByProfileId = from,
                counter = long("n") ?: return null,
                contentGeneration = int("g") ?: return null,
                startPositionMs = long("pos") ?: return null,
                startAtPartyMs = long("at") ?: return null,
                playbackSpeed = float("spd") ?: 1f,
                // Absent from a build that predates the field, and there it always resumed.
                playAfter = bool("run") ?: true,
                sourceGeneration = sourceGeneration,
                authorityEpoch = authorityEpoch,
            ),
        )
        TypeClockPing -> PartyClockPingMessage(
            partyId = partyId,
            fromProfileId = from,
            exchangeId = str("id") ?: return null,
            sentAtMs = long("t0") ?: return null,
            contentGeneration = contentGeneration,
            sourceGeneration = sourceGeneration,
            authorityEpoch = authorityEpoch,
        )
        TypeClockPong -> PartyClockPongMessage(
            partyId = partyId,
            fromProfileId = from,
            toProfileId = str("to") ?: return null,
            exchangeId = str("id") ?: return null,
            sentAtMs = long("t0") ?: return null,
            hostAtMs = long("t1") ?: return null,
            contentGeneration = contentGeneration,
            sourceGeneration = sourceGeneration,
            authorityEpoch = authorityEpoch,
        )
        TypePeerStatus -> PartyPeerStatusMessage(
            partyId = partyId,
            fromProfileId = from,
            status = str("s")?.let { name -> runCatching { WatchPartyStatus.valueOf(name) }.getOrNull() }
                ?: return null,
            atPartyMs = long("at") ?: return null,
            rttMs = long("rtt") ?: -1L,
            // Absent from every build before 2026-09-19, and false is what those builds mean:
            // "no starvation fact available", which is how the host read a bare `paused` then.
            starved = bool("st") ?: false,
            // Absent from every build before Away existed, and false is what those builds mean:
            // a member that cannot report being away is a member that is watching as far as any
            // decision here is concerned.
            away = bool("aw") ?: false,
            contentGeneration = contentGeneration,
            sourceGeneration = sourceGeneration,
            authorityEpoch = authorityEpoch,
        )
        else -> null
    }
}
