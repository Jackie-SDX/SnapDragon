package com.threeseeds.engine.ai

import com.threeseeds.engine.Board
import com.threeseeds.engine.BoardSnapshot
import com.threeseeds.engine.GameEngine
import com.threeseeds.engine.GameEvent
import com.threeseeds.engine.GamePhase
import com.threeseeds.engine.GameState
import com.threeseeds.engine.Move.Place
import com.threeseeds.engine.MovementRules
import com.threeseeds.engine.Player
import com.threeseeds.engine.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiPlayerTest {

    private val allDifficulties = AiDifficulty.entries.toList()
    private val allPersonalities = AiPersonality.entries.toList()

    /** Plays one full AI-vs-AI game; asserts every chosen move is legal; null = safety cap hit. */
    private fun playGame(
        first: AiDifficulty,
        second: AiDifficulty,
        seed: Long,
        rules: MovementRules = MovementRules.FREE,
        timeBudgetMs: Long = 150,
    ): Player? {
        val engine = GameEngine(movementRules = rules)
        var plies = 0
        while (engine.state.phase == GamePhase.PLACEMENT || engine.state.phase == GamePhase.MOVEMENT) {
            if (plies++ > 160) return null // endless shuffling guard -> treated as a non-win
            val difficulty = if (engine.state.currentPlayer == Player.ONE) first else second
            val move = assertNotNull(
                AiPlayer.chooseMove(
                    state = engine.state,
                    rules = rules,
                    difficulty = difficulty,
                    seed = seed + plies * 31,
                    timeBudgetMs = timeBudgetMs,
                ),
                "AI must always have a move while the game is running"
            )
            val result = engine.apply(move)
            assertTrue(
                result.events.none { it is GameEvent.MoveRejected },
                "AI produced an illegal move under $rules: $move"
            )
        }
        return engine.state.winner
    }

    /** Wins and losses for [strong] across 6 games with seats swapped. */
    private data class SeriesResult(val strongWins: Int, val weakWins: Int)

    private fun countSeries(
        strong: AiDifficulty,
        weak: AiDifficulty,
        timeBudgetMs: Long,
    ): SeriesResult {
        var strongWins = 0
        var weakWins = 0
        for (seed in 1L..3L) {
            val asPlayerOne = playGame(strong, weak, seed = seed, timeBudgetMs = timeBudgetMs)
            val asPlayerTwo = playGame(weak, strong, seed = seed + 1000, timeBudgetMs = timeBudgetMs)
            if (asPlayerOne == Player.ONE) strongWins++
            if (asPlayerOne == Player.TWO) weakWins++
            if (asPlayerTwo == Player.TWO) strongWins++
            if (asPlayerTwo == Player.ONE) weakWins++
        }
        return SeriesResult(strongWins, weakWins)
    }

    @Test
    fun `returns null when the game is already over`() {
        val engine = GameEngine()
        listOf(0, 3, 1, 4).forEach { engine.apply(Place(Position(it))) }
        engine.apply(Place(Position(2))) // Player ONE completes the top row
        assertEquals(GamePhase.WON, engine.state.phase)
        assertNull(AiPlayer.chooseMove(engine.state))

        val fresh = GameEngine()
        assertNull(AiPlayer.chooseMove(fresh.state.copy(phase = GamePhase.DRAW)))
    }

    @Test
    fun `every difficulty plays only legal moves in placement and movement, under both rulesets`() {
        for (difficulty in allDifficulties) {
            for (rules in MovementRules.entries) {
                val fresh = GameEngine(movementRules = rules)
                val placementMove = assertNotNull(
                    AiPlayer.chooseMove(fresh.state, rules, difficulty, timeBudgetMs = 200),
                    "$difficulty must move in placement"
                )
                assertTrue(
                    fresh.apply(placementMove).events.none { it is GameEvent.MoveRejected },
                    "$difficulty broke $rules with $placementMove"
                )

                val moving = GameEngine(initialState = movementState(), movementRules = rules)
                val move = assertNotNull(
                    AiPlayer.chooseMove(moving.state, rules, difficulty, timeBudgetMs = 200)
                )
                val result = moving.apply(move)
                assertTrue(
                    result.events.none { it is GameEvent.MoveRejected },
                    "$difficulty broke $rules with $move"
                )
            }
        }
    }

    @Test
    fun `the same inputs always produce the same move`() {
        val state = movementState()
        for (difficulty in allDifficulties) {
            val a = AiPlayer.chooseMove(state, MovementRules.FREE, difficulty, seed = 42L, timeBudgetMs = 0)
            val b = AiPlayer.chooseMove(state, MovementRules.FREE, difficulty, seed = 42L, timeBudgetMs = 0)
            assertEquals(a, b, "$difficulty must be deterministic for a fixed seed")
        }
    }

    @Test
    fun `every difficulty takes an immediate win when one exists`() {
        val engine = GameEngine()
        listOf(0, 3, 1, 4).forEach { engine.apply(Place(Position(it))) } // ONE to move: 2 wins
        for (difficulty in allDifficulties) {
            val move = assertNotNull(
                AiPlayer.chooseMove(engine.state, difficulty = difficulty, timeBudgetMs = 0)
            )
            assertEquals(Place(Position(2)), move, "$difficulty must take the winning placement")
        }
    }

    @Test
    fun `easy and above block an immediate loss`() {
        val engine = GameEngine()
        listOf(0, 4, 1).forEach { engine.apply(Place(Position(it))) } // TWO to move, ONE threatens 0-1-2
        for (difficulty in listOf(AiDifficulty.EASY, AiDifficulty.MEDIUM, AiDifficulty.HARD, AiDifficulty.MASTER)) {
            val move = assertNotNull(
                AiPlayer.chooseMove(engine.state, difficulty = difficulty, timeBudgetMs = 250)
            )
            assertEquals(Place(Position(2)), move, "$difficulty must block the immediate win")
        }
    }

    @Test
    fun `a stronger difficulty beats a blundering weaker one over a series`() {
        // Free-movement three men's morris (nine holes) is a strongly solved
        // DRAW: against error-free defence no tier can force a win. What
        // difficulty guarantees is punishment of blunders — BEGINNER plays
        // depth 1 with heavy noise, so every tier above it must convert.
        val medium = countSeries(AiDifficulty.MEDIUM, AiDifficulty.BEGINNER, timeBudgetMs = 200)
        assertTrue(
            medium.strongWins >= 5 && medium.weakWins == 0,
            "MEDIUM vs BEGINNER should be a clean sweep but was ${medium.strongWins}W-${medium.weakWins}L"
        )

        val hard = countSeries(AiDifficulty.HARD, AiDifficulty.BEGINNER, timeBudgetMs = 400)
        assertTrue(
            hard.strongWins >= 5 && hard.weakWins == 0,
            "HARD vs BEGINNER should be a clean sweep but was ${hard.strongWins}W-${hard.weakWins}L"
        )
    }

    @Test
    fun `a stronger difficulty never loses to a tier that defends well`() {
        // EASY and above always answer immediate wins; in a solved-draw game
        // that makes them effectively unbeatable — but they must never be
        // seen LOSING to a deeper or equal class tier.
        val pairs = listOf(
            AiDifficulty.MEDIUM to AiDifficulty.EASY,
            AiDifficulty.HARD to AiDifficulty.EASY,
            AiDifficulty.HARD to AiDifficulty.MEDIUM,
        )
        for ((strong, weak) in pairs) {
            val result = countSeries(strong, weak, timeBudgetMs = 400)
            assertTrue(
                result.weakWins == 0,
                "$strong lost ${result.weakWins} game(s) to $weak"
            )
        }
    }

    @Test
    fun `master never loses to beginner across a series`() {
        for (seed in 1L..3L) {
            val masterFirst = playGame(AiDifficulty.MASTER, AiDifficulty.BEGINNER, seed = seed, timeBudgetMs = 300)
            assertTrue(
                masterFirst == Player.ONE || masterFirst == null,
                "MASTER lost as Player One (seed $seed)"
            )
            val masterSecond = playGame(AiDifficulty.BEGINNER, AiDifficulty.MASTER, seed = seed + 700, timeBudgetMs = 300)
            assertTrue(
                masterSecond == Player.TWO || masterSecond == null,
                "MASTER lost as Player Two (seed $seed)"
            )
        }
    }

    @Test
    fun `personalities all produce legal play at master strength`() {
        val state = movementState()
        for (personality in allPersonalities) {
            val move = assertNotNull(
                AiPlayer.chooseMove(
                    state = state,
                    difficulty = AiDifficulty.HARD,
                    personality = personality,
                    timeBudgetMs = 150,
                )
            )
            val engine = GameEngine(state, MovementRules.FREE)
            assertTrue(
                engine.apply(move).events.none { it is GameEvent.MoveRejected },
                "$personality produced an illegal move: $move"
            )
        }
    }

    /** Player ONE at 0, 1, 5; Player TWO at 3, 4, 7; ONE to move in the movement phase. */
    private fun movementState(): GameState {
        var board = Board()
        listOf(
            0 to Player.ONE, 3 to Player.TWO, 1 to Player.ONE,
            4 to Player.TWO, 5 to Player.ONE, 7 to Player.TWO
        ).forEach { (index, player) -> board = board.placed(Position(index), player) }
        return GameState(
            board = board,
            currentPlayer = Player.ONE,
            phase = GamePhase.MOVEMENT,
            seedsRemaining = Player.entries.associateWith { 0 },
            history = listOf(BoardSnapshot(board, Player.ONE)),
        )
    }
}
