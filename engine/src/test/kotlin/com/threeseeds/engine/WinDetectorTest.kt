package com.threeseeds.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WinDetectorTest {

    private fun boardWith(vararg positions: Int, player: Player = Player.ONE): Board {
        var board = Board()
        for (p in positions) board = board.placed(Position(p), player)
        return board
    }

    @Test
    fun `there are exactly 8 winning lines`() {
        assertEquals(8, WinDetector.WINNING_LINES.size)
    }

    @Test
    fun `top row is a win`() {
        assertEquals(listOf(Position(0), Position(1), Position(2)), WinDetector.winningLineFor(boardWith(0, 1, 2), Player.ONE))
    }

    @Test
    fun `middle row is a win`() {
        assertEquals(listOf(Position(3), Position(4), Position(5)), WinDetector.winningLineFor(boardWith(3, 4, 5), Player.ONE))
    }

    @Test
    fun `bottom row is a win`() {
        assertEquals(listOf(Position(6), Position(7), Position(8)), WinDetector.winningLineFor(boardWith(6, 7, 8), Player.ONE))
    }

    @Test
    fun `left column is a win`() {
        assertEquals(listOf(Position(0), Position(3), Position(6)), WinDetector.winningLineFor(boardWith(0, 3, 6), Player.ONE))
    }

    @Test
    fun `middle column is a win`() {
        assertEquals(listOf(Position(1), Position(4), Position(7)), WinDetector.winningLineFor(boardWith(1, 4, 7), Player.ONE))
    }

    @Test
    fun `right column is a win`() {
        assertEquals(listOf(Position(2), Position(5), Position(8)), WinDetector.winningLineFor(boardWith(2, 5, 8), Player.ONE))
    }

    @Test
    fun `main diagonal is a win`() {
        assertEquals(listOf(Position(0), Position(4), Position(8)), WinDetector.winningLineFor(boardWith(0, 4, 8), Player.ONE))
    }

    @Test
    fun `anti-diagonal is a win`() {
        assertEquals(listOf(Position(2), Position(4), Position(6)), WinDetector.winningLineFor(boardWith(2, 4, 6), Player.ONE))
    }

    @Test
    fun `every winning line is detected generically, not just the ones spelled out above`() {
        for (line in WinDetector.WINNING_LINES) {
            val board = boardWith(*line.map { it.index }.toIntArray())
            assertEquals(line, WinDetector.winningLineFor(board, Player.ONE), "expected $line to be detected")
        }
    }

    @Test
    fun `a partially filled line is not a win`() {
        assertNull(WinDetector.winningLineFor(boardWith(0, 1), Player.ONE))
    }

    @Test
    fun `a line split between two players is not a win for either`() {
        var board = Board()
        board = board.placed(Position(0), Player.ONE)
        board = board.placed(Position(1), Player.TWO)
        board = board.placed(Position(2), Player.ONE)
        assertNull(WinDetector.winningLineFor(board, Player.ONE))
        assertNull(WinDetector.winningLineFor(board, Player.TWO))
    }

    @Test
    fun `three occupied points that are not one of the 8 lines is not a win`() {
        assertNull(WinDetector.winningLineFor(boardWith(0, 1, 3), Player.ONE))
    }
}
