package com.nuvio.app.features.social

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * What can pop up, on every platform: the desktop's corner window and the phone's in-app banner draw the
 * same [SocialPopup]s, chosen by the same rules (Social V2, 2026-10-16; shared 2026-10-17).
 *
 * Friend requests, party invites, requests to join your playback, and "a friend started watching" for
 * friends you asked to be told about (an inbox event the backend only writes for them). Each pops **once**
 * per profile on a device ([SocialPopupLedger]); where and for how long is the platform's business.
 */
internal enum class SocialPopupKind { FriendWatching, FriendRequest, PartyInvite, JoinRequest }

/** One pop-up: who, what happened, the title it is about, and where a tap takes you. */
internal data class SocialPopup(
    /** Stable per event, so a refresh that re-delivers it never pops it twice. */
    val key: String,
    val kind: SocialPopupKind,
    val person: SocialProfileSummary,
    val line: String,
    val title: String,
    val meta: String?,
    val art: SocialArtRef,
    val target: SocialNavigationTarget,
)

/**
 * Everything in [state] that could pop, each with when it happened (epoch ms, 0 when unreadable): pending
 * actionable notices, and unread friend-started-watching events from friends with Notify on.
 */
internal fun socialPopupCandidates(state: SocialUiState): List<Pair<SocialPopup, Long>> {
    val popups = mutableListOf<Pair<SocialPopup, Long>>()
    state.notifications
        .filter { it.readAt == null && it.state == "pending" && it.availableActions.isNotEmpty() }
        .forEach { notice -> popups += notice.toSocialPopup() to (parseSocialTimestampMs(notice.createdAt) ?: 0L) }
    state.inbox
        .filter { it.kind == SocialInboxEvent.FriendWatching && it.readAt == null }
        .filter { event -> state.prefsFor(event.actor.profileId).notifyWhenWatching }
        .forEach { event -> popups += event.toSocialPopup(state.watchingNow) to (parseSocialTimestampMs(event.createdAt) ?: 0L) }
    return popups
}

/** A notice as a pop-up. Shared by the pop-ups, the phone's banners and its in-player card. */
internal fun SocialNotification.toSocialPopup(): SocialPopup {
    val notice = this
    val content = notice.contentSummary
    return when (notice.kind) {
        SocialNotificationKind.FriendRequest -> SocialPopup(
            key = "notice:${notice.id}",
            kind = SocialPopupKind.FriendRequest,
            person = notice.actor,
            line = "wants to be friends",
            title = "@${notice.actor.handle}",
            meta = null,
            art = SocialArtRef(title = notice.actor.handle, contentType = null, contentId = null, poster = null),
            target = SocialNavigationTarget.FriendRequests,
        )
        SocialNotificationKind.PartyInvitation -> SocialPopup(
            key = "notice:${notice.id}",
            kind = SocialPopupKind.PartyInvite,
            person = notice.actor,
            line = "invited you to watch",
            title = content?.title ?: "a Watch Together party",
            meta = listOfNotNull("Watch party", popupEpisodeLabel(content?.season, content?.episode)).joinToString(" · "),
            art = content?.artRef() ?: SocialArtRef("party", null, null, null),
            target = SocialNavigationTarget.Inbox,
        )
        SocialNotificationKind.WatchingNowJoinRequest -> SocialPopup(
            key = "notice:${notice.id}",
            kind = SocialPopupKind.JoinRequest,
            person = notice.actor,
            line = "asked to join",
            title = content?.title ?: "your playback",
            meta = listOfNotNull("Your playback", popupEpisodeLabel(content?.season, content?.episode)).joinToString(" · "),
            art = content?.artRef() ?: SocialArtRef("join", null, null, null),
            target = SocialNavigationTarget.Inbox,
        )
    }
}

/** A friend-started-watching event as a pop-up; [watchingNow] says where a tap goes while they still are. */
internal fun SocialInboxEvent.toSocialPopup(watchingNow: List<WatchingNowItem>): SocialPopup {
    val event = this
    val payload = event.payload
    val live = watchingNow.firstOrNull { it.profile.profileId == event.actor.profileId }
    return SocialPopup(
        key = "inbox:${event.id}",
        kind = SocialPopupKind.FriendWatching,
        person = event.actor,
        line = "is watching",
        title = payload.title ?: live?.title ?: "something",
        meta = popupEpisodeLabel(payload.season, payload.episode),
        art = SocialArtRef(
            title = payload.title.orEmpty(),
            contentType = payload.contentType,
            contentId = payload.contentId,
            poster = payload.poster,
            landscape = listOf(payload.background),
            videoId = payload.videoId,
            season = payload.season,
            episode = payload.episode,
        ),
        target = live?.let { SocialNavigationTarget.WatchingSession(it.socialNavigationKey()) }
            ?: SocialNavigationTarget.Inbox,
    )
}

private fun popupEpisodeLabel(season: Int?, episode: Int?): String? =
    if (season != null && episode != null) "S$season E$episode" else null

/**
 * Which candidates have never popped on this device, so each pops once.
 *
 * The keys of everything ever popped - or present when a profile was first seen here - are kept per
 * profile through [load] / [save], so a restart, a refresh or a reconnect never repeats one. A loading or
 * offline-cache state is a replay of what the device already had: baselining or popping from it would
 * swallow fresh items or pop stale ones, so it is ignored. Something older than [freshnessMs] is marked
 * seen without popping; it is history, and the Inbox holds it.
 *
 * Not thread-safe: one collector feeds it.
 */
internal class SocialPopupLedger(
    private val load: (profileId: String) -> String?,
    private val save: (profileId: String, keys: String) -> Unit,
    private val freshnessMs: Long,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var profileId: String? = null
    private val seen = LinkedHashSet<String>()

    /** The pop-ups in [state] to show now, oldest first; each is marked seen. */
    fun take(state: SocialUiState, nowMs: Long): List<SocialPopup> {
        val profile = state.activeProfileId ?: return emptyList()
        if (state.isLoading || state.isOfflineCache) return emptyList()
        val candidates = socialPopupCandidates(state)
        if (profileId != profile) {
            profileId = profile
            seen.clear()
            val stored = load(profile)
            if (stored == null) {
                // First time this profile is seen on this device: everything already here is history.
                seen += candidates.map { it.first.key }
                persist(profile)
                return emptyList()
            }
            runCatching { json.decodeFromString<List<String>>(stored) }.getOrNull()?.let(seen::addAll)
        }
        val fresh = candidates.filter { it.first.key !in seen }
        if (fresh.isEmpty()) return emptyList()
        fresh.forEach { seen += it.first.key }
        persist(profile)
        return fresh.filter { it.second >= nowMs - freshnessMs }.sortedBy { it.second }.map { it.first }
    }

    /**
     * [key] was already shown on this device another way (an Android system notification), so it never
     * pops. A profile this device has never seen is left alone: its first sight baselines everything.
     */
    fun markSeen(profile: String, key: String) {
        if (profileId == profile) {
            if (seen.add(key)) persist(profile)
            return
        }
        val stored = load(profile)?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() } ?: return
        if (key in stored) return
        save(profile, json.encodeToString((stored + key).takeLast(MaxRemembered)))
    }

    private fun persist(profile: String) {
        save(profile, json.encodeToString(seen.toList().takeLast(MaxRemembered)))
    }

    private companion object {
        const val MaxRemembered = 400
    }
}
