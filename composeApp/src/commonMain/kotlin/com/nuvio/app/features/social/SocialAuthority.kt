package com.nuvio.app.features.social

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import com.nuvio.app.core.network.ZSessionBridge

/** Identity of an activation, deliberately distinct even when A returns after A -> B -> A. */
internal class SocialOperation(val profileId: String?) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SocialOperation>
}

/** Session boundary kept separate so repository regressions need no signed-in account. */
internal interface SocialSessionAccess {
    suspend fun ensure(profileId: String): Boolean
    suspend fun reexchange(profileId: String): Boolean
}

internal object LiveSocialSessionAccess : SocialSessionAccess {
    override suspend fun ensure(profileId: String) = ZSessionBridge.ensureSession(profileId)
    override suspend fun reexchange(profileId: String) = ZSessionBridge.reexchange(profileId)
}
