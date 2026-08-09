package com.skystream.ssheadunit.utils

import android.content.IntentFilter
import com.skystream.ssheadunit.contract.KeyIntent

object IntentFilters {
    val keyEvent = IntentFilter(KeyIntent.action)
}