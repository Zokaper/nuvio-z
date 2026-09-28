package com.nuvio.app.features.tmdb

/**
 * Z: TMDB enrichment is on for a profile that has never chosen, using the bundled key; a personal
 * key stays an optional override (maintainer decision, 2026-09-28).
 *
 * This is only the fallback for an absent stored value. An explicit ON or OFF - set here, or
 * arriving through settings sync, whose import clears only the keys a payload carries - is always
 * read back as stored, so no existing choice is overwritten.
 */
internal const val TMDB_ENRICHMENT_ENABLED_BY_DEFAULT = true
