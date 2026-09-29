package com.threeseeds.engine

/**
 * One of the 9 points on the board, identified by a stable index:
 *
 *   0 1 2
 *   3 4 5
 *   6 7 8
 *
 * A value class so it costs nothing at runtime but stops a raw Int
 * from being passed around where a board coordinate is meant.
 */
@JvmInline
value class Position(val index: Int) {
    init {
        require(index in 0..8) { "Position index must be 0-8, was $index" }
    }

    override fun toString(): String = "Position($index)"

    companion object {
        val ALL: List<Position> = (0..8).map { Position(it) }
    }
}
