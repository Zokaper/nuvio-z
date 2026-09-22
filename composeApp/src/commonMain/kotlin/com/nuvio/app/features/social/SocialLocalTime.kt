package com.nuvio.app.features.social

/**
 * The viewer's UTC offset at [epochMs], for Recently Watched's Today / Yesterday buckets.
 *
 * Taken at the instant rather than once, so a bucket boundary across a DST change lands on the
 * local midnight the viewer actually lives in.
 */
internal expect fun socialUtcOffsetMs(epochMs: Long): Long
