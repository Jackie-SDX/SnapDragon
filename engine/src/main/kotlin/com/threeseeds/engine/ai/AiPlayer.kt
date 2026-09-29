package com.threeseeds.engine.ai

import com.threeseeds.engine.GameEngine
import com.threeseeds.engine.GamePhase
import com.threeseeds.engine.GameState
import com.threeseeds.engine.Move
import com.threeseeds.engine.MoveValidator
import com.threeseeds.engine.MovementRules
import com.threeseeds.engine.Position
import com.threeseeds.engine.WinDetector
import kotlin.math.abs
import kotlin.random.Random

/**
 * A real, deterministic, fully on-device game-playing AI.
 *
 * Rules never get a second implementation here: every candidate move
 * is executed by [GameEngine] itself and rolled back with
 * [GameEngine.undo], so the AI physically cannot produce a move the
 * rules would reject — the same discipline a networked client needs.
 *
 * The search is iterative-deepening negamax with alpha-beta pruning,
 * static move ordering (immediate wins first, then centre-ward), and
 * a wall-clock budget so no tier can ever hang the UI thread. State
 * carries its own history, so threefold-repetition draws are scored
 * exactly the way the engine scores them instead of assuming
 * board + side-to-move is the whole position.
 *
 * No transposition table on purpose: a memo keyed on the board alone
 * would be wrong whenever the same position is reached with different
 * repetition history, and correctness beats speed on a 3x3 board.
 */
object AiPlayer {

    /** Score of a won game, minus ply, so faster wins are preferred. */
    const val WIN_SCORE = 100_000


    /**
     * Picks a move for the player to move in [state].
     * Returns null when the game is already over or no legal move exists.
     *
     * Fully deterministic for a given (state, rules, difficulty,
     * personality, seed) — tests rely on it.
     */
    fun chooseMove(
        state: GameState,
        rules: MovementRules = MovementRules.FREE,
        difficulty: AiDifficulty = AiDifficulty.MEDIUM,
        personality: AiPersonality = AiPersonality.BALANCED,
        seed: Long = 0L,
        timeBudgetMs: Long = difficulty.timeBudgetMs,
    ): Move? {
        if (state.phase == GamePhase.WON || state.phase == GamePhase.DRAW) return null
        val rootMoves = legalMoves(state, rules)
        if (rootMoves.isEmpty()) return null
        if (rootMoves.size == 1) return rootMoves.first()

        val deadline = if (timeBudgetMs > 0) {
            System.nanoTime() + timeBudgetMs * 1_000_000
        } else {
            Long.MAX_VALUE
        }
        val scored = Searcher(GameEngine(state, rules), personality, deadline)
            .searchAllDepths(state, rootMoves, difficulty.searchDepth)

        // Personality-scaled noise on the final scores; weak tiers vary.
        val noise = (difficulty.noise * personality.noiseScale).toInt()
        val rng = Random(seed)
        val adjusted = scored.map { (move, score) ->
            move to (if (noise > 0) score + rng.nextInt(noise * 2 + 1) - noise else score)
        }
        val best = adjusted.maxOf { it.second }
        val tied = adjusted.filter { it.second == best }.map { it.first }
        return tied[rng.nextInt(tied.size)]
    }

    /** Every move the current rules allow — placement or movement phase. */
    internal fun legalMoves(state: GameState, rules: MovementRules): List<Move> = when (state.phase) {
        GamePhase.PLACEMENT -> state.board.emptyPositions().map { Move.Place(it) }

        GamePhase.MOVEMENT -> state.board.positionsOf(state.currentPlayer).flatMap { from ->
            MoveValidator.legalDestinations(state, from, rules).map { Move.Relocate(from, it) }
        }

        else -> emptyList()
    }
}

/** Thrown internally when the wall-clock budget runs out; never escapes AiPlayer. */
private class SearchTimeout : RuntimeException(null, null, false, false)

