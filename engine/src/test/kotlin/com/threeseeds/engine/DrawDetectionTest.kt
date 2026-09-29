package com.threeseeds.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DrawDetectionTest {

    @Test
    fun `threefold repetition of the same position with the same player to move ends in a draw`() {
        val engine = GameEngine()
        listOf(0, 1, 6, 3, 8, 7).forEach { engine.apply(Move.Place(Position(it))) }
        // P1: 0,6,8   P2: 1,3,7   empty: 2,4,5 — recorded as occurrence #1 when placement finished.
        assertEquals(GamePhase.MOVEMENT, engine.state.phase)

        val shuttle = listOf(
            Move.Relocate(Position(0), Position(4)),
            Move.Relocate(Position(1), Position(2)),
            Move.Relocate(Position(4), Position(0)),
            Move.Relocate(Position(2), Position(1))
        )

        shuttle.forEach { engine.apply(it) } // back to the exact same position — occurrence #2
        assertEquals(GamePhase.MOVEMENT, engine.state.phase, "should still be playable after only 2 occurrences")

        shuttle.dropLast(1).forEach { engine.apply(it) }
        val result = engine.apply(shuttle.last()) // occurrence #3

        assertEquals(GamePhase.DRAW, engine.state.phase)
        assertTrue(result.events.any { it is GameEvent.Drawn })
    }

    @Test
    fun `no reachable 3-vs-3 configuration ever leaves the player to move with zero legal destinations`() {
        // Exhaustively re-checks the finding the adjacency model was designed
        // around: a hard stalemate is structurally impossible here, because
        // fully blocking any 3-seed cluster would need more than 3 opposing
        // seeds. If AdjacencyGraph is ever edited in a way that breaks this,
        // this test catches it immediately rather than shipping a game that
        // can silently lock up.
        fun triples(from: List<Position>): List<List<Position>> {
            val result = mutableListOf<List<Position>>()
            for (i in from.indices) for (j in i + 1 until from.size) for (k in j + 1 until from.size) {
                result.add(listOf(from[i], from[j], from[k]))
            }
            return result
        }

        var checked = 0
        for (p1 in triples(Position.ALL)) {
            val remaining = Position.ALL.filter { it !in p1 }
            for (p2 in triples(remaining)) {
                var board = Board()
                p1.forEach { board = board.placed(it, Player.ONE) }
                p2.forEach { board = board.placed(it, Player.TWO) }

                val alreadyDecided = WinDetector.winningLineFor(board, Player.ONE) != null ||
                    WinDetector.winningLineFor(board, Player.TWO) != null
                if (alreadyDecided) continue
                checked++

                for (player in Player.entries) {
                    val hasMove = board.positionsOf(player).any { seed ->
                        AdjacencyGraph.neighborsOf(seed).any { board.isEmpty(it) }
                    }
                    assertTrue(hasMove, "player $player has no legal move on board $board")
                }
            }
        }
        assertEquals(1372, checked, "sanity check on how many non-terminal configurations exist")
    }
}
