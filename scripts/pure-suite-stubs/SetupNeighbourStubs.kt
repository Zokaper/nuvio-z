// Neighbour stubs for the setup wizard's pure suites, per AGENTS.md "Verifying without
// Gradle", item 2. Nothing under test is stubbed: SetupWizardSteps.kt and SetupWizardPresets.kt
// are the real shipped files, unmodified.
//
// The three enums below are declared in files that reach kotlinx.serialization, the Compose
// resource bundle and `runBlocking { getString(...) }` - MetaScreenSettingsRepository.kt and
// WatchProgressModels.kt - none of which the presets touch. The enum *entries* are copied
// exactly, because that is the whole point: a preset naming a value the app no longer has must
// fail to compile here.

package com.nuvio.app.features.details

enum class MetaScreenBackgroundMode {
    Normal,
    Cinematic,
    DominantColor,
    ;

    val usesBackdropBackground: Boolean
        get() = this != Normal
}

enum class MetaEpisodeCardStyle {
    Horizontal,
    List,
}
