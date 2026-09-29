package com.threeseeds.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EdgeCasesTest {

    @Test
    fun `reset returns to a fresh initial state regardless of prior progress`() {
        val engine = GameEngine()
        listOf(0, 3, 1, 4).forEach { engine.apply(Move.Place(Position(it))) }
        engine.apply(Move.Place(Position(2))) // now WON

        engine.reset()

        assertEquals(GameState(), engine.state)
    }

    @Test
    fun `no further move is accepted once the game is drawn`() {
        val engine = GameEngine()
        listOf(0, 1, 6, 3, 8, 7).forEach { engine.apply(Move.Place(Position(it))) }
        val shuttle = listOf(
            Move.Relocate(Position(0), Position(4)),
            Move.Relocate(Position(1), Position(2)),
            Move.Relocate(Position(4), Position(0)),
            Move.Relocate(Position(2), Position(1))
        )
        repeat(2) { shuttle.forEach { move -> engine.apply(move) } }
        assertEquals(GamePhase.DRAW, engine.state.phase)
        val before = engine.state

        val result = engine.apply(Move.Relocate(Position(6), Position(3)))

        assertEquals(before, engine.state)
        assertTrue(result.events.single() is GameEvent.MoveRejected)
    }

    @Test
    fun `SeedPlaced and SeedMoved events carry the correct position and player`() {
        val engine = GameEngine()
        val placeResult = engine.apply(Move.Place(Position(0)))
        val placed = placeResult.events.single() as GameEvent.SeedPlaced
        assertEquals(Position(0), placed.position)
        assertEquals(Player.ONE, placed.player)

        listOf(3, 1, 4, 5, 7).forEach { engine.apply(Move.Place(Position(it))) } // P1: 0,1,5  P2: 3,4,7
        assertEquals(GamePhase.MOVEMENT, engine.state.phase)

        val moveResult = engine.apply(Move.Relocate(Position(1), Position(2)))
        val moved = moveResult.events.single() as GameEvent.SeedMoved
        assertEquals(Position(1), moved.from)
        assertEquals(Position(2), moved.to)
        assertEquals(Player.ONE, moved.player)
    }

    @Test
    fun `legalDestinations for a phase outside MOVEMENT is always empty`() {
        val engine = GameEngine()
        assertEquals(emptySet(), engine.legalDestinations(Position(0)))
    }
}
