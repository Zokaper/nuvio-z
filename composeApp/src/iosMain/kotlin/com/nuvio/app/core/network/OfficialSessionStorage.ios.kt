package com.nuvio.app.core.network

import io.github.jan.supabase.auth.SessionManager

/** User defaults are per bundle, so no other app can share or rotate this login. */
internal actual fun officialSessionManager(backendUrl: String): SessionManager? = null
