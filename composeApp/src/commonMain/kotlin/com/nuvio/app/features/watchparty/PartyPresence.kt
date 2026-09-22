package com.nuvio.app.features.watchparty

/**
 * Away: still in the party, deliberately not watching right now.
 *
 * Every platform lifecycle event this feature reacts to used to arrive at the player as nothing at
 * all, or as one of the three things it is not. Backgrounding a mobile client paused its engine
 * through `PlayerEngine.android.kt`'s `ON_STOP` observer, which the party could only observe as a
 * member that had stopped reporting `playing` - so the host's stall guard read a person pressing
 * Home as a stream that had stalled, held the party for them, and gave up on them
 * [WatchPartyStallHoldMaxMs] later. Returning was worse: `ON_START` restores `playWhenReady`, so the
 * member resumed at the position it had been frozen at and the drift tracker dragged it forward
 * through a seek - minutes of it, if the phone had been in a pocket.
 *
 * None of that is a bug in the stall guard. It is a fact the party was never told, and this file is
 * the fact: a member is **Watching** or **Away**, Away is a first-class member state, and it is not
 * disconnected, not buffering, not leaving, not a source failure and not evidence about the host.
 *
 * Import-free on purpose, like `WatchPartyBarrier` and `PartyPlaybackStatus` beside it, so
 * `scripts/run-pure-suites.sh` executes these decisions rather than parser-checking them. Android's
 * lifecycle callbacks report [PartyLifecycleFacts] into here; they decide nothing themselves.
 */

/** Watching, or deliberately not. Nothing about connectivity, readiness or membership. */
enum class PartyPresence { Watching, Away }

/**
 * Why a member is Away, for the log line and for wording that can tell a locked phone from a call.
 *
 * Never sent over the wire: the other members are told *that* somebody is away, which is all any of
 * them can act on. Keeping the reason local is also what stops it becoming a second, subtly
 * different presence enum that has to be kept compatible across two release trains.
 */
enum class PartyAwayReason(val logCode: String) {
    None("none"),

    /** The app is not in the foreground, and no picture-in-picture window is playing for it. */
    Background("background"),

    /** The screen is locked. Distinct from [Background] only in the log. */
    ScreenLocked("lock"),

    /** A call or comparable interruption; playback cannot reasonably continue. */
    Interrupted("interrupt"),

    /**
     * A picture-in-picture window was dismissed into an app that is still in the background.
     *
     * Its own reason because the ordering is the thing most likely to be wrong: Android delivers
     * the mode change before it is settled whether the activity is coming back, and calling that
     * [Background] would make the two indistinguishable in a log of a run that flapped.
     */
    PictureInPictureDismissed("pip-dismissed"),
}

/**
 * What the platform said, as facts rather than as policy.
 *
 * ⚠ **A snapshot, not an event.** Entering picture-in-picture delivers `onPause`, a configuration
 * change, `onPictureInPictureModeChanged` and - on some devices - `ON_STOP`, in an order that is
 * not guaranteed and is not the same across API levels. A state machine fed those as *events*
 * flaps Watching→Away→Watching for a member whose video never stopped playing, which is edge case
 * 21 of the Phase 6 brief. Fed the whole truth at once, the decision below cannot: it reads
 * [pictureInPicture] and [playbackContinues] together and answers Watching.
 *
 * [seq] is a monotonic counter the adapter bumps for every observation it makes. A callback that
 * arrives late - a posted `ON_STOP` landing after the foreground it was overtaken by - carries an
 * older [seq] and is refused by [PartyPresenceState.observe] rather than overwriting newer truth.
 */
