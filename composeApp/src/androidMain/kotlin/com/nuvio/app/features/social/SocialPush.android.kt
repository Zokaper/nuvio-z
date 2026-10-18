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
                val active = state.activeProfileId?.takeIf {
                    state.capabilities.socialEnabled && SocialFeaturePreferencesRepository.isEnabledNow && state.me != null
                }
                active to alerts.enabled
            }.distinctUntilChanged().collect { (profile, alertsOn) -> sync(profile, alertsOn) }
        }
    }

    /** A new token from FCM: registered at once if there is a profile to register it for. */
    fun onNewToken(token: String) {
        val context = appContext ?: return
        val profile = prefs(context).getString(ProfileKey, null) ?: return
        scope.launch { register(context, profile, token) }
    }

    private suspend fun sync(profile: String?, alertsOn: Boolean) {
        val context = appContext ?: return
        val stored = prefs(context)
        when {
            profile == null -> {
                // Signed out, or Social off before anything loaded: pushes for the old profile are dropped.
                stored.edit().remove(ProfileKey).apply()
            }
            !alertsOn || !SocialFeaturePreferencesRepository.isEnabledNow -> {
                val token = stored.getString(TokenKey, null)
                if (token != null && stored.getString(ProfileKey, null) == profile) {
                    SocialRepository.unregisterPushToken(token)
                        .onFailure { log.w { "unregister failed: ${it.message}" } }
                }
                stored.edit().remove(ProfileKey).apply()
            }
            else -> {
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

    /** From `MainActivity`: a tapped Social notification opens its place. */
    fun handleIntent(intent: Intent?) {
        val target = intent?.getStringExtra(TargetExtra) ?: return
        intent.removeExtra(TargetExtra)
        SocialNavigation.open(
            if (target == "friend_requests") SocialNavigationTarget.FriendRequests else SocialNavigationTarget.Inbox,
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
                .putExtra(TargetExtra, data["target"] ?: "inbox"),
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
