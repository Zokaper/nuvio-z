package com.nuvio.app.features.social

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone

internal actual fun socialUtcOffsetMs(epochMs: Long): Long =
    NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)) * 1000L
