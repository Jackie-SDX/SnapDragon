package com.threeseeds.engine

/**
 * Immutable snapshot of the 9 points. A null cell is empty.
 * Every mutation returns a new Board rather than changing this one, so
 * a GameState's history can hold cheap, safe references to past boards.
 */
data class Board(private val cells: List<Player?> = List(9) { null }) {

    operator fun get(position: Position): Player? = cells[position.index]

    fun isEmpty(position: Position): Boolean = this[position] == null

    fun isOccupiedBy(position: Position, player: Player): Boolean = this[position] == player

    fun emptyPositions(): List<Position> = Position.ALL.filter { isEmpty(it) }

    fun positionsOf(player: Player): List<Position> = Position.ALL.filter { this[it] == player }

    fun placed(position: Position, player: Player): Board {
        require(isEmpty(position)) { "Cannot place on occupied position $position" }
        return Board(cells.toMutableList().also { it[position.index] = player })
    }

    fun moved(from: Position, to: Position): Board {
        val mover = this[from]
        requireNotNull(mover) { "No seed at $from to move" }
        require(isEmpty(to)) { "Cannot move to occupied position $to" }
        return Board(cells.toMutableList().also {
            it[from.index] = null
            it[to.index] = mover
        })
    }
}
