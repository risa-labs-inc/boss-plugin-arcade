package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.dynamic.arcade.battleship.BattleshipLogic
import ai.rever.boss.plugin.dynamic.arcade.battleship.BattleshipLogic.Orientation
import ai.rever.boss.plugin.dynamic.arcade.battleship.BattleshipLogic.Ship
import ai.rever.boss.plugin.dynamic.arcade.battleship.BattleshipLogic.ShipType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * These mirror the server-side checks in supabase/arcade_battleship.sql. The
 * server is the authority — this suite is here so the placement UI never offers
 * a fleet the server will reject, and so the row-wrap trap stays fixed.
 */
class BattleshipLogicTest {

    private fun fleet(vararg pairs: Pair<ShipType, List<Int>>): List<Ship> =
        pairs.map { (type, cells) -> Ship(type, cells) }

    private val validFleet = fleet(
        ShipType.CARRIER to listOf(0, 1, 2, 3, 4),
        ShipType.BATTLESHIP to listOf(10, 20, 30, 40),
        ShipType.CRUISER to listOf(55, 56, 57),
        ShipType.SUBMARINE to listOf(71, 81, 91),
        ShipType.DESTROYER to listOf(98, 99),
    )

    @Test
    fun acceptsACompleteLegalFleet() {
        assertTrue(BattleshipLogic.isCompleteFleet(validFleet))
    }

    @Test
    fun rejectsAFleetMissingAShip() {
        assertFalse(BattleshipLogic.isCompleteFleet(validFleet.dropLast(1)))
    }

    @Test
    fun rejectsAShipOfTheWrongLength() {
        val short = validFleet.map {
            if (it.type == ShipType.CARRIER) it.copy(cells = listOf(0, 1, 2, 3)) else it
        }
        assertFalse(BattleshipLogic.isCompleteFleet(short))
    }

    @Test
    fun rejectsOverlappingShips() {
        val overlapping = validFleet.map {
            if (it.type == ShipType.BATTLESHIP) it.copy(cells = listOf(1, 11, 21, 31)) else it
        }
        assertFalse(BattleshipLogic.isCompleteFleet(overlapping))
    }

    @Test
    fun rejectsARunThatWrapsToTheNextRow() {
        // 9 and 10 are adjacent as indices but sit at opposite edges of the
        // board. This is the trap a naive `b == a + 1` check falls into.
        assertFalse(BattleshipLogic.isStraightRun(listOf(9, 10)))
        val wrapped = validFleet.map {
            if (it.type == ShipType.DESTROYER) it.copy(cells = listOf(9, 10)) else it
        }
        assertFalse(BattleshipLogic.isCompleteFleet(wrapped))
    }

    @Test
    fun rejectsANonContiguousShip() {
        assertFalse(BattleshipLogic.isStraightRun(listOf(55, 56, 58)))
    }

    @Test
    fun acceptsAVerticalRunAndAnyCellOrder() {
        assertTrue(BattleshipLogic.isStraightRun(listOf(71, 81, 91)))
        assertTrue(BattleshipLogic.isStraightRun(listOf(91, 71, 81)))
    }

    @Test
    fun spanRefusesToRunOffTheRightEdge() {
        // Column 6 leaves only four cells before the edge, so a carrier cannot
        // start there — the wrap it would otherwise produce is exactly the bug.
        assertNull(BattleshipLogic.span(ShipType.CARRIER, BattleshipLogic.cellOf(0, 6), Orientation.HORIZONTAL))
        assertNotNull(BattleshipLogic.span(ShipType.CARRIER, BattleshipLogic.cellOf(0, 5), Orientation.HORIZONTAL))
    }

    @Test
    fun spanRefusesToRunOffTheBottom() {
        assertNull(BattleshipLogic.span(ShipType.CARRIER, BattleshipLogic.cellOf(6, 0), Orientation.VERTICAL))
        assertNotNull(BattleshipLogic.span(ShipType.CARRIER, BattleshipLogic.cellOf(5, 0), Orientation.VERTICAL))
    }

    @Test
    fun canPlaceRejectsACollision() {
        val taken = setOf(12)
        assertFalse(
            BattleshipLogic.canPlace(ShipType.DESTROYER, BattleshipLogic.cellOf(1, 1), Orientation.HORIZONTAL, taken)
        )
        assertTrue(
            BattleshipLogic.canPlace(ShipType.DESTROYER, BattleshipLogic.cellOf(1, 4), Orientation.HORIZONTAL, taken)
        )
    }

    @Test
    fun randomFleetsAreAlwaysLegal() {
        // Seeded across many boards: placement retries until it fits, so a bad
        // ordering shows up as a hang or an illegal fleet, not a flaky failure.
        repeat(300) { seed ->
            val ships = BattleshipLogic.randomFleet(Random(seed))
            assertTrue(BattleshipLogic.isCompleteFleet(ships), "seed $seed produced an illegal fleet")
            assertEquals(BattleshipLogic.FLEET_CELLS, ships.flatMap { it.cells }.toSet().size)
        }
    }

    @Test
    fun fleetJsonMatchesTheWireFormatTheServerValidates() {
        val json = BattleshipLogic.fleetToJson(validFleet)
        assertEquals(
            "[{\"id\":\"carrier\",\"cells\":[0,1,2,3,4]}," +
                "{\"id\":\"battleship\",\"cells\":[10,20,30,40]}," +
                "{\"id\":\"cruiser\",\"cells\":[55,56,57]}," +
                "{\"id\":\"submarine\",\"cells\":[71,81,91]}," +
                "{\"id\":\"destroyer\",\"cells\":[98,99]}]",
            json,
        )
    }

    @Test
    fun sunkOnlyWhenEveryCellIsHit() {
        val cruiser = Ship(ShipType.CRUISER, listOf(55, 56, 57))
        assertFalse(BattleshipLogic.isSunk(cruiser, setOf(55, 56)))
        assertTrue(BattleshipLogic.isSunk(cruiser, setOf(55, 56, 57)))
        assertTrue(BattleshipLogic.isSunk(cruiser, setOf(0, 55, 56, 57)))
    }

    @Test
    fun shipAtFindsTheOccupantAndNothingElse() {
        assertEquals(ShipType.CRUISER, BattleshipLogic.shipAt(validFleet, 56)?.type)
        assertNull(BattleshipLogic.shipAt(validFleet, 50))
    }
}
