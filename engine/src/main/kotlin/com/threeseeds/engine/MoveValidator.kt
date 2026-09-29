package com.threeseeds.engine

class IllegalMoveException(message: String) : Exception(message)

object MoveValidator {

    fun validatePlacement(state: GameState, position: Position): Result<Unit> {
        if (state.phase != GamePhase.PLACEMENT) {
            return Result.failure(IllegalMoveException("Not in the placement phase"))
        }
        if (!state.board.isEmpty(position)) {
            return Result.failure(IllegalMoveException("$position is already occupied"))
        }
        if ((state.seedsRemaining[state.currentPlayer] ?: 0) <= 0) {
            return Result.failure(IllegalMoveException("${state.currentPlayer} has no seeds left to place"))
        }
        return Result.success(Unit)
    }

    fun validateRelocation(
        state: GameState,
        from: Position,
        to: Position,
        rules: MovementRules = MovementRules.FREE
    ): Result<Unit> {
        if (state.phase != GamePhase.MOVEMENT) {
            return Result.failure(IllegalMoveException("Not in the movement phase"))
        }
        if (!state.board.isOccupiedBy(from, state.currentPlayer)) {
            return Result.failure(IllegalMoveException("$from is not ${state.currentPlayer}'s seed"))
        }
        if (!state.board.isEmpty(to)) {
            return Result.failure(IllegalMoveException("$to is already occupied"))
        }
        if (!rules.allows(from, to)) {
            return Result.failure(IllegalMoveException("$from and $to are not connected"))
        }
        return Result.success(Unit)
    }

    /** Empty points a seed at [from] may legally move to right now — used for UI highlighting. */
    fun legalDestinations(
        state: GameState,
        from: Position,
        rules: MovementRules = MovementRules.FREE
    ): Set<Position> {
        if (state.phase != GamePhase.MOVEMENT) return emptySet()
        if (!state.board.isOccupiedBy(from, state.currentPlayer)) return emptySet()
        val empties = state.board.emptyPositions()
        if (rules == MovementRules.FREE) return empties.toSet()
        return empties.filterTo(mutableSetOf()) { rules.allows(from, it) }
    }
}
