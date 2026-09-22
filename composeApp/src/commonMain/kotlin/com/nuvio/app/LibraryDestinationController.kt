package com.nuvio.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide because iOS hosts each native top-level tab in a separate Compose controller. */
internal object LibraryDestinationController {
    private val _destination = MutableStateFlow(LibrarySubDestination.Library)
    val destination = _destination.asStateFlow()

    fun show(destination: LibrarySubDestination) {
        _destination.value = destination
    }

    fun apply(intent: NavigationIntent.Tab) {
        if (intent.tab == AppScreenTab.Library) {
            intent.librarySubDestination?.let(::show)
        }
    }
}
