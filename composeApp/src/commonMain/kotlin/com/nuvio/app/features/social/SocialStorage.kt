package com.nuvio.app.features.social

internal expect object SocialStorage {
    fun loadPayload(profileId: String): String?
    fun savePayload(profileId: String, payload: String)
    fun loadOutbox(profileId: String): String?
    fun saveOutbox(profileId: String, payload: String)

    /** Join requests abandoned at an identity boundary, awaiting reconciliation. JSON. */
    fun loadAbandonedJoinRequests(profileId: String): String?
    fun saveAbandonedJoinRequests(profileId: String, payload: String?)

    /** The keys of Social pop-ups already shown on this device (see `SocialPopupLedger`). JSON. */
    fun loadPopupSeen(profileId: String): String?
    fun savePopupSeen(profileId: String, payload: String)
}

