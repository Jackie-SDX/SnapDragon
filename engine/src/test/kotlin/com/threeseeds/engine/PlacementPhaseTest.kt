package com.threeseeds.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlacementPhaseTest {

    @Test
    fun `a new engine starts with an empty board, player ONE to move, in the placement phase`() {
        val engine = GameEngine()
        assertTrue(Position.ALL.all { engine.state.board.isEmpty(it) })
        assertEquals(Player.ONE, engine.state.currentPlayer)
        assertEquals(GamePhase.PLACEMENT, engine.state.phase)
        assertEquals(3, engine.state.seedsRemaining[Player.ONE])
        assertEquals(3, engine.state.seedsRemaining[Player.TWO])
        assertNull(engine.state.winner)
    }

    @Test
    fun `turns alternate after every accepted move`() {
        val engine = GameEngine()
        assertEquals(Player.ONE, engine.state.currentPlayer)
        engine.apply(Move.Place(Position(0)))
        assertEquals(Player.TWO, engine.state.currentPlayer)
        engine.apply(Move.Place(Position(1)))
        assertEquals(Player.ONE, engine.state.currentPlayer)
    }

    @Test
    fun `a rejected move does not switch turns or change the board`() {
        val engine = GameEngine()
        engine.apply(Move.Place(Position(0))) // P1 places at 0
        val before = engine.state
        val result = engine.apply(Move.Place(Position(0))) // P2 tries the same point
        assertEquals(before, engine.state)
        assertTrue(result.events.single() is GameEvent.MoveRejected)
    }

    @Test
    fun `placing a seed occupies the point and reduces that player's remaining count`() {
        val engine = GameEngine()
        engine.apply(Move.Place(Position(4)))
        assertEquals(Player.ONE, engine.state.board[Position(4)])
        assertEquals(2, engine.state.seedsRemaining[Player.ONE])
    }

    @Test
    fun `after both players place all 3 seeds, the phase becomes MOVEMENT`() {
        val engine = GameEngine()
        listOf(0, 3, 1, 4, 5, 7).forEach { engine.apply(Move.Place(Position(it))) } // P1: 0,1,5  P2: 3,4,7
        assertEquals(GamePhase.MOVEMENT, engine.state.phase)
        assertEquals(0, engine.state.seedsRemaining[Player.ONE])
        assertEquals(0, engine.state.seedsRemaining[Player.TWO])
    }

    @Test
    fun `placement is rejected once the movement phase has started`() {
        val engine = GameEngine()
        listOf(0, 3, 1, 4, 5, 7).forEach { engine.apply(Move.Place(Position(it))) }
        val before = engine.state
        val result = engine.apply(Move.Place(Position(2)))
        assertEquals(before, engine.state)
        assertTrue(result.events.single() is GameEvent.MoveRejected)
    }
}
