package com.nuvio.app.features.social

internal expect object SocialStorage {
    fun loadPayload(profileId: String): String?
    fun savePayload(profileId: String, payload: String)
    fun loadOutbox(profileId: String): String?
    fun saveOutbox(profileId: String, payload: String)
}

