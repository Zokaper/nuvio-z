package com.nuvio.app.core.debug

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.ComposeUIViewController
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.settings.SelfSigningSetupScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.UIKit.UIViewController

/**
 * Fills [SelfSigningSpike.platform] when this iOS build is linked with the self-signing library, and starts the
 * on-open renewal. The actual comes from `src/iosSelfSigning` or the no-op in `src/iosNoSelfSigning`, chosen per
 * target in build.gradle.kts.
 */
internal expect fun installSelfSigningSpike()

/** The platform without the UI side, for the Swift entry points below; null in builds without the library. */
internal expect fun selfSigningPlatform(): SelfSigningPlatform?

/**
 * Whether this copy signs itself: linked with the library and installed by the Nuvio Z installer (a SideStore copy of
 * the same IPA is not). No side effects (unlike [selfSigningPlatform]).
 */
internal expect val selfSigningActive: Boolean

/**
 * Swift: the "Refresh Nuvio Z" App Intent and the background refresh task call this. Renews with the account
 * saved in the Keychain and never waits for a person; [onDone] gets one line for Shortcuts to show.
 */
fun selfSigningRefresh(trigger: String, onDone: (String) -> Unit) {
    val platform = selfSigningPlatform() ?: return onDone("This build of Nuvio Z can’t renew itself.")
    CoroutineScope(Dispatchers.Default).launch {
        val line = runCatching {
            val root = Json.parseToJsonElement(platform.refreshSaved(trigger)).jsonObject
            if (root["ok"]?.jsonPrimitive?.content == "true") {
                "Nuvio Z renewed."
            } else {
                val error = root["error"]?.jsonObject
                when (error?.get("kind")?.jsonPrimitive?.content) {
                    "codeNeeded" -> "Apple asked for a code. Open Nuvio Z and renew once by hand."
                    else -> "Nuvio Z couldn’t renew: ${error?.get("summary")?.jsonPrimitive?.content ?: "unknown error"}"
                }
            }
        }.getOrElse { "Nuvio Z couldn’t renew: ${it.message}" }
        onDone(line)
    }
}

/**
 * Swift: whether to show the "Keep Nuvio Z working" set-up over the app at launch (before any sign-in): only in
 * builds that renew themselves, and until the daily refresh is set up on this iPhone.
 */
fun selfSigningSetupNeeded(): Boolean = selfSigningPlatform()?.automationSetUp() == false

/** Swift: that set-up, as a screen for a full-screen cover. [onClose] after "Done" or "Later". */
fun SelfSigningSetupViewController(onClose: () -> Unit): UIViewController = ComposeUIViewController {
    val platform = selfSigningPlatform()
    NuvioTheme(darkTheme = true) {
        if (platform != null) SelfSigningSetupScreen(platform, onClose) else LaunchedEffect(Unit) { onClose() }
    }
}

/** Swift: whether the last renewal worked (the background task reports it to iOS). */
fun selfSigningLastRefreshOk(): Boolean = runCatching {
    val last = selfSigningPlatform()?.lastRefresh() ?: return false
    Json.parseToJsonElement(last).jsonObject["ok"]?.jsonPrimitive?.content == "true"
}.getOrDefault(false)
