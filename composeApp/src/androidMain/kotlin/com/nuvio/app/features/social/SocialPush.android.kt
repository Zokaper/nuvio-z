package com.nuvio.app.features.social

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import co.touchlab.kermit.Logger
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.nuvio.app.MainActivity
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationPlatform
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Android system notifications for Social (Social V2 Stage 4, 2026-10-18).
 *
 * The server pushes FCM *data* messages (`social-push` Edge Function, fed by triggers in
 * `202610180002_social_push.sql`); this side decides whether and how each one shows:
 *
 * - **Registration.** While a Social profile is active and Settings -> Social -> Notifications is on, this
 *   phone's token is registered for that profile. The server moves a token to whoever registered it last,
 *   so a profile switch needs no unregister; turning Social or its notifications off unregisters it.
 * - **Whose push.** The profile the token was last registered for is remembered here. A push for anyone
 *   else (a stale row after signing out, or before the move reached the server) is dropped.
 * - **When.** Never while Nuvio is in front - the in-app banner shows it - and never for a kind switched off
 *   in Notifications ([PhoneSocialAlerts]). What does show is marked seen in the banner ledger, with the
 *   same key, so opening the app does not show it a second time.
 * - **Tap.** Opens Nuvio on the place: friend requests, or the Inbox ([SocialNavigation]).
 *
 * Firebase is configured from `google-services.json` at build time (see `androidApp/build.gradle.kts`); a
 * build without it has no Firebase app, and everything here quietly does nothing.
 */
