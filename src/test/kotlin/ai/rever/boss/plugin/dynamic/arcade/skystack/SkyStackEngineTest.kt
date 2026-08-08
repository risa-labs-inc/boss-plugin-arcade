package ai.rever.boss.plugin.dynamic.arcade.skystack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkyStackEngineTest {
    @Test
    fun cleanMissEndsRunWithoutRaisingScore() {
        val engine = SkyStackEngine()

        assertEquals(SkyStackEngine.DropResult.MISSED, engine.drop())
        assertEquals(0, engine.score)
        assertEquals(null, engine.current)
        assertEquals(1, engine.slices.size)
    }

    @Test
    fun overlappingDropTrimsBlockAndRaisesScore() {
        val engine = SkyStackEngine()
        val speed = engine.speedForLevel()
        val targetX = -50f
        val distance = targetX - (-SkyStackEngine.RANGE - SkyStackEngine.SIZE)
        engine.update(distance / speed, isPlaying = true)

        assertEquals(SkyStackEngine.DropResult.PLACED, engine.drop())
        assertEquals(1, engine.score)
        assertEquals(2, engine.blocks.size)
        assertTrue(kotlin.math.abs(engine.blocks.last().w - 116f) < 0.001f)
        assertEquals(1, engine.slices.size)
    }

    @Test
    fun centeredDropBuildsPerfectComboAndAlternatesAxis() {
        val engine = SkyStackEngine()
        val speed = engine.speedForLevel()
        val targetX = -SkyStackEngine.SIZE / 2f
        val distance = targetX - (-SkyStackEngine.RANGE - SkyStackEngine.SIZE)
        engine.update(distance / speed, isPlaying = true)

        assertEquals(SkyStackEngine.DropResult.PLACED, engine.drop())
        assertEquals(1, engine.combo)
        assertTrue(engine.lastDropWasPerfect)
        assertEquals(1, engine.rings.size)
        assertEquals(SkyStackEngine.Axis.Z, engine.current?.axis)
    }

    @Test
    fun towerExportContainsEveryFaceAndScore() {
        val engine = SkyStackEngine()
        val speed = engine.speedForLevel()
        val targetX = -SkyStackEngine.SIZE / 2f
        val distance = targetX - (-SkyStackEngine.RANGE - SkyStackEngine.SIZE)
        engine.update(distance / speed, isPlaying = true)
        engine.drop()

        val svg = SkyStackTowerExport.svg(engine.blocks, engine.score)

        assertTrue(svg.startsWith("<?xml"))
        assertTrue(svg.contains("ALTITUDE 1"))
        assertEquals(engine.blocks.size * 3, Regex("<polygon ").findAll(svg).count())
    }
}
