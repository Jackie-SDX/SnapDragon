package com.threeseeds.engine

/**
 * Standard Three Men's Morris / Tapatan board connections: the 3x3 grid
 * lines plus both diagonals through the center point (index 4).
 *
 * This table does two jobs: it decides which connector lines the board
 * UI draws, and — under [MovementRules.TAPATAN] only — it constrains a
 * seed to move along one of these drawn connections, to a directly
 * adjacent, currently empty point. The default [MovementRules.FREE]
 * mode ignores it for legality: any vacant point is a legal destination.
 *
 * To change the connection model later (e.g. drop the diagonals for a
 * simpler variant), edit only the EDGES list below. Nothing else in the
 * engine assumes a specific board topology.
 */
object AdjacencyGraph {

    private val EDGES: List<Pair<Int, Int>> = listOf(
        0 to 1, 1 to 2,   // top row
        3 to 4, 4 to 5,   // middle row
        6 to 7, 7 to 8,   // bottom row
        0 to 3, 3 to 6,   // left column
        1 to 4, 4 to 7,   // middle column
        2 to 5, 5 to 8,   // right column
        0 to 4, 4 to 8,   // main diagonal
        2 to 4, 4 to 6    // anti-diagonal
    )

    private val CONNECTIONS: Map<Position, Set<Position>> = run {
        val map = Position.ALL.associateWith { mutableSetOf<Position>() }
        EDGES.forEach { (a, b) ->
            map.getValue(Position(a)).add(Position(b))
            map.getValue(Position(b)).add(Position(a))
        }
        map
    }

    fun neighborsOf(position: Position): Set<Position> = CONNECTIONS.getValue(position)

    fun areConnected(a: Position, b: Position): Boolean = b in neighborsOf(a)
}
