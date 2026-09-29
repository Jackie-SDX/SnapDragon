package com.threeseeds.engine

data class MoveResult(val state: GameState, val events: List<GameEvent>)

/**
 * The single authoritative entry point for all game logic. No other
 * class mutates game state, and the UI never applies a move without
 * going through here — the same discipline a future networked client
 * will need, since server-side validation can reuse this class as-is.
 *
 * Stalemate: under either movement ruleset a player can always find a
 * legal move on a 3-vs-3 board (exhaustively verified in the test
 * suite), so there is deliberately no "no legal moves, skip turn" branch.
 */
class GameEngine(
    initialState: GameState = GameState(),
    movementRules: MovementRules = MovementRules.FREE
) {

    /** Which ruleset relocations are validated against; the UI can switch it mid-session. */
    var movementRules: MovementRules = movementRules

    var state: GameState = initialState
        private set

    /** The only way any move reaches the board. Always returns a result, even when rejected. */
    fun apply(move: Move): MoveResult {
        val result = when (move) {
            is Move.Place -> place(move.position)
            is Move.Relocate -> relocate(move.from, move.to)
        }
        state = result.state
        return result
    }

    /** For UI highlighting: where the seed at [from] may legally move to right now. */
    fun legalDestinations(from: Position): Set<Position> =
        MoveValidator.legalDestinations(state, from, movementRules)

    fun undo(): MoveResult {
        if (state.history.size <= 1) {
            return MoveResult(state, listOf(GameEvent.MoveRejected("Nothing to undo")))
        }
        val newHistory = state.history.dropLast(1)
        val restored = rebuild(newHistory.last(), newHistory)
        state = restored
        return MoveResult(restored, emptyList())
    }

    fun reset(): MoveResult {
        state = GameState()
        return MoveResult(state, emptyList())
    }

    private fun place(position: Position): MoveResult {
        MoveValidator.validatePlacement(state, position).exceptionOrNull()?.let {
            return MoveResult(state, listOf(GameEvent.MoveRejected(it.message ?: "Invalid placement")))
        }
        val player = state.currentPlayer
        val newBoard = state.board.placed(position, player)
        val newRemaining = state.seedsRemaining.toMutableMap().apply {
            this[player] = getValue(player) - 1
        }
        return finishTurn(newBoard, newRemaining, mutableListOf(GameEvent.SeedPlaced(position, player)))
    }

    private fun relocate(from: Position, to: Position): MoveResult {
        MoveValidator.validateRelocation(state, from, to, movementRules).exceptionOrNull()?.let {
            return MoveResult(state, listOf(GameEvent.MoveRejected(it.message ?: "Invalid move")))
        }
        val player = state.currentPlayer
        val newBoard = state.board.moved(from, to)
        return finishTurn(newBoard, state.seedsRemaining, mutableListOf(GameEvent.SeedMoved(from, to, player)))
    }

    /**
     * Shared tail end of place()/relocate(): checks for a win, then for a
     * repetition draw, then otherwise advances the turn. History always
     * gets a new entry here — including on a win — so undo steps back
     * exactly one ply no matter how that ply ended.
     */
    private fun finishTurn(
        newBoard: Board,
        newRemaining: Map<Player, Int>,
        events: MutableList<GameEvent>
    ): MoveResult {
        val mover = state.currentPlayer
        val winningLine = WinDetector.winningLineFor(newBoard, mover)
        val nextPlayer = mover.opponent()
        val snapshot = BoardSnapshot(newBoard, nextPlayer)
        val newHistory = state.history + snapshot

        if (winningLine != null) {
            events += GameEvent.Won(mover, winningLine)
            return MoveResult(
                state.copy(
                    board = newBoard,
                    seedsRemaining = newRemaining,
                    phase = GamePhase.WON,
                    winner = mover,
                    winningLine = winningLine,
                    history = newHistory
                ),
                events
            )
        }

        if (newHistory.count { it == snapshot } >= GameState.REPETITIONS_FOR_DRAW) {
            events += GameEvent.Drawn
            return MoveResult(
                state.copy(
                    board = newBoard,
                    currentPlayer = nextPlayer,
                    seedsRemaining = newRemaining,
                    phase = GamePhase.DRAW,
                    history = newHistory
                ),
                events
            )
        }

        val ongoingPhase = if (newRemaining.values.all { it == 0 }) GamePhase.MOVEMENT else state.phase
        return MoveResult(
            state.copy(
                board = newBoard,
                currentPlayer = nextPlayer,
                seedsRemaining = newRemaining,
                phase = ongoingPhase,
                history = newHistory
            ),
            events
        )
    }

    /** Recomputes derived fields (phase, seeds remaining) from a restored board — used by undo(). */
    private fun rebuild(snapshot: BoardSnapshot, history: List<BoardSnapshot>): GameState {
        val remaining = Player.entries.associateWith { player ->
            GameState.SEEDS_PER_PLAYER - snapshot.board.positionsOf(player).size
        }
        val phase = if (remaining.values.any { it > 0 }) GamePhase.PLACEMENT else GamePhase.MOVEMENT
        return GameState(
            board = snapshot.board,
            currentPlayer = snapshot.toMove,
            phase = phase,
            seedsRemaining = remaining,
            history = history
        )
    }
}
