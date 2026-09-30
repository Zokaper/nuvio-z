package com.nuvio.app.features.watchparty

import io.github.jan.supabase.realtime.RealtimeChannel

/** Each plane is necessary. Traffic on the surviving plane cannot repair a lost subscription. */
internal fun partyPlaneHealth(a: RealtimeChannel.Status, p: RealtimeChannel.Status): PartyRealtimeHealth = when {
    a == RealtimeChannel.Status.SUBSCRIBED && p == RealtimeChannel.Status.SUBSCRIBED ->
        PartyRealtimeHealth.SubscribedUnverified
    a == RealtimeChannel.Status.UNSUBSCRIBED || p == RealtimeChannel.Status.UNSUBSCRIBED ||
        a == RealtimeChannel.Status.UNSUBSCRIBING || p == RealtimeChannel.Status.UNSUBSCRIBING ->
        PartyRealtimeHealth.Degraded
    else -> PartyRealtimeHealth.Connecting
}

internal fun partyPlaneHealthEvent(a: RealtimeChannel.Status, p: RealtimeChannel.Status, instance: Long): PartyHealthEvent =
    when (partyPlaneHealth(a, p)) {
        PartyRealtimeHealth.SubscribedUnverified -> PartyHealthEvent.RealtimeSubscribed(instance)
        PartyRealtimeHealth.Degraded -> PartyHealthEvent.RealtimeDegraded(instance)
        else -> PartyHealthEvent.RealtimeConnecting(instance)
    }

internal fun partyLiveReceiveEvent(
    a: RealtimeChannel.Status?, p: RealtimeChannel.Status?, socketConnected: Boolean,
    instance: Long, atMs: Long, kind: PartyRealtimeTrafficKind,
): PartyHealthEvent = if (a == RealtimeChannel.Status.SUBSCRIBED && p == RealtimeChannel.Status.SUBSCRIBED && socketConnected) {
    PartyHealthEvent.RealtimeReceived(instance, atMs, kind)
} else PartyHealthEvent.RealtimeDegraded(instance)
