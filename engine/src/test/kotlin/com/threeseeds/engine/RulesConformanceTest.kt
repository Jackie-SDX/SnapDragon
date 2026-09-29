package com.threeseeds.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hardcore conformance harness: the engine's movement rules are checked
 * against an INDEPENDENTLY-derived reference model — the reference
 * neighbours are generated from the eight winning lines (consecutive
 * points along each row / column / diagonal), not from AdjacencyGraph's
 * edge list. If the two ever disagree, this fails.
 *
 * Scope: every labelled 3-vs-3 board (C(9,3) * C(6,3) = 1680), both
 * players to move, all 9 origins — 30,240 assertions.
 */
class RulesConformanceTest {

    /** Reference adjacency, derived from the 8 lines only. */
    private val lines = listOf(
        intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
        intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
        intArrayOf(0, 4, 8), intArrayOf(2, 4, 6)
    )

    private val referenceNeighbors: Map<Int, Set<Int>> = run {
        val map = (0..8).associateWith { mutableSetOf<Int>() }
        for (line in lines) {
            for (i in 0 until line.size - 1) {
                map.getValue(line[i]).add(line[i + 1])
                map.getValue(line[i + 1]).add(line[i])
            }
        }
        map
    }

    private fun combinations(n: Int, k: Int): List<List<Int>> =
        when {
            k == 0 -> listOf(emptyList())
            n < k -> emptyList()
            else -> (0 until n).flatMap { i ->
                combinations(n - i - 1, k - 1).map { rest -> listOf(i) + rest.map { it + i + 1 } }
            }
        }

    private fun boardOf(p1: List<Int>, p2: List<Int>): Board {
        var board = Board()
        p1.forEach { board = board.placed(Position(it), Player.ONE) }
        p2.forEach { board = board.placed(Position(it), Player.TWO) }
        return board
    }

    @Test
    fun `engine legalDestinations match the reference model on every 3-vs-3 board`() {
        val combos = combinations(9, 3)
        var checked = 0
        for (p1 in combos) {
            val rest = (0..8).filter { it !in p1 }
            for (p2 in rest.combinations(3)) {
                val board = boardOf(p1, p2)
                for (player in Player.entries) {
                    val state = GameState(
                        board = board,
                        currentPlayer = player,
                        phase = GamePhase.MOVEMENT,
                        seedsRemaining = Player.entries.associateWith { 0 }
                    )
                    for (origin in 0..8) {
                        if (board[Position(origin)] != player) continue
                        val expected = referenceNeighbors.getValue(origin)
                            .filterTo(mutableSetOf()) { board[Position(it)] == null }
                            .map { Position(it) }
                            .toSet()
                        val actual = MoveValidator.legalDestinations(state, Position(origin), MovementRules.TAPATAN)
                        assertTrue(
                            expected == actual,
                            "Board ${render(board, player)} origin=$origin: expected=$expected actual=$actual"
                        )
                        // The default (standard) ruleset: every vacant point is reachable.
                        val freeActual = MoveValidator.legalDestinations(state, Position(origin), MovementRules.FREE)
                        val freeExpected = (0..8).filterTo(mutableSetOf()) { board[Position(it)] == null }
                            .map { Position(it) }.toSet()
                        assertTrue(
                            freeExpected == freeActual,
                            "FREE mode Board ${render(board, player)} origin=$origin: expected=$freeExpected actual=$freeActual"
                        )
                        checked++
                    }
                }
            }
        }
        assertTrue(checked > 10_000, "expected a full sweep, only checked $checked")
    }

    @Test
    fun `validateRelocation agrees with the reference model for every from-to pair`() {
        val combos = combinations(9, 3)
        var checked = 0
        for (p1 in combos) {
            val rest = (0..8).filter { it !in p1 }
            for (p2 in rest.combinations(3)) {
                val board = boardOf(p1, p2)
                for (player in Player.entries) {
                    val state = GameState(
                        board = board,
                        currentPlayer = player,
                        phase = GamePhase.MOVEMENT,
                        seedsRemaining = Player.entries.associateWith { 0 }
                    )
                    for (from in 0..8) {
                        for (to in 0..8) {
                            if (from == to) continue
                            val own = board[Position(from)] == player && board[Position(to)] == null
                            val refTapatan = own && to in referenceNeighbors.getValue(from)
                            val engineTapatan = MoveValidator.validateRelocation(
                                state, Position(from), Position(to), MovementRules.TAPATAN
                            ).isSuccess
                            assertTrue(
                                refTapatan == engineTapatan,
                                "TAPATAN Board ${render(board, player)} move $from->$to: reference=$refTapatan engine=$engineTapatan"
                            )
                            val engineFree = MoveValidator.validateRelocation(
                                state, Position(from), Position(to), MovementRules.FREE
                            ).isSuccess
                            assertTrue(
                                own == engineFree,
                                "FREE Board ${render(board, player)} move $from->$to: reference=$own engine=$engineFree"
                            )
                            checked++
                        }
                    }
                }
            }
        }
        assertTrue(checked > 240_000, "expected a full sweep, only checked $checked")
    }

