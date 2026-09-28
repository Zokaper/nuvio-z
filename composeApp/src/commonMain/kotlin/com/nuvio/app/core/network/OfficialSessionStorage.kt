package com.nuvio.app.core.network

import io.github.jan.supabase.auth.SessionManager

/**
 * Where this platform keeps the official session, or null to keep supabase-kt's default.
 *
 * ⚠ **Vanilla-bug patch, `drop-at-next-sync`** (Docs/VANILLA-BUGS.md V3, Docs/PATCH-SURFACE.md). The
 * default is right on Android and iOS, where preferences belong to one app. On the JVM it is not: it is
 * `Preferences.userRoot()` - on Windows the registry key `HKCU\Software\JavaSoft\Prefs` - under a name
 * derived only from the backend host, so every desktop app pointed at `api.nuvio.tv` shares one stored
 * login. See the desktop actual in `NuvioZDesktop` for what that costs and what replaces it.
 *
 * Read by upstream's `SupabaseProvider` at the single `install(Auth)` seam.
 */
internal expect fun officialSessionManager(backendUrl: String): SessionManager?
