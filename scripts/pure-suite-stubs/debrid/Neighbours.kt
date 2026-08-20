// Neighbour stubs for the debrid stream-presentation group. Same rule as the other stub set:
// nothing under test is stubbed. StreamModels.kt itself is compiled from the shipped source, so
// StreamItem, AioStreamData and the cache-status types are real - only the three things that
// drag in the build config, the generated Compose resource bundle and the platform key store are
// stood in for here.
//
// Keep these in step with the real declarations. If one drifts the compile fails, which is the
// intended alarm.

package com.nuvio.app.core.build

// The real one is an `expect object` with ten members; StreamModels.kt reads exactly one.
object AppFeaturePolicy {
    val p2pEnabled: Boolean = true
}