    /**
     * Re-verifies the README's claim that a hard stalemate is impossible:
     * on every NON-TERMINAL 3-vs-3 board the player to move always has at
     * least one legal destination. (Terminal boards are excluded — the
     * game is already over there.)
     */
    @Test
    fun `no non-terminal 3-vs-3 board leaves the player to move without a legal move`() {
        val combos = combinations(9, 3)
        var nonTerminal = 0
        for (p1 in combos) {
            val rest = (0..8).filter { it !in p1 }
            for (p2 in rest.combinations(3)) {
                val board = boardOf(p1, p2)
                val terminal = Player.entries.any { WinDetector.winningLineFor(board, it) != null }
                if (terminal) continue
                nonTerminal++
                for (player in Player.entries) {
                    val hasMove = (0..8).any { origin ->
                        board[Position(origin)] == player && referenceNeighbors.getValue(origin)
                            .any { board[Position(it)] == null }
                    }
                    assertTrue(hasMove, "Stalemate found: ${render(board, player)} to move has no legal move")
                }
            }
        }
        assertTrue(nonTerminal > 1_000, "expected a full sweep, checked $nonTerminal boards")
    }

    /**
     * The reported scenario, pinned as explicit expectations so any
     * future rules change is a conscious one:
     *
     *   top row = [P1 seed][P2 seed][empty]  — P1 at 0 CANNOT reach 2:
     *   0 and 2 are not connected (the line runs 0-1-2), and 1 is
     *   occupied. Standard Tapatan / Three Men's Morris forbids jumping.
     *   Legal from 0: 3 (down) and 4 (diagonal).
     */
    @Test
    fun `a seed blocked along its own row cannot jump the blocker under adjacent-only rules`() {
        val board = Board()
            .placed(Position(0), Player.ONE)
            .placed(Position(1), Player.TWO)
            .placed(Position(2), Player.TWO)
        val state = GameState(
            board = board, currentPlayer = Player.ONE,
            phase = GamePhase.MOVEMENT, seedsRemaining = Player.entries.associateWith { 0 }
        )
        val dest = MoveValidator.legalDestinations(state, Position(0), MovementRules.TAPATAN)
        assertTrue(Position(3) in dest && Position(4) in dest, "down + diagonal must be offered: $dest")
        assertTrue(Position(1) !in dest, "occupied neighbour must not be offered")
        assertTrue(Position(2) !in dest, "jumping over the blocker must be refused")
        assertTrue(MoveValidator.validateRelocation(state, Position(0), Position(2), MovementRules.TAPATAN).isFailure)
    }

    /**
     * The same board under the DEFAULT ruleset (standard three men's
     * morris): the empty point behind the blocker must be reachable,
     * because a piece may move to any vacant point.
     */
    @Test
    fun `under standard rules the empty point beyond the blocker is reachable`() {
        // The reported shape: [P1 seed][P2 seed][empty] along the top row.
        val board = Board()
            .placed(Position(0), Player.ONE)
            .placed(Position(1), Player.TWO)
            .placed(Position(4), Player.TWO)
        val state = GameState(
            board = board, currentPlayer = Player.ONE,
            phase = GamePhase.MOVEMENT, seedsRemaining = Player.entries.associateWith { 0 }
        )
        val dest = MoveValidator.legalDestinations(state, Position(0), MovementRules.FREE)
        assertEquals(setOf(Position(2), Position(3), Position(5), Position(6), Position(7), Position(8)), dest)
        assertTrue(MoveValidator.validateRelocation(state, Position(0), Position(2)).isSuccess)
    }

    /** The mirror image of the report: target IS an adjacent empty point -> must be offered and accepted. */
    @Test
    fun `an adjacent empty point behind nothing is always movable even with seeds elsewhere`() {
        val board = Board()
            .placed(Position(1), Player.ONE)
            .placed(Position(2), Player.TWO)
        val state = GameState(
            board = board, currentPlayer = Player.ONE,
            phase = GamePhase.MOVEMENT, seedsRemaining = Player.entries.associateWith { 0 }
        )
        val dest = MoveValidator.legalDestinations(state, Position(1), MovementRules.TAPATAN)
        assertTrue(Position(0) in dest, "1 -> 0 is a drawn connection to an empty point: $dest")
        assertTrue(MoveValidator.validateRelocation(state, Position(1), Position(0)).isSuccess)
    }

    private fun render(board: Board, toMove: Player): String {
        val cells = (0..8).map { i ->
            when (board[Position(i)]) {
                Player.ONE -> "X"
                Player.TWO -> "O"
                null -> "."
            }
        }
        return "${cells[0]}${cells[1]}${cells[2]}/${cells[3]}${cells[4]}${cells[5]}/${cells[6]}${cells[7]}${cells[8]} ($toMove)"
    }

    private fun List<Int>.combinations(k: Int): List<List<Int>> = combinations(size, k).map { idx -> idx.map { this[it] } }
}
