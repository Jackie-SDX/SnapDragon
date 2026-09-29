package com.threeseeds.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UndoTest {

    @Test
    fun `undo reverts the most recent placement`() {
        val engine = GameEngine()
        engine.apply(Move.Place(Position(0)))
        engine.undo()
        assertTrue(engine.state.board.isEmpty(Position(0)))
        assertEquals(Player.ONE, engine.state.currentPlayer)
        assertEquals(3, engine.state.seedsRemaining[Player.ONE])
    }

    @Test
    fun `undo reverts the most recent relocation`() {
        val engine = GameEngine()
        listOf(0, 3, 1, 4, 5, 7).forEach { engine.apply(Move.Place(Position(it))) }
        engine.apply(Move.Relocate(Position(1), Position(2)))
        engine.undo()
        assertEquals(Player.ONE, engine.state.board[Position(1)])
        assertTrue(engine.state.board.isEmpty(Position(2)))
    }

    @Test
    fun `undo immediately after a win reverts exactly one move, not two`() {
        // This is the exact scenario a bug was caught in during pre-implementation
        // verification: history must record a new entry on a winning move too,
        // or undo from WON jumps back two plies instead of one.
        val engine = GameEngine()
        listOf(0, 3, 1, 4).forEach { engine.apply(Move.Place(Position(it))) }
        engine.apply(Move.Place(Position(2))) // P1 completes top row -> WON
        assertEquals(GamePhase.WON, engine.state.phase)

        engine.undo()

        assertEquals(GamePhase.PLACEMENT, engine.state.phase)
        assertNull(engine.state.winner)
        assertNull(engine.state.winningLine)
        assertEquals(Player.ONE, engine.state.currentPlayer)
        assertTrue(engine.state.board.isEmpty(Position(2)), "only the winning move should be undone")
        assertEquals(Player.ONE, engine.state.board[Position(0)], "earlier moves must still stand")
        assertEquals(Player.ONE, engine.state.board[Position(1)], "earlier moves must still stand")
    }

    @Test
    fun `redo after undo reproduces the same win`() {
        val engine = GameEngine()
        listOf(0, 3, 1, 4).forEach { engine.apply(Move.Place(Position(it))) }
        engine.apply(Move.Place(Position(2)))
        engine.undo()
        engine.apply(Move.Place(Position(2)))
        assertEquals(GamePhase.WON, engine.state.phase)
        assertEquals(Player.ONE, engine.state.winner)
    }

    @Test
    fun `undo on a fresh game is rejected rather than throwing, and changes nothing`() {
        val engine = GameEngine()
        val before = engine.state
        val result = engine.undo()
        assertEquals(before, engine.state)
        assertTrue(result.events.single() is GameEvent.MoveRejected)
    }

    @Test
    fun `repeated undo can walk all the way back to the start`() {
        val engine = GameEngine()
        listOf(0, 3, 1).forEach { engine.apply(Move.Place(Position(it))) }
        engine.undo()
        engine.undo()
        engine.undo()
        assertEquals(GameState(), engine.state)
    }
}
