package com.threeseeds.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoveValidatorTest {

    @Test
    fun `placement is valid on an empty point during the placement phase`() {
        assertTrue(MoveValidator.validatePlacement(GameState(), Position(0)).isSuccess)
    }

    @Test
    fun `placement is rejected on an occupied point`() {
        val state = GameState().let { GameEngine(it).apply(Move.Place(Position(0))).state }
        assertTrue(MoveValidator.validatePlacement(state, Position(0)).isFailure)
    }

    @Test
    fun `placement is rejected once the movement phase has started`() {
        val state = GameState(phase = GamePhase.MOVEMENT)
        assertTrue(MoveValidator.validatePlacement(state, Position(0)).isFailure)
    }

    @Test
    fun `placement is rejected when the player has no seeds left, even if the phase is still PLACEMENT`() {
        val state = GameState(seedsRemaining = mapOf(Player.ONE to 0, Player.TWO to 3))
        assertTrue(MoveValidator.validatePlacement(state, Position(0)).isFailure)
    }

    @Test
    fun `relocation is rejected before the movement phase begins`() {
        assertTrue(MoveValidator.validateRelocation(GameState(), Position(0), Position(1)).isFailure)
    }

    private fun midGameMovementState(): GameState {
        var board = Board()
        board = board.placed(Position(4), Player.ONE)
        board = board.placed(Position(0), Player.TWO)
        board = board.placed(Position(1), Player.ONE)
        board = board.placed(Position(3), Player.TWO)
        board = board.placed(Position(5), Player.ONE)
        board = board.placed(Position(7), Player.TWO)
        return GameState(
            board = board,
            currentPlayer = Player.ONE,
            phase = GamePhase.MOVEMENT,
            seedsRemaining = mapOf(Player.ONE to 0, Player.TWO to 0)
        )
    }

    @Test
    fun `relocation is rejected when moving the opponent's seed`() {
        val state = midGameMovementState() // position 0 belongs to Player TWO
        assertTrue(MoveValidator.validateRelocation(state, Position(0), Position(2)).isFailure)
    }

    @Test
    fun `relocation is rejected onto an occupied point`() {
        val state = midGameMovementState() // 1 (ONE) -> 3 (occupied by TWO)
        assertTrue(MoveValidator.validateRelocation(state, Position(1), Position(3)).isFailure)
    }

    @Test
    fun `adjacent-only rules reject a non-adjacent point`() {
        val state = midGameMovementState() // 4 and... every point is adjacent to 4, so use 1->8 instead
        assertTrue(MoveValidator.validateRelocation(state, Position(1), Position(8), MovementRules.TAPATAN).isFailure)
    }

    @Test
    fun `standard rules allow a move to any vacant point`() {
        val state = midGameMovementState()
        assertTrue(MoveValidator.validateRelocation(state, Position(1), Position(8), MovementRules.FREE).isSuccess)
        assertTrue(MoveValidator.validateRelocation(state, Position(1), Position(8)).isSuccess) // default is FREE
    }

    @Test
    fun `legalDestinations under adjacent-only rules only returns empty, connected points`() {
        val state = midGameMovementState()
        // Player ONE's seed at 4 (center) connects to all 8 others; only 2, 6, 8 are empty.
        assertEquals(
            setOf(Position(2), Position(6), Position(8)),
            MoveValidator.legalDestinations(state, Position(4), MovementRules.TAPATAN)
        )
    }

    @Test
    fun `legalDestinations under standard rules returns every vacant point`() {
        val state = midGameMovementState() // empty: 2, 6, 8
        assertEquals(
            setOf(Position(2), Position(6), Position(8)),
            MoveValidator.legalDestinations(state, Position(4), MovementRules.FREE)
        )
        assertEquals(
            setOf(Position(2), Position(6), Position(8)),
            MoveValidator.legalDestinations(state, Position(1)) // default is FREE
        )
    }

    @Test
    fun `legalDestinations for a point with no seed of the current player's is empty`() {
        val state = midGameMovementState()
        assertEquals(emptySet(), MoveValidator.legalDestinations(state, Position(0))) // belongs to TWO
        assertEquals(emptySet(), MoveValidator.legalDestinations(state, Position(2))) // empty point
    }
}
