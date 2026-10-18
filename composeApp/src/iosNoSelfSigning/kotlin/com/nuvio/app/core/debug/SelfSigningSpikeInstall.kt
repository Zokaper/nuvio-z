package com.nuvio.app.core.debug

/** This build is not linked with the self-signing library, so there is nothing to install. */
internal actual fun installSelfSigningSpike() = Unit

internal actual fun selfSigningPlatform(): SelfSigningPlatform? = null

internal actual val selfSigningLinked: Boolean = false
