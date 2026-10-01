package com.nuvio.app.core.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.ktor.client.request.headers
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Never carries server bodies, request credentials or SDK error-parser side effects. */
internal class AuthHttpFailure(val status: Int, val invalidSession: Boolean) :
    Exception("Auth request rejected (HTTP $status)")

/**
 * Uses the existing configured HTTP stack, but deliberately bypasses Auth's error parser.
 * SDK 3.4.1 schedules clearSession for session_not_found even on a captured-token request.
 * Only AuthSessionCoordinator may decide to import/clear after checking current authority.
 */
@OptIn(SupabaseInternal::class)
internal class StatelessAuthRequests(private val client: SupabaseClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun user(accessToken: String): UserInfo = decode(request("user", HttpMethod.Get, accessToken))

    suspend fun refresh(refreshToken: String): UserSession = decode(request(
        "token?grant_type=refresh_token", HttpMethod.Post, client.supabaseKey,
        buildJsonObject { put("refresh_token", refreshToken) },
    ))

    suspend fun login(email: String, password: String, signUp: Boolean): UserSession? {
        // The supported email/password flow is IMPLICIT (the app's Auth configuration).
        // Preserve Email's credential encoding; never read/adopt the SDK's current session.
        val body = Email.encodeCredentials { this.email = email; this.password = password }
        val response = request(if (signUp) "signup" else "token?grant_type=password",
            HttpMethod.Post, client.supabaseKey, body)
        val payload = decode<JsonObject>(response)
        if (signUp && "access_token" !in payload) {
            decode<UserInfo>(response) // A legitimate email-confirmation response, not any 2xx body.
            return null
        }
        return decode(response)
    }

    suspend fun signOut(accessToken: String) {
        try {
            request("logout?scope=global", HttpMethod.Post, accessToken)
        } catch (error: AuthHttpFailure) {
            // Match SDK sign-out: expired/deleted/already signed-out sessions still clean locally.
            if (error.status !in listOf(401, 403, 404)) throw error
        }
    }

    private suspend fun request(path: String, method: HttpMethod, token: String, body: JsonObject? = null): String {
        val response = client.httpClient.request("${client.supabaseHttpUrl}/auth/v1/$path") {
            this.method = method
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val invalid = runCatching {
                val fields = json.decodeFromString<JsonObject>(text)
                listOf("error_code", "code", "error", "error_description", "msg", "message")
                    .mapNotNull { fields[it]?.jsonPrimitive?.contentOrNull }.any {
                        val value = it.lowercase()
                        value == "session_not_found" || value == "user_not_found" ||
                            ("jwt" in value && listOf("invalid", "expired", "malformed").any { marker -> marker in value })
                    }
            }.getOrDefault(false)
            throw AuthHttpFailure(response.status.value, invalid)
        }
        return text
    }

    private inline fun <reified T> decode(text: String): T = try {
        json.decodeFromString<T>(text)
    } catch (_: Exception) {
        throw IllegalStateException("Invalid auth response")
    }
}