data class PartyLifecycleFacts(
    val appForeground: Boolean,
    /** A picture-in-picture window exists for this app. */
    val pictureInPicture: Boolean = false,
    /**
     * The video is actually still running where the viewer can see it.
     *
     * Only ever consulted together with [pictureInPicture]: a PiP window the system has frozen, or
     * one the user dismissed a frame ago, is not somebody watching.
     */
    val playbackContinues: Boolean = false,
    val screenLocked: Boolean = false,
    /** A phone call or comparable interruption. */
    val interrupted: Boolean = false,
    val seq: Long = 0L,
) {
    /** Watching in a picture-in-picture window, which is watching. */
    val watchingInPictureInPicture: Boolean get() = pictureInPicture && playbackContinues
}

/**
 * The three broadcasts a platform can tell this model about the screen and the lock.
 *
 * Named for what they mean rather than for the Android constants that carry them, so the decision
 * they feed stays in this file with every other one.
 */
enum class PartyScreenSignal {
    /** The screen went off. */
    ScreenOff,

    /** The screen came on. Says nothing about the lock: a lock screen is a lit screen. */
    ScreenOn,

    /** Somebody got past the lock screen. */
    UserPresent,
}

/**
 * Whether a lock screen is between the viewer and the video, after [signal].
 *
 * [keyguardLocked] is what the platform says the lock is doing *at that instant*, and the whole
 * point of this function is that it is only asked when it can be believed.
 *
 * - [PartyScreenSignal.ScreenOff] needs no lock at all. A dark screen is away whatever the
 *   keyguard thinks, and on a device with no lock set it is the only signal there will be.
 * - [PartyScreenSignal.ScreenOn] is the one honest question: the screen is lit and the lock may or
 *   may not be in front of it. A device with no lock set answers false here and is done.
 * - [PartyScreenSignal.UserPresent] **is the answer, and asking again gets a worse one.** It is
 *   broadcast for exactly one reason - somebody unlocked - so reading the keyguard back at that
 *   instant replaces a certainty with a race. On the S25 that read returns `true` while the
 *   keyguard is still playing its going-away animation; nothing else was coming to correct it, so
 *   the member stayed Away for the rest of the session with the film in front of them. Reproduced
 *   on hardware 2026-09-20.
 */
fun partyScreenLockedAfter(signal: PartyScreenSignal, keyguardLocked: Boolean): Boolean = when (signal) {
    PartyScreenSignal.ScreenOff -> true
    PartyScreenSignal.ScreenOn -> keyguardLocked
    PartyScreenSignal.UserPresent -> false
}

/**
 * Whether a lock screen is still in front of the viewer once the app reaches the foreground.
 *
 * Two different qualities of evidence, which is why [resumed] is a parameter and not a caller's
 * business:
 *
 * - **Resumed is proof.** An activity cannot be RESUMED behind the keyguard - this app sets no
 *   `showWhenLocked` anywhere, and a picture-in-picture window is paused rather than resumed - so
 *   being resumed *is* the lock being gone. No keyguard read, no broadcast, nothing to race. It is
 *   the same kind of fact as `ACTION_USER_PRESENT` in [partyScreenLockedAfter], and it is the one
 *   signal that arrives *after* the dismiss animation rather than during it.
 * - **Started is only a second chance**, and a poor one. It re-reads the keyguard to recover a lock
 *   fact that went missing, so it may *clear* a lock that is no longer there but may never
 *   *declare* one - one direction only, exactly as [partyStaleAwayNeedsClearing] is.
 *
 * **Why the started path alone was not enough.** Captured on an S25 on 2026-09-21 with
 * `0.4.13-z1.39` installed: `presence Watching -> Away reason=lock` at the lock, then no transition
 * of any kind for the next eight minutes while the phone sat unlocked with the film in front of it.
 * The receiver was registered for all three actions the whole time - `dumpsys activity broadcasts`
 * confirmed it on the live pid - so nothing had torn down. `ACTION_USER_PRESENT` simply never
 * arrived: the process is cached while the screen is locked, and broadcasts to a cached process are
 * dropped. That is exactly the case the started re-read was added for, and the re-read missed it
 * too, because the only `ON_START` of an unlock-and-return fires *during* the keyguard dismiss
 * animation, where `isKeyguardLocked` still answers `true`. Foregrounding the app again minutes
 * later - a second `ON_START`, with the keyguard long settled - cleared it instantly
 * (`presence Away -> Watching reason=foreground`), which is the whole bug in one line: it cleared
 * only by accident of timing, and an unlock-and-return never supplies that accident.
 *
 * A genuine lock arrives as [PartyScreenSignal.ScreenOff] or [PartyScreenSignal.ScreenOn] with the
 * keyguard up, and neither goes through here.
 */
