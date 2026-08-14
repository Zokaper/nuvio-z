package com.nuvio.app.core.ui

/**
 * Whether a backdrop blur actually blurs anything on this device.
 *
 * ⚠ **A frosted surface has two very different appearances and only one of them is designed.**
 * Haze and `Modifier.blur` both need `RenderEffect`, which is **API 31+**, and `minSdk` here is
 * **24**. Below that a `hazeEffect` is a no-op and all that remains is the tint painted over it -
 * so a tint chosen to look good over a blur is, on those devices, a thin translucent sheet over
 * live content. That is exactly what revision 2 of the setup wizard shipped, and it came back
 * from a device unreadable.
 *
 * So anything that frosts should pick its alphas from this rather than assume the blur landed.
 * `NuvioNavigationBar` already does the same thing one step cruder, keying its tint off whether a
 * `HazeState` was passed at all (`alpha = if (hazeState != null) 0.55f else 0.82f`).
 *
 * ⚠ This says "the platform can blur", not "this particular surface is blurred". A caller that
 * does not wire a `HazeState` is unblurred whatever this returns.
 */
internal expect fun isBackdropBlurSupported(): Boolean
