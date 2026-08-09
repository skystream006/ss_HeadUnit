package com.skystream.ssheadunit.utils

import android.os.Build
import java.util.Locale

object ProjectionRendererPolicy {
    private val legacyRockchipHardwareIds = setOf("rk3066", "rk3188", "rk3288", "rk3368")
    // Some Rockchip Android builds expose a board family rather than the SoC in Build.BOARD.
    private val legacyRockchipBoardIds = setOf("rk3066", "rk3188", "rk3288", "rk3368", "rk30sdk")

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
        // Match exact IDs and delimiter-suffixed variants only; avoid broad prefix matching that
        // could classify unrelated future boards as legacy Rockchip.
        return ids.any { id -> field == id || field.startsWith("${id}_") || field.startsWith("${id}-") }
    }
}
