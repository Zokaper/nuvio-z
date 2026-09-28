package com.nuvio.app.core.network

import io.github.jan.supabase.auth.SessionManager

/** SharedPreferences are per application id, so release and debug never share a login. */
internal actual fun officialSessionManager(backendUrl: String): SessionManager? = null
