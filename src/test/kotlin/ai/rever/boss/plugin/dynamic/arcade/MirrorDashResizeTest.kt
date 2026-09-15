package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.dynamic.arcade.mirrordash.MirrorDashEngine
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MirrorDashResizeTest {
    @Test
    fun resizingDoesNotTeleportThePlayerIntoAnExistingGate() {
        val engine = MirrorDashEngine(Random(7))
        engine.resize(1000f, 700f)
        engine.reset()
        // This gate is harmless at 700dp, but the old resize teleported the
        // player's baseline into it when shrinking to 240dp.
        val gate = MirrorDashEngine.Obstacle(engine.playerX - 0.01f, 0.05f, 175f, 40f)
        val shard = MirrorDashEngine.Shard(0.27f, 200f)
        engine.obstacles.add(gate)
        engine.shards.add(shard)
        val gateDistance = gate.y - engine.playerY
        val shardDistance = shard.y - engine.playerY
        engine.resize(300f, 240f)
        assertEquals(gateDistance, gate.y - engine.playerY, 0.001f)
        assertEquals(shardDistance, shard.y - engine.playerY, 0.001f)
        assertTrue(engine.update(0f), "Resizing alone must not crash the run")
        assertTrue((engine.playerY + engine.playerR) * engine.viewportScale < 240f - 56f, "Sparks must clear HUD controls")
        engine.resize(0f, 0f)
        engine.resize(1000f, 700f)
        assertEquals(gateDistance, gate.y - engine.playerY, 0.001f)
        assertEquals(shardDistance, shard.y - engine.playerY, 0.001f)
        assertEquals(1, engine.obstacles.size)
        assertEquals(0, engine.displayScore())
    }
    @Test
    fun horizontalGapsRemainSafeWhenPaneWidthShrinks() {
        val engine = MirrorDashEngine(Random(4))
        engine.resize(1000f, 700f)
        engine.reset()
        engine.obstacles.add(MirrorDashEngine.Obstacle(engine.playerX + 0.02f, 0.05f,
            engine.playerY - 20f, 40f))
        assertTrue(engine.update(0f))
        engine.resize(300f, 240f)
        assertTrue(engine.update(0f), "Width changes must scale spark and gate collision geometry together")
        assertEquals(0.3f, engine.viewportScale, 0.001f)
        engine.resize(1000f, 700f)
        assertTrue(engine.update(0f))
    }

}
