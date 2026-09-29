package com.threeseeds.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WinConditionTest {

    @Test
    fun `completing any of the 8 winning lines during placement ends the game for that player`() {
        for (line in WinDetector.WINNING_LINES) {
            val engine = GameEngine()
            val p1 = line.map { it.index }
            val blockers = Position.ALL.map { it.index }.filter { it !in p1 }.take(2)

            // P1, P2, P1, P2, P1 — P1 completes the line on their 3rd placement.
            // P2 only ever places 2 seeds here, so P2 can never win first.
            engine.apply(Move.Place(Position(p1[0])))
            engine.apply(Move.Place(Position(blockers[0])))
            engine.apply(Move.Place(Position(p1[1])))
            engine.apply(Move.Place(Position(blockers[1])))
            val result = engine.apply(Move.Place(Position(p1[2])))

            assertEquals(GamePhase.WON, engine.state.phase, "line $line should have won")
            assertEquals(Player.ONE, engine.state.winner, "line $line")
            assertEquals(line, engine.state.winningLine, "line $line")
            assertTrue(result.events.any { it is GameEvent.Won }, "line $line should emit a Won event")
        }
    }

    @Test
    fun `completing a winning line during movement ends the game`() {
        val engine = GameEngine()
        listOf(0, 3, 1, 4, 5, 7).forEach { engine.apply(Move.Place(Position(it))) } // P1: 0,1,5  P2: 3,4,7
        val result = engine.apply(Move.Relocate(Position(5), Position(2))) // P1 -> 0,1,2 = top row

        assertEquals(GamePhase.WON, engine.state.phase)
        assertEquals(Player.ONE, engine.state.winner)
        assertEquals(listOf(Position(0), Position(1), Position(2)), engine.state.winningLine)
        assertTrue(result.events.any { it is GameEvent.Won })
    }

    @Test
    fun `no further move is accepted once the game is won`() {
        val engine = GameEngine()
        listOf(0, 3, 1, 4).forEach { engine.apply(Move.Place(Position(it))) }
        engine.apply(Move.Place(Position(2))) // P1 wins top row
        val before = engine.state

        val result = engine.apply(Move.Place(Position(6)))

        assertEquals(before, engine.state)
        assertTrue(result.events.single() is GameEvent.MoveRejected)
    }
}
