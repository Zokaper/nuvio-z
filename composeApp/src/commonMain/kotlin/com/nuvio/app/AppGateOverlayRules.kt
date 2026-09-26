package com.nuvio.app

/**
 * Whether the gate's launch overlay (profile backdrop + spinner) covers the screen while the
 * natively hosted main content starts - iOS, where the gate does not render `MainAppContent`
 * itself (`renderMainContent = false`) and waits for it to report ready.
 *
 * **Never while the setup wizard gates the app.** Main content is deliberately not mounted then
 * (Phase 8: the wizard must keep the gate's touches), so it can never report ready, and an overlay
 * waiting for it sat on top of the wizard for good. Stage 8's revision 10 and the per-device run
 * made that wizard owed on every existing iPhone: "stuck on the loading screen after choosing a
 * profile" on debug 54. Import-free so the pure suite runs it.
 */
fun appLaunchOverlayVisible(
    renderMainContent: Boolean,
    gateIsMain: Boolean,
    externalMainContentReady: Boolean,
    setupWizardGating: Boolean,
): Boolean = !renderMainContent && gateIsMain && !externalMainContentReady && !setupWizardGating
