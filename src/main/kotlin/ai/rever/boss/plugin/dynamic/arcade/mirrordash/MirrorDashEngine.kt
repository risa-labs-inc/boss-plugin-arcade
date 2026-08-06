package ai.rever.boss.plugin.dynamic.arcade.mirrordash

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Mirror Dash simulation — a direct port of the update/spawn logic in the
 * original HTML game. All coordinates are in density-independent "css px":
 * the renderer scales by density, so the physics match the original 1:1.
 */
class MirrorDashEngine(private val random: Random = Random.Default) {

    data class Obstacle(
        var x: Float, val w: Float, var y: Float, val h: Float,
        var passed: Boolean = false,
        val moving: Boolean = false, val phase: Float = 0f, val amp: Float = 0f,
    )

    data class Shard(var x: Float, var y: Float, val r: Float = 7f, var rot: Float = 0f)

    data class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val max: Float, val size: Float, val cyan: Boolean,
    )

    data class Star(val x: Float, val y: Float, val z: Float, val s: Float, val p: Float)

    var width = 0f; private set
    var height = 0f; private set

    var score = 0.0; private set
    var combo = 0; private set
    var mult = 1; private set
    var speed = 290f; private set
    var time = 0f; private set
    var shake = 0f; private set
    var flash = 0f; private set
    var alive = true; private set

    // Player: x is a fraction of width; the twin renders mirrored at (1 - x).
    var playerX = 0.27f; private set
    var playerTarget = 0.27f; private set
    var playerDir = 1; private set
    var playerY = 0f; private set
    val playerR = 11f

    val obstacles = mutableListOf<Obstacle>()
    val shards = mutableListOf<Shard>()
    val particles = mutableListOf<Particle>()
    var stars = listOf<Star>(); private set
    val trailA = ArrayDeque<Pair<Float, Float>>()
    val trailB = ArrayDeque<Pair<Float, Float>>()

    private var spawnTimer = 0.6f
    private var shardTimer = 1.2f

    fun resize(w: Float, h: Float) {
        if (w == width && h == height) return
        width = w
        height = h
        playerY = h * 0.78f
        val count = min(150, (w * h / 8000f).toInt())
        stars = List(count) {
            Star(
                x = random.nextFloat() * w, y = random.nextFloat() * h,
                z = 0.2f + random.nextFloat() * 0.8f,
                s = 0.3f + random.nextFloat() * 1.4f,
                p = random.nextFloat() * (Math.PI.toFloat() * 2),
            )
        }
    }

    fun reset() {
        score = 0.0; combo = 0; mult = 1; speed = 290f
        spawnTimer = 0.6f; shardTimer = 1.2f
        time = 0f; shake = 0f; flash = 0f; alive = true
        obstacles.clear(); shards.clear(); particles.clear()
        trailA.clear(); trailB.clear()
        playerX = 0.27f; playerTarget = 0.27f; playerDir = 1
    }

    fun reverse() {
        playerDir = -playerDir
        burst(playerX * width, playerY, cyan = false, n = 7)
        burst((1 - playerX) * width, playerY, cyan = true, n = 7)
    }

    /** Advance one frame. Returns false on the frame the player dies. */
    fun update(dt: Float): Boolean {
        time += dt
        speed = min(690f, 290f + score.toFloat() * 0.32f)

        playerTarget += playerDir * dt * (0.25f + speed / 2400f)
        if (playerTarget > 0.45f) { playerTarget = 0.45f; playerDir = -1 }
        if (playerTarget < 0.08f) { playerTarget = 0.08f; playerDir = 1 }
        playerX += (playerTarget - playerX) * min(1f, dt * 15f)

        val ax = playerX * width
        val bx = (1 - playerX) * width
        trailA.addFirst(ax to playerY); while (trailA.size > 20) trailA.removeLast()
        trailB.addFirst(bx to playerY); while (trailB.size > 20) trailB.removeLast()

        spawnTimer -= dt
        if (spawnTimer <= 0) {
            spawnObstacle()
            spawnTimer = maxOf(0.48f, 1.03f - speed / 1050f) + random.nextFloat() * 0.25f
        }
        shardTimer -= dt
        if (shardTimer <= 0) {
            shards.add(Shard(x = 0.12f + random.nextFloat() * 0.33f, y = -30f))
            shardTimer = 0.85f + random.nextFloat() * 1.25f
        }

        for (o in obstacles) {
            o.y += speed * dt
            if (o.moving) o.x += sin(time * 2.1f + o.phase) * o.amp * dt
            val ox = o.x * width
            val ow = o.w * width
            for (px in floatArrayOf(ax, bx)) {
                if (hitCircleRect(px, playerY, playerR, ox, o.y, ow, o.h) ||
                    hitCircleRect(px, playerY, playerR, width - ox - ow, o.y, ow, o.h)
                ) {
                    burst(ax, playerY, cyan = false, n = 28)
                    burst(bx, playerY, cyan = true, n = 28)
                    alive = false; shake = 18f; flash = 1f
                    return false
                }
            }
            if (!o.passed && o.y > playerY + playerR) {
                o.passed = true
                score += 10 * mult
            }
        }
        obstacles.retainAll { it.y < height + 120 }

        val taken = mutableListOf<Shard>()
        for (s in shards) {
            s.y += speed * 0.78f * dt
            s.rot += dt * 5f
            val sx = s.x * width
            // Spark A collects the left diamond, spark B its mirror.
            for ((px, mx) in listOf(ax to sx, bx to (width - sx))) {
                val dx = px - mx
                val dy = playerY - s.y
                val reach = playerR + s.r + 4
                if (s !in taken && dx * dx + dy * dy < reach * reach) {
                    taken.add(s)
                    combo++
                    mult = min(5, 1 + combo / 4)
                    score += 25 * mult
                    burst(mx, s.y, cyan = px == bx, n = 15)
                }
            }
            if (s !in taken && s.y > playerY + 50) {
                combo = 0; mult = 1
            }
        }
        shards.removeAll(taken)
        shards.retainAll { it.y < height + 60 }

        for (p in particles) {
            p.x += p.vx * dt; p.y += p.vy * dt
            p.vx *= 0.985f; p.vy *= 0.985f
            p.life -= dt
        }
        particles.retainAll { it.life > 0 }

        score += dt * 3 * mult
        shake *= 0.02f.pow(dt)
        flash *= 0.003f.pow(dt)
        return true
    }

    fun displayScore(): Int = floor(score).toInt()

    private fun spawnObstacle() {
        val lane = 0.13f + random.nextFloat() * 0.28f
        obstacles.add(
            Obstacle(
                x = lane, w = 0.08f + random.nextFloat() * 0.08f,
                y = -80f, h = 38f + random.nextFloat() * 55f,
                moving = score > 180 && random.nextFloat() < 0.28f,
                phase = random.nextFloat() * (Math.PI.toFloat() * 2),
                amp = 0.025f + random.nextFloat() * 0.025f,
            ),
        )
        if (score > 350 && random.nextFloat() < 0.25f) {
            val shift = if (random.nextFloat() < 0.5f) -0.16f else 0.16f
            val lane2 = (lane + shift).coerceIn(0.1f, 0.43f)
            obstacles.add(Obstacle(x = lane2, w = 0.065f, y = -150f, h = 32f))
        }
    }

    private fun burst(x: Float, y: Float, cyan: Boolean, n: Int) {
        repeat(n) {
            val a = random.nextFloat() * (Math.PI.toFloat() * 2)
            val v = 40 + random.nextFloat() * 150
            particles.add(
                Particle(
                    x = x, y = y,
                    vx = kotlin.math.cos(a) * v, vy = sin(a) * v,
                    life = 0.35f + random.nextFloat() * 0.45f, max = 0.8f,
                    size = 1 + random.nextFloat() * 3, cyan = cyan,
                ),
            )
        }
    }

    private fun hitCircleRect(
        cx: Float, cy: Float, r: Float,
        rx: Float, ry: Float, rw: Float, rh: Float,
    ): Boolean {
        val x = cx.coerceIn(rx, rx + rw)
        val y = cy.coerceIn(ry, ry + rh)
        val dx = cx - x
        val dy = cy - y
        return dx * dx + dy * dy < r * r
    }
}
