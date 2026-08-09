package com.skystream.ssheadunit.utils

import android.os.Build
import java.util.Locale

object ProjectionRendererPolicy {
    private val legacyRockchipHardwareIds = setOf("rk3066", "rk3188", "rk3288", "rk3368")
    private val legacyRockchipBoardIds = setOf("rk30sdk")

    fun defaultViewModeForCurrentDevice(): Settings.ViewMode =
        defaultViewMode(
            hardware = Build.HARDWARE,
            board = Build.BOARD
        )

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
        return matchesAny(hardware, legacyRockchipHardwareIds) ||
            matchesAny(board, legacyRockchipBoardIds)
    }

    private fun matchesAny(value: String?, ids: Set<String>): Boolean {
        val field = value.orEmpty().lowercase(Locale.ROOT)
        return ids.any { id -> field == id || field.startsWith("${id}_") || field.startsWith("${id}-") }
    }
}
