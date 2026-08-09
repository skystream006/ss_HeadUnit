package com.skystream.ssheadunit.utils

import android.os.Build
import java.util.Locale

object ProjectionRendererPolicy {
    private val currentDeviceDefault by lazy {
        defaultViewMode(
            hardware = Build.HARDWARE,
            board = Build.BOARD,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL
        )
    }

    fun defaultViewModeForCurrentDevice(): Settings.ViewMode =
        currentDeviceDefault

    fun defaultViewMode(
        hardware: String?,
        board: String?,
        manufacturer: String?,
        model: String?
    ): Settings.ViewMode {
        val fields = listOf(hardware, board, manufacturer, model)
            .map { it.orEmpty().lowercase(Locale.ROOT) }

        return if (isLegacyRockchip(fields)) {
            Settings.ViewMode.SURFACE
        } else {
            Settings.ViewMode.TEXTURE
        }
    }

    private fun isLegacyRockchip(fields: List<String>): Boolean {
        val legacyRockchipIds = listOf("rk3066", "rk3188", "rk3288", "rk3368", "rk30sdk")
        return fields.any { field -> legacyRockchipIds.any { field.contains(it) } }
    }
}
