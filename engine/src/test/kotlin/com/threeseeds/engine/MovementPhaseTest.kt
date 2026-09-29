package com.threeseeds.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MovementPhaseTest {

    /** P1: 0,1,5   P2: 3,4,7   empty: 2,6,8   P1 to move. */
    private fun engineInMovementPhase(): GameEngine {
        val engine = GameEngine()
        listOf(0, 3, 1, 4, 5, 7).forEach { engine.apply(Move.Place(Position(it))) }
        return engine
    }

    @Test
    fun `a player can move their own seed to a connected empty point`() {
        val engine = engineInMovementPhase()
        val result = engine.apply(Move.Relocate(Position(1), Position(2))) // 1's neighbors: 0,2,4 -> 2 is empty
        assertTrue(result.events.any { it is GameEvent.SeedMoved })
        assertEquals(Player.ONE, engine.state.board[Position(2)])
        assertTrue(engine.state.board.isEmpty(Position(1)))
    }

    @Test
    fun `adjacent-only rules reject a move to a non-adjacent empty point`() {
        val engine = engineInMovementPhase()
        engine.movementRules = MovementRules.TAPATAN
        val before = engine.state
        val result = engine.apply(Move.Relocate(Position(0), Position(8))) // 0 and 8 are not connected
        assertEquals(before, engine.state)
        assertTrue(result.events.single() is GameEvent.MoveRejected)
    }

    @Test
    fun `standard rules allow a move to any vacant point`() {
        val engine = engineInMovementPhase() // empty: 2, 6, 8
        val result = engine.apply(Move.Relocate(Position(0), Position(8))) // 0 and 8 are not connected
        assertTrue(result.events.any { it is GameEvent.SeedMoved })
        assertEquals(Player.ONE, engine.state.board[Position(8)])
        assertTrue(engine.state.board.isEmpty(Position(0)))
    }

    @Test
    fun `moving onto an occupied point is rejected`() {
        val engine = engineInMovementPhase()
        val before = engine.state
        val result = engine.apply(Move.Relocate(Position(1), Position(4))) // 4 is occupied by P2
        assertEquals(before, engine.state)
        assertTrue(result.events.single() is GameEvent.MoveRejected)
    }

    @Test
    fun `a player cannot move the opponent's seed`() {
        val engine = engineInMovementPhase()
        val before = engine.state
        val result = engine.apply(Move.Relocate(Position(3), Position(6))) // 3 belongs to P2; it's P1's turn
        assertEquals(before, engine.state)
        assertTrue(result.events.single() is GameEvent.MoveRejected)
    }

    @Test
    fun `moving a point to itself is rejected as an occupied destination`() {
        val engine = engineInMovementPhase()
        val before = engine.state
        val result = engine.apply(Move.Relocate(Position(1), Position(1)))
        assertEquals(before, engine.state)
        assertTrue(result.events.single() is GameEvent.MoveRejected)
    }

    @Test
    fun `relocating does not change either player's remaining seed count`() {
        val engine = engineInMovementPhase()
        engine.apply(Move.Relocate(Position(1), Position(2)))
        assertEquals(0, engine.state.seedsRemaining[Player.ONE])
        assertEquals(0, engine.state.seedsRemaining[Player.TWO])
    }
}
