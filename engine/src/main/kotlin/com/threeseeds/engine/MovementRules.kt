package com.threeseeds.engine

/**
 * The two published rulesets for this game family. The default is FREE,
 * the primary rule for three men's morris: "A piece may move to any
 * vacant point on the board, not just an adjacent one."
 * (Wikipedia, Three men's morris — the standard rule.)
 *
 * TAPATAN is H. J. R. Murray's restricted variant, where a piece may
 * only slide along a drawn line to an immediately adjacent empty point.
 * It is offered as a stricter toggle, never the default.
 */
enum class MovementRules {
    /** Any vacant point may be moved to (standard rule). */
    FREE,

    /** Only points connected by a drawn line may be moved to (Murray variant). */
    TAPATAN;

    fun allows(from: Position, to: Position): Boolean = when (this) {
        FREE -> true
        TAPATAN -> AdjacencyGraph.areConnected(from, to)
    }
}
