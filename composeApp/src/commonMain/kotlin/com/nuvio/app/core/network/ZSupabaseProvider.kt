package com.nuvio.app.core.network

import com.nuvio.app.core.build.AppVersionConfig
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders

/**
 * Nuvio Z's own backend.
 *
 * Nuvio Z is a mod of Nuvio, and `api.nuvio.tv` is the official upstream backend, which we have no
 * administrative relationship to. Accounts, profiles and all base user data stay there, so a Z
 * install remains cross-compatible with vanilla Nuvio; this second backend holds only Z's social and
 * Watch Together surface.
 *
 * The two are bridged by [ZSessionBridge] rather than by shared credentials.
 *
 * There is deliberately no fallback-endpoint retry here. The official client has one because it
 * serves playback and sign-in, where an outage is fatal; the social surface degrades to hidden
 * instead, so a second endpoint would be complexity without a matching failure to absorb.
 */
object ZSupabaseProvider {

    val isConfigured: Boolean get() = ZSupabaseConfig.isConfigured

    @OptIn(SupabaseInternal::class)
    val client by lazy {
        val userAgent = "NuvioZ/${AppVersionConfig.VERSION_NAME.ifBlank { "dev" }}"
        createSupabaseClient(
            supabaseUrl = ZSupabaseConfig.URL,
            supabaseKey = ZSupabaseConfig.PUBLISHABLE_KEY,
        ) {
            httpConfig {
                defaultRequest {
                    headers.append(HttpHeaders.UserAgent, userAgent)
                }
            }
            // The session is installed by the exchange, never restored from disk: it is derived from
            // whichever official session is live, so a stale one loaded at start-up would only ever
            // be wrong. Refreshing is likewise the bridge's job, because recovering from an expired
            // Z session means re-presenting the official token.
            install(Auth) {
                autoLoadFromStorage = false
                autoSaveToStorage = false
                alwaysAutoRefresh = false
                // This client has no browser/deep-link auth lifecycle. Its session is imported by
                // ZSessionBridge; an asynchronous platform init is only another writer to the
                // in-memory session state and can race that import back to NotAuthenticated.
                autoSetupPlatform = false
            }
            install(Postgrest)
            install(Realtime)
        }
    }
}
