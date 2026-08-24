package com.nuvio.app.core.build

/** Version-name rules owned by Nuvio Z rather than by the upstream updater. */
object NuvioZVersion {
    private val zVersion = Regex(
        """^(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)-z([1-9]\d*)(?:\.\d+)?$""",
    )

    /**
     * Returns the named vanilla base for a Z release.
     *
     * The optional final numeric component is the debug-channel counter, not part of either the
     * vanilla base or the Z revision. Versions from before the `-z<n>` scheme deliberately return
     * null: the bridge build must not claim a base that its version does not name.
     */
    fun vanillaBaseVersion(versionName: String): String? =
        zVersion.matchEntire(versionName.trim())?.groupValues?.get(1)
}
