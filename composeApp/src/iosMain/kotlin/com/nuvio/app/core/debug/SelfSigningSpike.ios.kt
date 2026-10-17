package com.nuvio.app.core.debug

/**
 * Fills [SelfSigningSpike.probe] when this iOS build is linked with the self-signing library. The actual comes
 * from `src/iosSelfSigning` or the no-op in `src/iosNoSelfSigning`, chosen per target in build.gradle.kts.
 */
internal expect fun installSelfSigningSpike()