internal object SocialPush {
    private val log = Logger.withTag("SocialPush")
    private const val PreferencesName = "nuvio_social_push"
    private const val ProfileKey = "registered_profile"
    private const val TokenKey = "registered_token"
    private const val PermissionAskedKey = "permission_asked"
    private const val ChannelId = "social"
    const val TargetExtra = "nuvio.social.target"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var appContext: Context? = null
    private var started = false

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    fun available(context: Context): Boolean = runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)

    /** From `MainActivity.onCreate`, after the Social storages are initialised. */
    fun start(context: Context) {
        appContext = context.applicationContext
        if (started) return
        started = true
        if (!available(context)) {
            log.i { "no Firebase app in this build: Social push is off" }
            return
        }
        scope.launch {
            combine(SocialRepository.uiState, PhoneSocialAlerts.state) { state, alerts ->
                SocialPushInput(
                    activeProfileId = state.activeProfileId,
                    ready = state.capabilities.socialEnabled && SocialFeaturePreferencesRepository.isEnabledNow && state.me != null,
                    alertsOn = alerts.enabled,
                )
            }.distinctUntilChanged().collect(::sync)
        }
    }

    /** A new token from FCM: registered at once if there is a profile to register it for. */
    fun onNewToken(token: String) {
        val context = appContext ?: return
        val profile = prefs(context).getString(ProfileKey, null) ?: return
        scope.launch { register(context, profile, token) }
    }

    /** Whether a profile has been active in this process: a null before that is start-up, not a sign-out. */
    private var seenActive = false

    private suspend fun sync(input: SocialPushInput) {
        val context = appContext ?: return
        val stored = prefs(context)
        if (input.activeProfileId != null) seenActive = true
        val registered = stored.getString(ProfileKey, null)
        when (socialPushStep(input, registered, seenActive)) {
            SocialPushStep.Wait -> Unit
            SocialPushStep.Forget -> stored.edit().remove(ProfileKey).apply()
            SocialPushStep.Unregister -> {
                stored.getString(TokenKey, null)?.let { token ->
                    SocialRepository.unregisterPushToken(token)
                        .onFailure { log.w { "unregister failed: ${it.message}" } }
                }
                stored.edit().remove(ProfileKey).apply()
            }
            SocialPushStep.Register -> {
                val profile = input.activeProfileId ?: return
                val token = currentToken() ?: return
                register(context, profile, token)
                requestPermissionOnce(context)
            }
        }
    }

    private suspend fun register(context: Context, profile: String, token: String) {
        val stored = prefs(context)
        if (stored.getString(ProfileKey, null) == profile && stored.getString(TokenKey, null) == token) return
        SocialRepository.registerPushToken(token)
            .onSuccess {
                stored.edit().putString(ProfileKey, profile).putString(TokenKey, token).apply()
                log.i { "registered for profile ${profile.take(8)}" }
            }
            .onFailure { log.w { "register failed: ${it.message}" } }
    }

    private suspend fun currentToken(): String? = suspendCancellableCoroutine { continuation ->
        runCatching {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                continuation.resume(if (task.isSuccessful) task.result else null)
            }
        }.onFailure { continuation.resume(null) }
    }

    /** Once, the first time there is something to be notified about; the answer is the user's to change. */
    private fun requestPermissionOnce(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val stored = prefs(context)
        if (stored.getBoolean(PermissionAskedKey, false) || !inFront()) return
        scope.launch {
            val granted = EpisodeReleaseNotificationPlatform.requestAuthorization()
            stored.edit().putBoolean(PermissionAskedKey, true).apply()
            log.i { "notification permission granted=$granted" }
        }
    }

    internal fun inFront(): Boolean = runCatching {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }.getOrDefault(false)

    private const val RecommendedTarget = "recommended"

    /** From `MainActivity`: a tapped Social notification opens its place. */
    fun handleIntent(intent: Intent?) {
        val target = intent?.getStringExtra(TargetExtra) ?: return
        intent.removeExtra(TargetExtra)
        SocialNavigation.open(
            when (target) {
                "friend_requests" -> SocialNavigationTarget.FriendRequests
                RecommendedTarget -> SocialNavigationTarget.Recommended
                else -> SocialNavigationTarget.Inbox
            },
        )
    }

    // --- receiving -----------------------------------------------------------------------------

    fun onMessage(context: Context, data: Map<String, String>) {
        if (data["nuvio"] != "social") return
        val recipient = data["recipient"] ?: return
        val key = data["key"] ?: return
        val title = data["title"] ?: return
        // A cold start runs this before MainActivity: the stores it reads must be ready.
        SocialStorage.initialize(context)
        PhoneSocialAlertsStorage.initialize(context)
        if (prefs(context).getString(ProfileKey, null) != recipient) {
            log.i { "dropped ${data["kind"]}: not for the profile signed in here" }
            return
        }
        if (!PhoneSocialAlerts.current.allows(data["kind"])) return
        if (inFront()) return
        Handler(Looper.getMainLooper()).post { SocialBanners.markSeen(recipient, key) }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        ensureChannel(context)
        val tap = PendingIntent.getActivity(
            context,
            key.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                // A recommendation waits on its own page (2026-10-22); the server still names the Inbox.
                .putExtra(TargetExtra, if (data["kind"] == "recommendation") RecommendedTarget else data["target"] ?: "inbox"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = data["body"].orEmpty()
        val builder = NotificationCompat.Builder(context, ChannelId)
            .setSmallIcon(com.nuvio.app.R.drawable.ic_notification_small)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tap)
        // The title's art, as the banners and pop-ups show it. Best effort: no picture is still a notification.
        data["image"]?.let(::downloadImage)?.let { picture ->
            builder.setLargeIcon(picture)
            builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as Bitmap?))
        }
        runCatching { NotificationManagerCompat.from(context).notify(key.hashCode(), builder.build()) }
            .onFailure { log.w { "notify failed: ${it.message}" } }
    }

    private fun PhoneSocialAlertsState.allows(kind: String?): Boolean = when (kind) {
        "friend_request" -> allows(SocialPopupKind.FriendRequest)
        "party_invite" -> allows(SocialPopupKind.PartyInvite)
        "join_request" -> allows(SocialPopupKind.JoinRequest)
        "friend_watching" -> allows(SocialPopupKind.FriendWatching)
        "recommendation" -> allows(SocialPopupKind.Recommendation)
        // Friend accepted: no switch of its own, only the master one.
        else -> enabled
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(ChannelId) != null) return
        manager.createNotificationChannel(
            NotificationChannel(ChannelId, "Social", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Friend requests, party invites, join requests and recommendations"
            },
        )
    }

    private fun downloadImage(url: String): Bitmap? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 4_000
        connection.readTimeout = 4_000
        try {
            connection.inputStream.use(BitmapFactory::decodeStream)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

/** Registered in the app manifest. FCM runs it on a worker thread, so blocking briefly here is allowed. */
class SocialPushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        SocialPush.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        SocialPush.onMessage(applicationContext, message.data)
    }
}

internal data class SocialPushInput(val activeProfileId: String?, val ready: Boolean, val alertsOn: Boolean)

internal enum class SocialPushStep { Wait, Forget, Unregister, Register }

/**
 * What the registration should do now. [registered] is the profile this phone's token was last registered for,
 * which is also what decides whose pushes are shown, so it changes only on a real change.
 *
 * Every activation starts "not ready" (no capabilities yet, cached or no `me`), and before the first activation
 * there is no profile at all. Neither is a sign-out: forgetting the profile then dropped every push as "not for
 * this profile" until Social finished loading, which, when the session came back late, meant until the Social
 * tab was opened (2026-10-19).
 */
internal fun socialPushStep(input: SocialPushInput, registered: String?, seenActive: Boolean): SocialPushStep {
    val profile = input.activeProfileId
    return when {
        // Signed out, Social switched off, or a profile without Social - once one has been active here.
        profile == null -> if (seenActive && registered != null) SocialPushStep.Forget else SocialPushStep.Wait
        !input.alertsOn -> when (registered) {
            null -> SocialPushStep.Wait
            profile -> SocialPushStep.Unregister
            else -> SocialPushStep.Forget
        }
        input.ready -> SocialPushStep.Register
        // Another profile is active and not loaded yet: the old one's pushes are no longer this phone's.
        registered != null && registered != profile -> SocialPushStep.Forget
        else -> SocialPushStep.Wait
    }
}