fun partyScreenLockedOnForeground(
    heldScreenLocked: Boolean,
    keyguardLocked: Boolean,
    resumed: Boolean,
): Boolean = if (resumed) false else heldScreenLocked && keyguardLocked

/**
 * Whether a member that is plainly here still has an away on the wire, and must withdraw it.
 *
 * The transport's own copy of this member's presence deliberately outlives the channel and the
 * generation that set it - a reconnect does not put the phone back into a hand - so the only
 * things that clear it are a return and leaving the party. Neither is a transition a *new* party
 * can produce: a fresh player composes as Watching, and a presence that never changes never
 * reports. A member that went away, left and joined again therefore published `away=true` on
 * every peer status of the new party for as long as the app ran. Observed on hardware 2026-09-20.
 *
 * **Only this direction.** Declaring Away from a reconciliation would report an absence with none
 * of the bookkeeping a return depends on - the generation key captured at away-time, the retained
 * playback intent, a host's pause - and every real absence already arrives as a transition.
 */
fun partyStaleAwayNeedsClearing(held: PartyPresenceState, transportReportsAway: Boolean): Boolean =
    transportReportsAway && held.presence == PartyPresence.Watching

/**
 * The presence these facts mean, with no memory of what came before.
 *
 * Precedence, highest first:
 *
 *  1. **Interrupted.** A call stops playback whatever window it was in, PiP included.
 *  2. **Playing in PiP is Watching**, even though the app is not in the foreground and the screen
 *     may well report itself locked on the way in. This is the one rule the brief calls out by
 *     name, and it has to outrank both of the rules below it or it does not exist.
 *  3. **Screen locked**, then **not foreground**. Both are Away; they differ only in the log.
 *
 * [PartyAwayReason.PictureInPictureDismissed] is not produced here, because "was in PiP a moment
 * ago" is not a fact about now. [PartyPresenceState.observe] adds it.
 */
fun partyPresenceFor(facts: PartyLifecycleFacts): PartyPresence = when {
    facts.interrupted -> PartyPresence.Away
    facts.watchingInPictureInPicture -> PartyPresence.Watching
    facts.screenLocked -> PartyPresence.Away
    !facts.appForeground -> PartyPresence.Away
    else -> PartyPresence.Watching
}

private fun partyAwayReasonFor(facts: PartyLifecycleFacts, wasInPictureInPicture: Boolean): PartyAwayReason = when {
    facts.interrupted -> PartyAwayReason.Interrupted
    facts.screenLocked -> PartyAwayReason.ScreenLocked
    // The dismissal is only interesting while the app stays in the background. A PiP window closed
    // by tapping it expands the app, and that is a return, not an away.
    wasInPictureInPicture && !facts.pictureInPicture && !facts.appForeground ->
        PartyAwayReason.PictureInPictureDismissed
    else -> PartyAwayReason.Background
}

/**
 * The local member's presence, and the facts it was decided from.
 *
 * Carried as state rather than recomputed per observation for two reasons: the dismissal reason
 * needs the previous window, and [observe] has to be able to refuse an observation older than the
 * one it already holds.
 */
