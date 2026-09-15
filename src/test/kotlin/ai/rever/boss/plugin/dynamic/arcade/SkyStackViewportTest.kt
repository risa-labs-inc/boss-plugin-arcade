package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.dynamic.arcade.skystack.*
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertNull

class SkyStackViewportTest {
    @Test
    fun movingBlockCornersStayInsideThePlayableFieldAcrossPaneSizesAndTowerLevels() {
        for ((width, height) in listOf(1000f to 700f, 600f to 500f, 420f to 300f, 300f to 500f, 900f to 240f, 300f to 240f)) {
            for (level in listOf(1, 5, 50)) {
                val camera = ((level - 4).coerceAtLeast(0)) * SkyStackEngine.BLOCK_HEIGHT
                val view = skyStackPlayViewport(width, height, level, camera)
                val topInset = if (height < 420f) 64f else 104f
                for (axis in 0..1) for (position in listOf(-SkyStackEngine.RANGE - SkyStackEngine.SIZE, SkyStackEngine.RANGE)) {
                    val x = if (axis == 0) position else -SkyStackEngine.SIZE / 2f
                    val z = if (axis == 1) position else -SkyStackEngine.SIZE / 2f
                    for (dx in listOf(0f, SkyStackEngine.SIZE)) for (dz in listOf(0f, SkyStackEngine.SIZE)) {
                        for (face in listOf(0f, SkyStackEngine.BLOCK_HEIGHT)) {
                            val point = projectSkyStackPoint(x + dx, z + dz,
                                (level + 1) * SkyStackEngine.BLOCK_HEIGHT - face, view.centerX, view.centerY) * view.scale
                            assertTrue(point.x >= 11.9f && point.x <= width - 11.9f,
                                "Moving block clipped horizontally at $width x $height, level $level: $point")
                            assertTrue(point.y >= topInset - 0.1f && point.y <= height - 75.9f,
                                "Moving block overlaps HUD at $width x $height, level $level: $point")
                        }
                    }
                }
            }
        }
    }
    @Test
    fun overviewFitsWholeTallTowerAndRejectsUnusablePanesWithoutMinimumScaleOverflow() {
        for ((width, height) in listOf(300f to 240f, 420f to 300f, 1000f to 700f)) {
            for (level in listOf(1, 50, 500)) {
                val view = skyStackOverviewViewport(width, height, level)!!
                for (x in listOf(-66f, 66f)) for (z in listOf(-66f, 66f)) {
                    for (y in listOf(0f, level * SkyStackEngine.BLOCK_HEIGHT)) {
                        val point = projectSkyStackPoint(x, z, y, view.centerX, view.centerY) * view.scale
                        assertTrue(point.x >= 11.9f && point.x <= width - 11.9f)
                        assertTrue(point.y >= 71.9f && point.y <= height - 119.9f,
                            "Full tower clipped at level $level: $point")
                    }
                }
            }
        }
        assertNull(skyStackOverviewViewport(0f, 0f, 50))
        assertNull(skyStackOverviewViewport(180f, 160f, 50))
    }

}
