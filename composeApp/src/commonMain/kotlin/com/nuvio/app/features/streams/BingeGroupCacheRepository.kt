package com.nuvio.app.features.streams

import com.nuvio.app.features.playback.StickySourcePin
import kotlinx.serialization.json.Json

object BingeGroupCacheRepository {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The quality band the user chose in Streamlined's sheet, for this sitting only.
     *
     * The complaint it answers: two taps that look identical to the user - same show, next
     * episode - landed on different resolutions, because the next episode was picked by
     * bandwidth estimate while the first was picked by hand, and the estimate ratchets upward
     * as you watch. Someone who deliberately chose "1080p Low" got whatever the line could
     * carry from episode two onward.
     *
     * A **resolution height, not a release**. Deliberately weaker than the sticky pin this
     * replaced: it is a tie-break towards stability applied by
     * `PlaybackQualityOptions.stickyAffordable`, never a ceiling and never a floor, so it can
     * never make the sheet stop appearing or hold a quality the connection cannot carry.
     *
     * Session-scoped and keyed by `parentMetaId`, because the churn is episode-to-episode
     * within one show and within one sitting. A stored value would silently outlive the
     * decision that produced it. That is a different key space from the binge-group cache
     * below, which is keyed the same way but is genuinely a long-lived preference and keeps
     * its storage untouched.
     */
    private val sessionQualityHeights = mutableMapOf<String, Int>()

    /**
     * The same choice, identified exactly: a `PlaybackQualityOption.id`.
     *
     * Two readers with two different jobs, which is why both are stored.
     * [sessionQualityHeight] serves the in-player next episode, where the band is a *tie-break*
     * - `stickyAffordable` falls back to the affordable best when the height is unavailable,
     * because nobody is watching to answer a sheet mid-binge and any reasonable source beats
     * stopping.
     *
     * This one serves `entry<StreamRoute>`, where the band is a *decision to skip a question*.
     * There it has to be exact and it has to be able to say "no": the id carries the variant as
     * well as the resolution (`PlaybackQualityOption.id` is built from both, and is stable
     * across a refetch for this reason), and a miss means the sheet appears rather than the app
     * quietly playing a band the user never chose. Falling back the way `stickyAffordable` does
     * would be silent substitution on a path the user cannot see - the sheet is *skipped*, so
     * there is nothing on screen to disagree with.
     *
     * Session-scoped for the same reason as the height above.
     */
    private val sessionQualityBandIds = mutableMapOf<String, String>()

    fun saveSessionQualityHeight(parentMetaId: String, height: Int) {
        if (parentMetaId.isBlank() || height <= 0) return
        sessionQualityHeights[parentMetaId] = height
    }

    /** Records both readings of one choice - see [sessionQualityBandIds]. */
    /**
     * The height is optional because "Best available" has no resolution by construction, and it
     * is the row most people tap. Gating the whole write on a height meant the one choice the
     * copy promises to remember was the one never stored - the id is what lets the route skip
     * the sheet, and it is knowable whether or not a height is.
     */
    fun saveSessionQualityBand(parentMetaId: String, height: Int?, optionId: String) {
        if (height != null) saveSessionQualityHeight(parentMetaId, height)
        if (parentMetaId.isBlank() || optionId.isBlank()) return
        sessionQualityBandIds[parentMetaId] = optionId
    }

    fun sessionQualityHeight(parentMetaId: String): Int? = sessionQualityHeights[parentMetaId]

    fun sessionQualityBandId(parentMetaId: String): String? = sessionQualityBandIds[parentMetaId]

    /**
     * Forgets the band for one show, so its next episode asks again.
     *
     * Raised by the "Change" action on the toast that announces a skipped sheet. Pressing it is
     * the user saying the automatic pick was wrong; carrying on skipping the question after
     * that would make the toast's own affordance a one-shot that changes nothing beyond the
     * episode in front of them.
     *
     * Clears the height as well. Leaving it would keep steering the in-player next episode
     * towards a band the user has just rejected - a disagreement between the two readers is
     * exactly what storing one choice twice has to avoid.
     */
    fun clearSessionQualityBand(parentMetaId: String) {
        sessionQualityBandIds.remove(parentMetaId)
        sessionQualityHeights.remove(parentMetaId)
    }

    /**
     * The show whose band the next "Change source" should forget, or null.
     *
     * `NuvioToastAction` is a typed enum handled in one place in `App.kt`, deliberately - toasts
     * are raised from screens that hold no navigator, and keeping lambdas out of the component
     * layer is what makes that safe. So the *identity* has to travel some other way, and it
     * travels as data, beside the thing it would clear.
     *
     * Armed when a play announces that it skipped the quality sheet, disarmed by whatever
     * happens next. Without it "Change" opens the source panel for this one episode and the
     * next one skips the sheet again - an affordance that appears to undo the behaviour and
     * only undoes one instance of it.
     */
    private var armedBandChangeId: String? = null

    fun armBandChange(parentMetaId: String) {
        armedBandChangeId = parentMetaId.takeIf { it.isNotBlank() }
    }

    /** Forgets the armed band, if the user asked for a different source. */
    fun consumeArmedBandChange() {
        armedBandChangeId?.let(::clearSessionQualityBand)
        armedBandChangeId = null
    }

    /**
     * Drops the arming without acting on it.
     *
     * For every other way a play can end. A toast that expired unread is not a user saying the
     * pick was wrong, and leaving it armed would make an unrelated "Change source" - pressed
     * three episodes later for a reason of its own - forget a band nobody complained about.
     */
    fun disarmBandChange() {
        armedBandChangeId = null
    }

    /**
     * Drops an arming that belongs to a different show.
     *
     * "Disarmed by whatever happens next" holds only for plays that reach one of the disarm
     * sites; an arming the user simply ignored outlives the show it was raised for. It then
     * belongs to whichever Change is pressed next - including reuse-last-link's, which raises
     * the same typed action for a different reason - and clears a band on a show the user is no
     * longer watching, for a reason they cannot connect to anything they did.
     *
     * Called as each play opens, so an arming survives only while its own show is in front of
     * the user.
     */
    fun disarmBandChangeIfNot(parentMetaId: String) {
        if (armedBandChangeId != null && armedBandChangeId != parentMetaId) {
            armedBandChangeId = null
        }
    }

    fun clearSessionPins() {
        sessionQualityHeights.clear()
        sessionQualityBandIds.clear()
        // Everything session-scoped, including a pending arming. Leaving one behind would let
        // a Change press after a profile switch reach for a show the new session never chose.
        armedBandChangeId = null
    }

    fun save(contentId: String, bingeGroup: String) {
        save(contentId, StickySourcePin(bingeGroup = bingeGroup))
    }

    fun save(contentId: String, pin: StickySourcePin) {
        if (pin.isEmpty) return remove(contentId)
        BingeGroupCacheStorage.save(hashedKey(contentId), json.encodeToString(StickySourcePin.serializer(), pin))
    }

    fun get(contentId: String): StickySourcePin? {
        val stored = BingeGroupCacheStorage.load(hashedKey(contentId)) ?: return null
        return runCatching { json.decodeFromString(StickySourcePin.serializer(), stored) }
            .getOrElse { StickySourcePin(bingeGroup = stored.trim()) }
            .takeUnless(StickySourcePin::isEmpty)
    }

    fun remove(contentId: String) {
        BingeGroupCacheStorage.remove(hashedKey(contentId))
    }

    private fun hashedKey(contentId: String): String {
        val hash = contentId.fold(0L) { acc, c -> acc * 31 + c.code }.toULong()
        return "binge_group_$hash"
    }
}