data class PartyPresenceState(
    val presence: PartyPresence = PartyPresence.Watching,
    val reason: PartyAwayReason = PartyAwayReason.None,
    val facts: PartyLifecycleFacts = PartyLifecycleFacts(appForeground = true),
) {
    val isAway: Boolean get() = presence == PartyPresence.Away

    /**
     * Folds one observation in, or refuses it.
     *
     * A stale observation - one whose [PartyLifecycleFacts.seq] is older than the held one - is
     * dropped whole. Not merged, not partially applied: a late callback describes a moment that has
     * already been superseded, and there is nothing in it worth half-believing.
     */
    fun observe(next: PartyLifecycleFacts): PartyPresenceState {
        if (next.seq < facts.seq) return this
        val presence = partyPresenceFor(next)
        return PartyPresenceState(
            presence = presence,
            reason = if (presence == PartyPresence.Away) {
                partyAwayReasonFor(next, wasInPictureInPicture = facts.pictureInPicture)
            } else {
                PartyAwayReason.None
            },
            facts = next,
        )
    }
}

/**
 * The one line a transition is logged as. `presence Watching -> Away reason=background`.
 *
 * Only ever called on a change, so a member sitting in a pocket for an hour writes one line and not
 * one per lifecycle callback. Staying Watching *through* a PiP transition is worth a line of its
 * own - it is the case most likely to be wrong on a device nobody has tested - hence the third form.
 */
fun partyPresenceTransitionLog(
    before: PartyPresenceState,
    after: PartyPresenceState,
): String? {
    if (before.presence == after.presence && before.reason == after.reason) {
        // Watching on both sides of a PiP change: the exception doing its job, said once.
        val pipChanged = before.facts.pictureInPicture != after.facts.pictureInPicture
        return if (pipChanged && after.presence == PartyPresence.Watching) {
            "presence Watching -> Watching reason=pip"
        } else {
            null
        }
    }
    val reason = if (after.presence == PartyPresence.Away) after.reason.logCode else "foreground"
    return "presence ${before.presence} -> ${after.presence} reason=$reason"
}

/**
 * What a member coming back has to do before it can watch again.
 *
 * Returning must never be a fresh source resolution. The realization a member left behind is still
 * good unless the party moved to different content or a different source while it was away, or the
 * local engine genuinely lost what it had - and "the app was in the background" is none of those.
 * Being suspicious here is the whole of the brief's source-preservation requirement: a return that
 * re-enters the realizer costs the member the source list, the match and the open stream, for a
 * party that is still playing exactly the file it already had.
 */
enum class PartyReturnAction {
    /**
     * Catch up to the party timeline and resume according to it. The ordinary path.
     *
     * Deliberately not "resume where I was": a member away for four minutes holds a position four
     * minutes stale, and playing it even for the half second before the next tick arrives is the
     * member watching the wrong frame - and, if the party is paused, the wrong frame indefinitely.
     */
    CatchUpToTimeline,

    /** The party's content or source moved while this member was away. The realizer owns it. */
    RealizeCurrentGeneration,
}

/**
 * Whether a return is a catch-up or a realization.
 *
 * [awayAtGenerationKey] is `WatchPartyState.generationKey()` as it stood when the member went away;
 * null means it was not recorded, which is treated as a change rather than assumed to be the same.
 */
fun partyReturnAction(
    awayAtGenerationKey: String?,
    currentGenerationKey: String?,
    localSourceUsable: Boolean,
): PartyReturnAction = when {
    !localSourceUsable -> PartyReturnAction.RealizeCurrentGeneration
    awayAtGenerationKey == null || currentGenerationKey == null -> PartyReturnAction.RealizeCurrentGeneration
    awayAtGenerationKey != currentGenerationKey -> PartyReturnAction.RealizeCurrentGeneration
    else -> PartyReturnAction.CatchUpToTimeline
}

