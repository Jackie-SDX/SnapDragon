package com.threeseeds.app.state

import com.threeseeds.engine.Board
import com.threeseeds.engine.BoardSnapshot
import com.threeseeds.engine.GamePhase
import com.threeseeds.engine.GameState
import com.threeseeds.engine.Player
import com.threeseeds.engine.Position
import com.threeseeds.engine.WinDetector

/**
 * Compact, dependency-free serialization of [GameState] to a single
 * String, for saving into a SavedStateHandle across process death.
 * Rotation alone needs none of this — a ViewModel already survives
 * configuration changes on its own — this is specifically for the
 * rarer case of the OS killing the process while backgrounded.
 *
 * Deliberately avoids pulling in a serialization library for what's a
 * handful of small fields: one 10-character block per history entry
 * (9 board cells + whose turn was next), joined with ';'. Everything
 * else (phase, winner, seeds remaining) is derived from the last
 * entry rather than stored redundantly, using the same precedence
 * GameEngine.finishTurn() uses: win, then draw, then ongoing.
 */
object GameStateCodec {

    fun encode(state: GameState): String =
        state.history.joinToString(";") { encodeSnapshot(it) }

    fun decode(encoded: String): GameState {
        if (encoded.isBlank()) return GameState()

        val history = try {
            encoded.split(";").map { decodeSnapshot(it) }
        } catch (malformed: IllegalArgumentException) {
            return GameState() // corrupt save data -> start fresh rather than crash
        }
        val last = history.last()
        val remaining = Player.entries.associateWith { player ->
            GameState.SEEDS_PER_PLAYER - last.board.positionsOf(player).size
        }

        val possibleWinner = last.toMove.opponent()
        val winningLine = WinDetector.winningLineFor(last.board, possibleWinner)
        if (winningLine != null) {
            return GameState(
                board = last.board,
                currentPlayer = possibleWinner,
                phase = GamePhase.WON,
                seedsRemaining = remaining,
                winner = possibleWinner,
                winningLine = winningLine,
                history = history
            )
        }

        if (history.count { it == last } >= GameState.REPETITIONS_FOR_DRAW) {
            return GameState(
                board = last.board,
                currentPlayer = last.toMove,
                phase = GamePhase.DRAW,
                seedsRemaining = remaining,
                history = history
            )
        }

        val phase = if (remaining.values.any { it > 0 }) GamePhase.PLACEMENT else GamePhase.MOVEMENT
        return GameState(
            board = last.board,
            currentPlayer = last.toMove,
            phase = phase,
            seedsRemaining = remaining,
            history = history
        )
    }

    private fun encodeSnapshot(snapshot: BoardSnapshot): String {
        val boardPart = Position.ALL.joinToString("") { cellChar(snapshot.board[it]) }
        return boardPart + cellChar(snapshot.toMove)
    }

    private fun decodeSnapshot(text: String): BoardSnapshot {
        require(text.length == 10) { "Corrupt snapshot (expected 10 chars): $text" }
        var board = Board()
        text.take(9).forEachIndexed { i, c ->
            charToPlayer(c)?.let { board = board.placed(Position(i), it) }
        }
        val toMove = charToPlayer(text[9]) ?: throw IllegalArgumentException("Corrupt snapshot, no player to move: $text")
        return BoardSnapshot(board, toMove)
    }

    private fun cellChar(player: Player?): String = when (player) {
        null -> "."
        Player.ONE -> "1"
        Player.TWO -> "2"
    }

    private fun charToPlayer(c: Char): Player? = when (c) {
        '.' -> null
        '1' -> Player.ONE
        '2' -> Player.TWO
        else -> throw IllegalArgumentException("Bad cell character: $c")
    }
}
