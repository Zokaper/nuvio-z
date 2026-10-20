// The repository clock is a neighbour of the pure timing rules, not a test substitute for them.
package com.nuvio.app.features.watchparty
internal fun currentEpochMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