/**
 * Who the host holds the party for because they are away, given its preference.
 *
 * **Separate from "Pause when someone buffers", and it has to be.** They answer different
 * questions: one is about a stream that cannot keep up, the other about a person who is not there.
 * A host who wants to wait out a bad connection does not necessarily want the film to stop because
 * somebody looked at a message, and the reverse is just as reasonable.
 *
 * Off - the default - the party plays on and whoever comes back catches up through
 * [PartyReturnAction.CatchUpToTimeline], which is the least disruptive of the two behaviours.
 *
 * ⚠ **This is about the *other* members, never the caller.** A member going away pauses its own
 * player directly, and on a host that pause is a party pause: the host is the clock, so there is no
 * arrangement in which the rest of the party carries on without it, whatever the preference says.
 * Counting the caller here would be that same stop counted twice - and on the host it would be a
 * hold waiting for a member who is sitting right there holding it.
 *
 * Members who cannot be waited for are never waited for, exactly as [partyMembersAwaitingSource]
 * refuses to: someone disconnected, left or failed is not coming back from being away.
 */
fun partyAwayHoldMembers(
    party: WatchPartyState?,
    awayProfileIds: Set<String>,
    viewerProfileId: String?,
    pauseForAwayUsers: Boolean,
): List<String> {
    if (!pauseForAwayUsers) return emptyList()
    if (party == null || party.status == WatchPartyStatus.ended) return emptyList()
    if (awayProfileIds.isEmpty()) return emptyList()
    return party.members.filter { member ->
        if (member.profileId !in awayProfileIds) return@filter false
        if (member.profileId == viewerProfileId) return@filter false
        if (!member.connected) return@filter false
        if (member.readyState == SourceResolutionState.left ||
            member.readyState == SourceResolutionState.failed ||
            member.readyState == SourceResolutionState.disconnected
        ) return@filter false
        true
    }.map { it.profileId }.sorted()
}

/**
 * How long a returning member waits for a live timeline before giving up on catching up from one.
 *
 * The host publishes twice a second, so this is generous by an order of magnitude and is only ever
 * reached when there is no live plane at all - a socket that has not come back yet, or a host on a
 * build that publishes no ticks. Giving up is not a failure: the durable anchor is still running and
 * is what that member has always been corrected by, so the catch-up simply hands over to it rather
 * than holding a player still waiting for a message that is not coming.
 */
const val PartyAwayCatchUpTickWaitMs = 5_000L

/**
 * How a member reads on a member list, as one enum instead of a generic "loading".
 *
 * The brief asks for five statuses that are not interchangeable, and the reason they must not
 * collapse is that each one tells the viewer something different about whether to wait: a member
 * who is Buffering will be back in a second, one who is Away will be back when they are back, one
 * who is Reconnecting may be about to be Offline, and one who is Offline cannot be waited for at
 * all.
 */
enum class PartyMemberActivity { Watching, Away, Buffering, Reconnecting, Offline }

/**
 * Away against transport health, which is the one place the two orthogonal facts have to be ranked.
 *
 * **Transport wins.** A member who backgrounded the app and then lost the network is not merely
 * away - nothing this client shows about them is current, and presenting them as Away implies a
 * liveness the socket is not providing. Once the transport is back the away fact is current again
 * and takes over, which is edge cases 14-16 of the brief in one expression.
 */
fun partyMemberActivity(
    connected: Boolean,
    reconnecting: Boolean,
    away: Boolean,
    buffering: Boolean,
): PartyMemberActivity = when {
    !connected -> PartyMemberActivity.Offline
    reconnecting -> PartyMemberActivity.Reconnecting
    away -> PartyMemberActivity.Away
    buffering -> PartyMemberActivity.Buffering
    else -> PartyMemberActivity.Watching
}

/** "Waiting for Riyad to return…" - what a party held for away members says. */
fun partyAwayHoldHeadline(names: List<String>): String = when (names.size) {
    0 -> "Waiting for someone to return…"
    1 -> "Waiting for ${names[0]} to return…"
    2 -> "Waiting for ${names[0]} and ${names[1]} to return…"
    else -> "Waiting for ${names[0]} and ${names.size - 1} others to return…"
}
