package ai.rever.boss.plugin.dynamic.arcade.skystack

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Pure Sky Stack simulation, ported from the original HTML game. Coordinates
 * are density-independent "CSS pixels" so the original values and timings can
 * be used unchanged by the Compose renderer.
 */
class SkyStackEngine(private val random: Random = Random.Default) {
    companion object {
        const val SIZE = 132f
        const val BLOCK_HEIGHT = 26f
        const val RANGE = 210f
        const val PERFECT = 7f
        const val BASE_HUE = 262f
    }

    data class Block(
        val x: Float,
        val z: Float,
        val w: Float,
        val d: Float,
        val hue: Float,
    )

    data class MovingBlock(
        val axis: Axis,
        var x: Float,
        var z: Float,
        val w: Float,
        val d: Float,
        var dir: Int,
        val hue: Float,
    )

    data class Slice(
        val x: Float,
        val z: Float,
        val w: Float,
        val d: Float,
        var y: Float,
        var vy: Float,
        var alpha: Float,
        val hue: Float,
    )

    data class Ring(val block: Block, val y: Float, var progress: Float = 0f)

    data class Star(val x: Float, val y: Float, val radius: Float, val phase: Float)

    enum class Axis { X, Z }
    enum class DropResult { PLACED, MISSED }

    val blocks = mutableListOf<Block>()
    val slices = mutableListOf<Slice>()
    val rings = mutableListOf<Ring>()
    val stars = List(110) {
        Star(
            x = random.nextFloat(),
            y = random.nextFloat(),
            radius = random.nextFloat() * 1.4f + 0.4f,
            phase = random.nextFloat() * (Math.PI.toFloat() * 2f),
        )
    }

    var current: MovingBlock? = null
        private set
    var level = 1
        private set
    var combo = 0
        private set
    var cameraY = 0f
        private set
    var cameraTarget = 0f
        private set
    var shakeTime = 0f
        private set
    var elapsed = 0f
        private set
    var lastDropWasPerfect = false
        private set

    val score: Int
        get() = level - 1

    init {
        reset()
    }

    fun reset() {
        blocks.clear()
        blocks += Block(
            x = -SIZE / 2f,
            z = -SIZE / 2f,
            w = SIZE,
            d = SIZE,
            hue = BASE_HUE,
        )
        slices.clear()
        rings.clear()
        level = 1
        combo = 0
        cameraY = 0f
        cameraTarget = 0f
        shakeTime = 0f
        lastDropWasPerfect = false
        spawn()
    }

    fun speedForLevel(): Float = min(250f + level * 9f, 620f)

    /** Advance animation state. The moving block advances only during play. */
    fun update(dt: Float, isPlaying: Boolean) {
        elapsed += dt
        cameraY += (cameraTarget - cameraY) * min(1f, dt * 5f)
        shakeTime = max(0f, shakeTime - dt)

        if (isPlaying) {
            current?.let { moving ->
                val speed = speedForLevel()
                when (moving.axis) {
                    Axis.X -> {
                        moving.x += moving.dir * speed * dt
                        if (moving.x > RANGE) {
                            moving.x = RANGE
                            moving.dir = -1
                        }
                        val lower = -RANGE - moving.w
                        if (moving.x < lower) {
                            moving.x = lower
                            moving.dir = 1
                        }
                    }

                    Axis.Z -> {
                        moving.z += moving.dir * speed * dt
                        if (moving.z > RANGE) {
                            moving.z = RANGE
                            moving.dir = -1
                        }
                        val lower = -RANGE - moving.d
                        if (moving.z < lower) {
                            moving.z = lower
                            moving.dir = 1
                        }
                    }
                }
            }
        }

        slices.forEach { slice ->
            slice.vy += 1400f * dt
            slice.y -= slice.vy * dt
            slice.alpha -= dt * 0.9f
        }
        slices.removeAll { it.alpha <= 0f }

        rings.forEach { it.progress += dt * 2.4f }
        rings.removeAll { it.progress >= 1f }
    }

    fun drop(): DropResult {
        val moving = current ?: return DropResult.MISSED
        val previous = blocks.last()
        val delta = when (moving.axis) {
            Axis.X -> moving.x - previous.x
            Axis.Z -> moving.z - previous.z
        }
        val movingSize = if (moving.axis == Axis.X) moving.w else moving.d
        val overlap = movingSize - abs(delta)

        if (overlap <= 0f) {
            lastDropWasPerfect = false
            slices += Slice(
                x = moving.x,
                z = moving.z,
                w = moving.w,
                d = moving.d,
                y = (level + 1) * BLOCK_HEIGHT,
                vy = 60f,
                alpha = 1f,
                hue = moving.hue,
            )
            shakeTime = 0.4f
            current = null
            return DropResult.MISSED
        }

        var placedX = moving.x
        var placedZ = moving.z
        var placedW = moving.w
        var placedD = moving.d

        lastDropWasPerfect = abs(delta) <= PERFECT
        if (lastDropWasPerfect) {
            combo++
            when (moving.axis) {
                Axis.X -> {
                    placedX = previous.x
                    if (combo >= 3) {
                        val grow = min(10f, SIZE - placedW)
                        placedW += grow
                        placedX -= grow / 2f
                    }
                }

                Axis.Z -> {
                    placedZ = previous.z
                    if (combo >= 3) {
                        val grow = min(10f, SIZE - placedD)
                        placedD += grow
                        placedZ -= grow / 2f
                    }
                }
            }
        } else {
            combo = 0
            val cutSize = abs(delta)
            when (moving.axis) {
                Axis.X -> {
                    placedX = max(moving.x, previous.x)
                    placedW = overlap
                    slices += Slice(
                        x = if (delta > 0f) placedX + overlap else moving.x,
                        z = moving.z,
                        w = cutSize,
                        d = moving.d,
                        y = (level + 1) * BLOCK_HEIGHT,
                        vy = 0f,
                        alpha = 1f,
                        hue = moving.hue,
                    )
                }

                Axis.Z -> {
                    placedZ = max(moving.z, previous.z)
                    placedD = overlap
                    slices += Slice(
                        x = moving.x,
                        z = if (delta > 0f) placedZ + overlap else moving.z,
                        w = moving.w,
                        d = cutSize,
                        y = (level + 1) * BLOCK_HEIGHT,
                        vy = 0f,
                        alpha = 1f,
                        hue = moving.hue,
                    )
                }
            }
        }

        val placed = Block(placedX, placedZ, placedW, placedD, moving.hue)
        blocks += placed
        if (lastDropWasPerfect) {
            rings += Ring(placed, (level + 1) * BLOCK_HEIGHT)
        }
        level++
        cameraTarget = max(0f, (level - 4) * BLOCK_HEIGHT)
        spawn()
        return DropResult.PLACED
    }

    private fun spawn() {
        val previous = blocks.last()
        val axis = if (level % 2 == 1) Axis.X else Axis.Z
        current = MovingBlock(
            axis = axis,
            x = if (axis == Axis.X) -RANGE - SIZE else previous.x,
            z = if (axis == Axis.Z) -RANGE - SIZE else previous.z,
            w = previous.w,
            d = previous.d,
            dir = 1,
            hue = (BASE_HUE + level * 8f) % 360f,
        )
    }
}
