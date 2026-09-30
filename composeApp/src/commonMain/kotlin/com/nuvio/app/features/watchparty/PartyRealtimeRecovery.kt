package com.nuvio.app.features.watchparty

import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** SDK owns socket reconnect/rejoin. Retain channels until that bounded recovery fails. */
internal suspend fun monitorPartyRealtimeRecovery(
    authority: StateFlow<RealtimeChannel.Status>,
    peer: StateFlow<RealtimeChannel.Status>,
    socket: StateFlow<Realtime.Status>,
    recoveryTimeoutMs: Long = 30_000L,
    onDegraded: () -> Unit,
    onRecovered: suspend () -> Unit,
) {
    val statuses = combine(authority, peer, socket) { a, p, s ->
        (a == RealtimeChannel.Status.SUBSCRIBED && p == RealtimeChannel.Status.SUBSCRIBED &&
            s == Realtime.Status.CONNECTED) to s
    }
    while (true) {
        statuses.first { !it.first }
        onDegraded()
        while (true) {
            // Offline/suspended time belongs to SDK socket recovery, not channel replacement.
            // Only a connected socket that cannot recover both joins exhausts this deadline.
            socket.first { it == Realtime.Status.CONNECTED }
            val next = withTimeout(recoveryTimeoutMs) {
                statuses.first { it.first || it.second != Realtime.Status.CONNECTED }
            }
            if (next.first) break
        }
        // Subscription alone still does not declare Live: the receive-health gate owns proof.
        onRecovered()
    }
}
