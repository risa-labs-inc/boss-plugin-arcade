package ai.rever.boss.plugin.dynamic.arcade.game2048

import kotlin.random.Random

/**
 * A tile on the board. Identity (`id`) is stable across moves so the UI can
 * animate position changes; value doubling happens at settle time.
 */
data class TileData(
    val id: Long,
    val value: Int,
    val row: Int,
    val col: Int,
)

/**
 * Result of a directional move, split into the two phases the UI animates:
 * slide (tiles at new positions, values unchanged, absorbed tiles still present
 * on top of their survivor) and settle (absorbed removed, survivors doubled).
 */
data class MoveOutcome(
    val tiles: List<TileData>,
    val absorbedIds: Set<Long>,
    val mergedIds: Set<Long>,
    val gained: Int,
    val moved: Boolean,
)

/**
 * Pure 2048 rules — a direct port of the logic block in the original HTML game.
 */
object Game2048Logic {
    const val SIZE = 4
    const val WIN_VALUE = 2048

    fun computeMove(tiles: List<TileData>, dr: Int, dc: Int): MoveOutcome {
        val grid = Array(SIZE) { arrayOfNulls<TileData>(SIZE) }
        for (t in tiles) grid[t.row][t.col] = t

        val rows = if (dr == 1) SIZE - 1 downTo 0 else 0 until SIZE
        val cols = if (dc == 1) SIZE - 1 downTo 0 else 0 until SIZE

        val result = tiles.associateBy { it.id }.toMutableMap()
        val absorbedIds = mutableSetOf<Long>()
        val mergedIds = mutableSetOf<Long>()
        var gained = 0
        var moved = false

        for (r in rows) {
            for (c in cols) {
                val tile = grid[r][c] ?: continue
                var nr = r
                var nc = c
                var target: TileData? = null
                while (true) {
                    val tr = nr + dr
                    val tc = nc + dc
                    if (tr !in 0 until SIZE || tc !in 0 until SIZE) break
                    val cell = grid[tr][tc]
                    if (cell == null) {
                        nr = tr
                        nc = tc
                        continue
                    }
                    // a tile that already merged this move cannot merge again
                    if (cell.value == tile.value && cell.id !in mergedIds) {
                        target = cell
                        nr = tr
                        nc = tc
                    }
                    break
                }
                if (nr == r && nc == c) continue
                grid[r][c] = null
                moved = true
                val movedTile = tile.copy(row = nr, col = nc)
                result[tile.id] = movedTile
                if (target != null) {
                    mergedIds.add(target.id)
                    absorbedIds.add(tile.id)
                    gained += target.value * 2
                } else {
                    grid[nr][nc] = movedTile
                }
            }
        }
        return MoveOutcome(
            tiles = tiles.map { result.getValue(it.id) },
            absorbedIds = absorbedIds,
            mergedIds = mergedIds,
            gained = gained,
            moved = moved,
        )
    }

    /** Second phase of a move: drop absorbed tiles, double the survivors. */
    fun settle(outcome: MoveOutcome): List<TileData> =
        outcome.tiles
            .filter { it.id !in outcome.absorbedIds }
            .map { if (it.id in outcome.mergedIds) it.copy(value = it.value * 2) else it }

    fun canMove(tiles: List<TileData>): Boolean {
        val grid = Array(SIZE) { arrayOfNulls<TileData>(SIZE) }
        for (t in tiles) grid[t.row][t.col] = t
        for (r in 0 until SIZE) {
            for (c in 0 until SIZE) {
                val t = grid[r][c] ?: return true
                if (r + 1 < SIZE && grid[r + 1][c]?.value == t.value) return true
                if (c + 1 < SIZE && grid[r][c + 1]?.value == t.value) return true
            }
        }
        return false
    }

    fun spawn(tiles: List<TileData>, id: Long, random: Random = Random.Default): TileData? {
        val occupied = tiles.map { it.row to it.col }.toSet()
        val empties = buildList {
            for (r in 0 until SIZE) for (c in 0 until SIZE) {
                if (r to c !in occupied) add(r to c)
            }
        }
        if (empties.isEmpty()) return null
        val (r, c) = empties[random.nextInt(empties.size)]
        return TileData(id = id, value = if (random.nextFloat() < 0.9f) 2 else 4, row = r, col = c)
    }
}
