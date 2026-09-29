package com.threeseeds.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BoardTest {

    @Test
    fun `a fresh board is entirely empty`() {
        val board = Board()
        for (p in Position.ALL) assertTrue(board.isEmpty(p))
    }

    @Test
    fun `placed occupies the target point and leaves the original board untouched`() {
        val original = Board()
        val next = original.placed(Position(4), Player.ONE)
        assertTrue(original.isEmpty(Position(4)), "original board must be unchanged")
        assertEquals(Player.ONE, next[Position(4)])
    }

    @Test
    fun `placed on an occupied point throws`() {
        val board = Board().placed(Position(0), Player.ONE)
        assertFailsWith<IllegalArgumentException> { board.placed(Position(0), Player.TWO) }
    }

    @Test
    fun `moved vacates the source and occupies the destination`() {
        val board = Board().placed(Position(0), Player.ONE)
        val moved = board.moved(Position(0), Position(1))
        assertTrue(moved.isEmpty(Position(0)))
        assertEquals(Player.ONE, moved[Position(1)])
    }

    @Test
    fun `moved from an empty point throws`() {
        assertFailsWith<IllegalArgumentException> { Board().moved(Position(0), Position(1)) }
    }

    @Test
    fun `moved onto an occupied point throws`() {
        var board = Board()
        board = board.placed(Position(0), Player.ONE)
        board = board.placed(Position(1), Player.TWO)
        assertFailsWith<IllegalArgumentException> { board.moved(Position(0), Position(1)) }
    }

    @Test
    fun `positionsOf returns only that player's occupied points, in board order`() {
        var board = Board()
        board = board.placed(Position(5), Player.ONE)
        board = board.placed(Position(1), Player.TWO)
        board = board.placed(Position(2), Player.ONE)
        assertEquals(listOf(Position(2), Position(5)), board.positionsOf(Player.ONE))
    }

    @Test
    fun `emptyPositions returns every unoccupied point`() {
        val board = Board().placed(Position(0), Player.ONE).placed(Position(8), Player.TWO)
        assertEquals(7, board.emptyPositions().size)
        assertTrue(Position(0) !in board.emptyPositions())
    }
}
