package com.nuvio.app.features.social

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val presenceSessionLog = Logger.withTag("SocialPresence")

data class SocialPresenceSessionState(
    val deviceId: String? = null,
    val sessionId: String? = null,
    val effectivePolicy: WatchJoinPolicy = WatchJoinPolicy.approval,
)

/** Process-scoped handle for the current player's per-session join-policy override. */
object SocialPresenceSession {
    private val _state = MutableStateFlow(SocialPresenceSessionState())
    val state = _state.asStateFlow()

    /**
     * Deliberately **not** the player's composition scope - see [detachAndClear].
     *
     * `internal var` only so the pure suite can drive the clear deterministically; nothing in the
     * app reassigns it.
     */
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal var clearPresence: suspend (String) -> Result<Unit> = { SocialRepository.clearPresence(it) }

    fun attach(deviceId: String, sessionId: String, defaultPolicy: WatchJoinPolicy) {
        val current = _state.value
        _state.value = if (current.deviceId == deviceId && current.sessionId == sessionId) {
            current
        } else {
            SocialPresenceSessionState(deviceId, sessionId, defaultPolicy)
        }
    }

    /** True when this call is the one that gave the session up, rather than a duplicate or a stale one. */
    fun detach(deviceId: String, sessionId: String): Boolean {
        val current = _state.value
        if (current.deviceId != deviceId || current.sessionId != sessionId) return false
        _state.value = SocialPresenceSessionState()
        return true
    }

    /**
     * Give the session up **and** take the row out of `watch_presence`.
     *
     * ⚠ **This is why a friend stayed in Watching Now, reading PAUSED, after the player was gone.**
     * The clear used to be `runtime.scope.launch { SocialRepository.clearPresence(deviceId) }` from
     * the player's `onDispose` - and `runtime.scope` is a `rememberCoroutineScope()`, so it is
     * cancelled by the very departure that triggers the dispose. The launch either never started or
     * was cancelled mid-RPC, the last thing the backend ever heard was the Paused publish from
     * before the exit, and the row then sat there until `SocialPresenceStaleMs` aged it out. The
     * symptom looks exactly like a TTL problem and is not one: **shortening the TTL would have
     * hidden this rather than fixed it.**
     *
     * The clear belongs to the process, not to the composition, so it runs here.
     *
     * Pausing does not come through this path at all - pause is a publish with
     * [SocialPlaybackState.paused] and the session stays attached, which is what keeps a paused
     * friend visible. Only leaving the player detaches. An episode change inside the player keeps
     * the same session too: the effect that owns the dispose is keyed on the device, not on the
     * video, so a handoff republishes rather than clearing.
     */
    fun detachAndClear(deviceId: String, sessionId: String): Job? {
        if (!detach(deviceId, sessionId)) {
            presenceSessionLog.d { "clear skipped - session $sessionId was not the attached one" }
            return null
        }
        return scope.launch {
            // A new player can attach between the dispose and this coroutine running; presence is
            // keyed by device, so clearing here would delete the *new* session's row.
            val attached = _state.value.sessionId
            if (attached != null) {
                presenceSessionLog.d { "clear abandoned - session $attached attached while leaving" }
                return@launch
            }
            clearPresence(deviceId)
                .onSuccess { presenceSessionLog.d { "presence cleared on leaving playback" } }
                .onFailure { presenceSessionLog.w(it) { "presence clear failed - friends will see a stale entry until it ages out" } }
        }
    }

    /**
     * Set this session's join policy to [policy], optimistically.
     *
     * Replaces `cyclePolicy`, which one click moved Direct → Ask → Off with no way to land where you
     * meant and no word when the write failed - a person could make themselves unjoinable without
     * knowing. The state moves at once; a refused write rolls it back and the failure is returned so
     * the panel can say so.
     */
    suspend fun setPolicy(policy: WatchJoinPolicy): Result<WatchJoinPolicy> {
        val current = _state.value
        val deviceId = current.deviceId ?: return Result.failure(IllegalStateException("No active presence"))
        val sessionId = current.sessionId ?: return Result.failure(IllegalStateException("No active presence"))
        if (current.effectivePolicy == policy) return Result.success(policy)
        // Overlapping presses (Ask, then Off before Ask answers): only the latest write may roll back,
        // and it rolls back to what the server last *confirmed*, not to what was shown when it began.
        val write = synchronized(policyLock) {
            if (policyConfirmedFor != sessionId) {
                policyConfirmedFor = sessionId
                confirmedPolicy = current.effectivePolicy
            }
            ++policyWriteSeq
        }
        _state.value = current.copy(effectivePolicy = policy)
        return SocialRepository.setPresenceJoinPolicy(deviceId, sessionId, policy).map { policy }
            .onSuccess {
                synchronized(policyLock) { if (write == policyWriteSeq) confirmedPolicy = policy }
            }
            .onFailure {
                presenceSessionLog.w(it) { "join policy change to $policy refused" }
                val rollbackTo = synchronized(policyLock) { confirmedPolicy.takeIf { write == policyWriteSeq } }
                val now = _state.value
                if (rollbackTo != null && now.deviceId == deviceId && now.sessionId == sessionId) {
                    _state.value = now.copy(effectivePolicy = rollbackTo)
                }
            }
    }

    private val policyLock = SynchronizedObject()
    private var policyWriteSeq = 0L
    private var policyConfirmedFor: String? = null
    private var confirmedPolicy: WatchJoinPolicy = WatchJoinPolicy.approval
}
