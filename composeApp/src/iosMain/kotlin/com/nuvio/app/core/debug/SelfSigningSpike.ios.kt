package com.nuvio.app.core.debug

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fills [SelfSigningSpike.platform] when this iOS build is linked with the self-signing library, and starts the
 * on-open renewal. The actual comes from `src/iosSelfSigning` or the no-op in `src/iosNoSelfSigning`, chosen per
 * target in build.gradle.kts.
 */
internal expect fun installSelfSigningSpike()

/** The platform without the UI side, for the Swift entry points below; null in builds without the library. */
internal expect fun selfSigningPlatform(): SelfSigningPlatform?

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

/** Swift: whether the last renewal worked (the background task reports it to iOS). */
fun selfSigningLastRefreshOk(): Boolean = runCatching {
    val last = selfSigningPlatform()?.lastRefresh() ?: return false
    Json.parseToJsonElement(last).jsonObject["ok"]?.jsonPrimitive?.content == "true"
}.getOrDefault(false)
