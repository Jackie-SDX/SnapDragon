package com.threeseeds.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class GameEngineRestoreTest {

    @Test
    fun `restore replaces the state wholesale without emitting events`() {
        val engine = GameEngine()
        engine.apply(Move.Place(Position(4)))
        val snapshot = engine.state

        val other = GameEngine()
        other.restore(snapshot)

        assertEquals(snapshot, other.state)
        assertEquals(snapshot.history.size, other.state.history.size)
    }

    @Test
    fun `a restored engine continues applying legal moves from the snapshot`() {
        val engine = GameEngine()
        engine.apply(Move.Place(Position(0))) // P1
        engine.apply(Move.Place(Position(4))) // P2

        val mirror = GameEngine()
        mirror.restore(engine.state)

        val result = mirror.apply(Move.Place(Position(1))) // P1 continues
        assertEquals(Player.ONE, result.state.board[Position(1)])
        assertEquals(GamePhase.PLACEMENT, result.state.phase)
    }
}
