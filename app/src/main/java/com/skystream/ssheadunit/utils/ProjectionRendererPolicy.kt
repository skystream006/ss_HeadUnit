package com.skystream.ssheadunit.utils

import android.os.Build
import java.util.Locale

object ProjectionRendererPolicy {
    private val currentDeviceDefault by lazy {
        defaultViewMode(
            hardware = Build.HARDWARE,
            board = Build.BOARD
        )
    }

    fun defaultViewModeForCurrentDevice(): Settings.ViewMode =
        currentDeviceDefault

    fun defaultViewMode(
        hardware: String?,
        board: String?
    ): Settings.ViewMode {
        return if (isLegacyRockchip(hardware, board)) {
            Settings.ViewMode.SURFACE
        } else {
            Settings.ViewMode.TEXTURE
        }
    }

    private fun isLegacyRockchip(hardware: String?, board: String?): Boolean {
        val legacyRockchipIds = listOf("rk3066", "rk3188", "rk3288", "rk3368", "rk30sdk")
        return listOf(hardware, board)
            .map { it.orEmpty().lowercase(Locale.ROOT) }
            .any { field -> legacyRockchipIds.any { id -> field == id || field.startsWith("${id}_") || field.startsWith("${id}-") } }
    }
}
