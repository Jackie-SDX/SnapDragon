package com.threeseeds.app.state

import com.threeseeds.engine.GameEngine
import com.threeseeds.engine.GamePhase
import com.threeseeds.engine.Move
import com.threeseeds.engine.Player
import com.threeseeds.engine.Position
import kotlin.test.Test
import kotlin.test.assertEquals

class GameStateCodecTest {

    @Test
    fun `blank input decodes to a fresh initial state`() {
        assertEquals(GameEngine().state, GameStateCodec.decode(""))
    }

    @Test
    fun `encode then decode reproduces the initial state exactly`() {
        val engine = GameEngine()
        val decoded = GameStateCodec.decode(GameStateCodec.encode(engine.state))
        assertEquals(engine.state, decoded)
    }

    @Test
    fun `round-trips a mid-placement state`() {
        val engine = GameEngine()
        listOf(4, 0, 1).forEach { engine.apply(Move.Place(Position(it))) }
        val decoded = GameStateCodec.decode(GameStateCodec.encode(engine.state))
        assertEquals(engine.state, decoded)
        assertEquals(GamePhase.PLACEMENT, decoded.phase)
    }

    @Test
    fun `round-trips a mid-movement state`() {
        val engine = GameEngine()
        listOf(0, 3, 1, 4, 5, 7).forEach { engine.apply(Move.Place(Position(it))) }
        engine.apply(Move.Relocate(Position(1), Position(2)))
        val decoded = GameStateCodec.decode(GameStateCodec.encode(engine.state))
        assertEquals(engine.state, decoded)
        assertEquals(GamePhase.MOVEMENT, decoded.phase)
    }

    @Test
    fun `round-trips a WON state, correctly re-deriving winner and winningLine`() {
        val engine = GameEngine()
        listOf(0, 3, 1, 4).forEach { engine.apply(Move.Place(Position(it))) }
        engine.apply(Move.Place(Position(2))) // P1 completes top row
        val decoded = GameStateCodec.decode(GameStateCodec.encode(engine.state))
        assertEquals(engine.state, decoded)
        assertEquals(GamePhase.WON, decoded.phase)
        assertEquals(Player.ONE, decoded.winner)
        assertEquals(listOf(Position(0), Position(1), Position(2)), decoded.winningLine)
    }

    @Test
    fun `round-trips a DRAWN state via threefold repetition`() {
        val engine = GameEngine()
        listOf(0, 1, 6, 3, 8, 7).forEach { engine.apply(Move.Place(Position(it))) }
        val shuttle = listOf(
            Move.Relocate(Position(0), Position(4)),
            Move.Relocate(Position(1), Position(2)),
            Move.Relocate(Position(4), Position(0)),
            Move.Relocate(Position(2), Position(1))
        )
        repeat(2) { shuttle.forEach { engine.apply(it) } }
        val decoded = GameStateCodec.decode(GameStateCodec.encode(engine.state))
        assertEquals(engine.state, decoded)
        assertEquals(GamePhase.DRAW, decoded.phase)
    }

    @Test
    fun `corrupt input decodes to a fresh state instead of crashing`() {
        assertEquals(GameEngine().state, GameStateCodec.decode("not;valid;data"))
        assertEquals(GameEngine().state, GameStateCodec.decode("garbage"))
    }
}