private class Searcher(
    /** Shared engine: apply() before recursing, undo() after — one rules authority. */
    private val engine: GameEngine,
    private val personality: AiPersonality,
    private val deadlineNanos: Long,
) {
    private var nodes = 0L

    /**
     * Runs iterative deepening from depth 1 up to [maxDepth], returning
     * per-move scores from the last iteration that completed in full.
     * A timed-out iteration is discarded so partial scores never win.
     */
    fun searchAllDepths(
        root: GameState,
        moves: List<Move>,
        maxDepth: Int,
    ): List<Pair<Move, Int>> {
        var order = moves
        var lastComplete: List<Pair<Move, Int>>? = null

        for (depth in 1..maxDepth) {
            val results = ArrayList<Pair<Move, Int>>(order.size)
            var timedOut = false

            for (move in order) {
                if (System.nanoTime() > deadlineNanos) {
                    timedOut = true
                    break
                }
                engine.apply(move)
                // Full window per root move: narrowing it here would let a
                // fail-high cutoff masquerade as an exact score, and a forced
                // win would then falsely TIE (or beat) the real best move.
                // Inside the subtree alpha-beta still prunes normally.
                val score = try {
                    val child = engine.state
                    when {
                        // Terminal children keep the mover as the side "to move",
                        // so the usual negamax negation must not be applied.
                        child.phase == GamePhase.WON -> AiPlayer.WIN_SCORE - 1
                        child.phase == GamePhase.DRAW -> 0
                        else -> -negamax(
                            depth - 1,
                            -AiPlayer.WIN_SCORE * 10,
                            AiPlayer.WIN_SCORE * 10,
                            1
                        )
                    }
                } catch (timeout: SearchTimeout) {
                    timedOut = true
                    Int.MIN_VALUE / 4
                } finally {
                    engine.undo()
                }
                if (timedOut) break
                results += move to score
            }

            if (timedOut || results.size < order.size) break
            lastComplete = results
            order = results.sortedByDescending { it.second }.map { it.first }
        }

        // Engine must be back at the root for the caller's next use.
        check(engine.state == root) { "search left the engine off the root state" }
        return lastComplete ?: moves.map { it to 0 }
    }

    private fun negamax(depth: Int, alpha0: Int, beta0: Int, ply: Int): Int {
        nodes++
        if (nodes and 1023L == 0L && System.nanoTime() > deadlineNanos) throw SearchTimeout()

        val state = engine.state
        when (state.phase) {
            GamePhase.WON -> return if (state.winner == state.currentPlayer) {
                AiPlayer.WIN_SCORE - ply
            } else {
                -(AiPlayer.WIN_SCORE - ply)
            }

            GamePhase.DRAW -> return 0
            else -> Unit
        }
        if (depth <= 0) return evaluate(state)

        var alpha = alpha0
        for (move in orderedMoves(state)) {
            engine.apply(move)
            // finally, not after-the-fact: a SearchTimeout thrown deep in
            // the recursion must still roll back every ply it entered.
            val value = try {
                val child = engine.state
                when {
                    child.phase == GamePhase.WON -> AiPlayer.WIN_SCORE - ply
                    child.phase == GamePhase.DRAW -> 0
                    else -> -negamax(depth - 1, -beta0, -alpha, ply + 1)
                }
            } finally {
                engine.undo()
            }

            if (value >= beta0) return value
            if (value > alpha) alpha = value
        }
        return alpha
    }

    private fun orderedMoves(state: GameState): List<Move> =
        AiPlayer.legalMoves(state, rules = movementRulesOf()).sortedBy { moveOrderKey(state, it) }

    private fun movementRulesOf(): MovementRules = engine.movementRules

    /** Immediate wins first, then centre-ward: helps alpha-beta a lot. */
    private fun moveOrderKey(state: GameState, move: Move): Int {
        val mover = state.currentPlayer
        val boardAfter = when (move) {
            is Move.Place -> state.board.placed(move.position, mover)
            is Move.Relocate -> state.board.moved(move.from, move.to)
        }
        val winsNow = WinDetector.winningLineFor(boardAfter, mover) != null
        val destination = when (move) {
            is Move.Place -> move.position
            is Move.Relocate -> move.to
        }
        return if (winsNow) 0 else 10 + centreRank(destination)
    }

    private fun centreRank(position: Position): Int {
        val row = position.index / 3
        val col = position.index % 3
        return maxOf(abs(row - 1), abs(col - 1)) // 0 centre, 1 edges, 2 corners
    }

    /** Static evaluation for [GameState.currentPlayer]. */
    private fun evaluate(state: GameState): Int {
        val me = state.currentPlayer
        val opp = me.opponent()
        val p = personality
        var score = 0

        for (line in WinDetector.WINNING_LINES) {
            var mine = 0
            var theirs = 0
            for (position in line) {
                val occupant = state.board[position]
                if (occupant == me) mine++ else if (occupant == opp) theirs++
            }
            when {
                theirs == 0 && mine == 2 -> score += (THREAT * p.threatWeight).toInt()
                theirs == 0 && mine == 1 -> score += PAIR
                mine == 0 && theirs == 2 -> score -= (THREAT * p.blockWeight).toInt()
                mine == 0 && theirs == 1 -> score -= PAIR
            }
        }

        val centre = state.board[Position(4)]
        if (centre == me) {
            score += (CENTRE * p.centerWeight).toInt()
        } else if (centre == opp) {
            score -= (CENTRE * p.centerWeight).toInt()
        }

        if (state.phase == GamePhase.MOVEMENT) {
            score += (AiPlayer.legalMoves(state, engine.movementRules).size * MOBILITY * p.mobilityWeight).toInt()
        }
        return score
    }

    private companion object {
        const val THREAT = 70 // two seeds in a line, third still open
        const val PAIR = 10
        const val CENTRE = 14
        const val MOBILITY = 3
    }
}
