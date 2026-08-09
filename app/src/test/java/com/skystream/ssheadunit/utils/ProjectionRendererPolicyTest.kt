package com.skystream.ssheadunit.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectionRendererPolicyTest {
    @Test
    fun `AutoPro X defaults to SurfaceView`() {
        assertEquals(
            Settings.ViewMode.SURFACE,
            ProjectionRendererPolicy.defaultViewMode(
                hardware = "rk3288",
                board = "rk30sdk",
                manufacturer = "rockchip",
                model = "AutoPro X"
            )
        )
    }

    @Test
    fun `legacy Rockchip defaults to SurfaceView`() {
        assertEquals(
            Settings.ViewMode.SURFACE,
            ProjectionRendererPolicy.defaultViewMode(
                hardware = "rk3288",
                board = "rk3288_box",
                manufacturer = "unknown",
                model = "Head Unit"
            )
        )
    }

    @Test
    fun `unknown devices keep TextureView default`() {
        assertEquals(
            Settings.ViewMode.TEXTURE,
            ProjectionRendererPolicy.defaultViewMode(
                hardware = "qcom",
                board = "kalama",
                manufacturer = "Google",
                model = "Pixel"
            )
        )
    }
}
