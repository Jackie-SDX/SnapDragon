package com.threeseeds.engine

/** A board together with whose turn it is — the unit repetition-detection compares. */
data class BoardSnapshot(val board: Board, val toMove: Player)

data class GameState(
    val board: Board = Board(),
    val currentPlayer: Player = Player.ONE,
    val phase: GamePhase = GamePhase.PLACEMENT,
    val seedsRemaining: Map<Player, Int> = Player.entries.associateWith { SEEDS_PER_PLAYER },
    val winner: Player? = null,
    val winningLine: List<Position>? = null,
    // One entry per half-move played, plus the starting position. This
    // single list backs both undo (pop the last entry) and draw
    // detection (count how many times a snapshot has recurred).
    val history: List<BoardSnapshot> = listOf(BoardSnapshot(Board(), Player.ONE))
) {
    companion object {
        const val SEEDS_PER_PLAYER = 3
        const val REPETITIONS_FOR_DRAW = 3
    }
}
