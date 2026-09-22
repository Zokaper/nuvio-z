package com.nuvio.app.features.social

internal actual fun socialUtcOffsetMs(epochMs: Long): Long = java.util.TimeZone.getDefault().getOffset(epochMs).toLong()
