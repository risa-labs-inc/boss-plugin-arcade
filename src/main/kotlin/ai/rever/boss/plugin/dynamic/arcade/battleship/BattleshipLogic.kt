package ai.rever.boss.plugin.dynamic.arcade.battleship

import kotlin.random.Random

/**
 * Pure Battleship rules. Deliberately mirrors the server-side checks in
 * supabase/arcade_battleship.sql: this copy exists to keep the placement UI
 * honest without a round-trip, NOT to be trusted. The server validates every
 * fleet again on submit, because this code runs on the player's machine.
 *
 * Cells are a single index, `row * SIZE + col`, matching the wire format.
 */
object BattleshipLogic {

    const val SIZE = 10
    const val CELLS = SIZE * SIZE

    enum class Orientation { HORIZONTAL, VERTICAL }

    /** The standard fleet. Ids are the wire values the server validates against. */
    enum class ShipType(val id: String, val label: String, val length: Int) {
        CARRIER("carrier", "Carrier", 5),
        BATTLESHIP("battleship", "Battleship", 4),
        CRUISER("cruiser", "Cruiser", 3),
        SUBMARINE("submarine", "Submarine", 3),
        DESTROYER("destroyer", "Destroyer", 2),
    }

    val FLEET: List<ShipType> = ShipType.entries.toList()

    /** Total occupied cells when a fleet is fully placed: 5+4+3+3+2. */
    val FLEET_CELLS: Int = FLEET.sumOf { it.length }

    data class Ship(val type: ShipType, val cells: List<Int>)

    fun rowOf(cell: Int): Int = cell / SIZE

    fun colOf(cell: Int): Int = cell % SIZE

    fun cellOf(row: Int, col: Int): Int = row * SIZE + col

    /** Classic battleship coordinates: rows A-J, columns 1-10 ("B7"). */
    fun cellName(cell: Int): String = "${'A' + rowOf(cell)}${colOf(cell) + 1}"

    /**
     * The cells a ship would occupy from [origin], or null if it would run off
     * the grid. A horizontal run must stay on one row — the reason this checks
     * the column rather than just the index is that cell 9 -> 10 is contiguous
     * as an index but wraps to the next row on the board.
     */
    fun span(type: ShipType, origin: Int, orientation: Orientation): List<Int>? {
        if (origin < 0 || origin >= CELLS) return null
        val row = rowOf(origin)
        val col = colOf(origin)
        return when (orientation) {
            Orientation.HORIZONTAL -> {
                if (col + type.length > SIZE) null
                else (0 until type.length).map { cellOf(row, col + it) }
            }
            Orientation.VERTICAL -> {
                if (row + type.length > SIZE) null
                else (0 until type.length).map { cellOf(row + it, col) }
            }
        }
    }

    /** True when [type] fits at [origin] without leaving the grid or touching [taken]. */
    fun canPlace(
        type: ShipType,
        origin: Int,
        orientation: Orientation,
        taken: Set<Int>,
    ): Boolean {
        val cells = span(type, origin, orientation) ?: return false
        return cells.none { it in taken }
    }

    /** A complete, legal random fleet. */
    fun randomFleet(random: Random = Random.Default): List<Ship> {
        // Longest first: placing the carrier last on a crowded board is what
        // makes naive shuffles fail and retry forever.
        val ships = mutableListOf<Ship>()
        val taken = mutableSetOf<Int>()
        for (type in FLEET.sortedByDescending { it.length }) {
            while (true) {
                val orientation =
                    if (random.nextBoolean()) Orientation.HORIZONTAL else Orientation.VERTICAL
                val origin = random.nextInt(CELLS)
                if (canPlace(type, origin, orientation, taken)) {
                    val cells = span(type, origin, orientation)!!
                    ships += Ship(type, cells)
                    taken += cells
                    break
                }
            }
        }
        return ships.sortedBy { it.type.ordinal }
    }

    /**
     * The same predicate the server enforces, so the UI can refuse to submit a
     * fleet the server would reject anyway.
     */
    fun isCompleteFleet(ships: List<Ship>): Boolean {
        if (ships.size != FLEET.size) return false
        if (ships.map { it.type }.toSet().size != FLEET.size) return false
        val all = mutableSetOf<Int>()
        for (ship in ships) {
            if (ship.cells.size != ship.type.length) return false
            if (ship.cells.any { it < 0 || it >= CELLS }) return false
            if (!isStraightRun(ship.cells)) return false
            all += ship.cells
        }
        return all.size == FLEET_CELLS
    }

    /** Contiguous along one row or one column, in any order. */
    fun isStraightRun(cells: List<Int>): Boolean {
        if (cells.isEmpty()) return false
        if (cells.size == 1) return true
        val sorted = cells.sorted()
        if (sorted.toSet().size != sorted.size) return false
        val sameRow = sorted.all { rowOf(it) == rowOf(sorted.first()) } &&
            sorted.zipWithNext().all { (a, b) -> b == a + 1 }
        val sameCol = sorted.all { colOf(it) == colOf(sorted.first()) } &&
            sorted.zipWithNext().all { (a, b) -> b == a + SIZE }
        return sameRow || sameCol
    }

    /** Wire format for the fleet RPCs: [{"id":"carrier","cells":[0,1,2,3,4]}, ...] */
    fun fleetToJson(ships: List<Ship>): String =
        // Explicit separators: joinToString defaults to ", ", which would put
        // stray spaces inside the payload the RPC parses.
        ships.joinToString(separator = ",", prefix = "[", postfix = "]") { ship ->
            val cells = ship.cells.joinToString(separator = ",", prefix = "[", postfix = "]")
            """{"id":"${ship.type.id}","cells":$cells}"""
        }

    /** What a shot at [cell] does to [ships], for the local preview of your own board. */
    fun shipAt(ships: List<Ship>, cell: Int): Ship? = ships.firstOrNull { cell in it.cells }

    /** True once every cell of [ship] appears in [hits]. */
    fun isSunk(ship: Ship, hits: Set<Int>): Boolean = hits.containsAll(ship.cells)
}
