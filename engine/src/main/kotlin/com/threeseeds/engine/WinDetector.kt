package com.threeseeds.engine

/**
 * The 8 fixed winning lines, defined once as data — nothing else in the
 * engine hard-codes a scattered "check the top row" style condition.
 */
object WinDetector {

    val WINNING_LINES: List<List<Position>> = listOf(
        listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8), // rows
        listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8), // columns
        listOf(0, 4, 8), listOf(2, 4, 6)                    // diagonals
    ).map { line -> line.map { Position(it) } }

    /** Returns the actual winning positions, or null if [player] hasn't won on [board]. */
    fun winningLineFor(board: Board, player: Player): List<Position>? =
        WINNING_LINES.firstOrNull { line -> line.all { board[it] == player } }
}
