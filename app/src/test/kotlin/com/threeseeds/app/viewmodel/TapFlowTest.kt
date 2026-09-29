package com.threeseeds.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.threeseeds.app.audio.SoundPlayer
import com.threeseeds.app.settings.SettingsStore
import com.threeseeds.app.state.GameStateCodec
import com.threeseeds.app.state.UiHint
import com.threeseeds.engine.GamePhase
import com.threeseeds.engine.GameState
import com.threeseeds.engine.Board
import com.threeseeds.engine.MoveValidator
import com.threeseeds.engine.MovementRules
import com.threeseeds.engine.Player
import com.threeseeds.engine.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Player-level tap flow: every destination the rules allow MUST be
 * reachable through onPointTapped exactly the way a thumb would do it
 * (tap seed -> tap target), and a blocked target must never move the
 * seed. This is the layer where the reported "can't move to the free
 * space" bug would live even when the engine itself is correct.
 */
class TapFlowTest {

    private class FakeSettings(
        override var adjacentMovementOnly: Boolean = false
    ) : SettingsStore {
        override var soundEnabled = true
        override var hapticsEnabled = true
        override var debugModeEnabled = false
    }

    private class FakeSound : SoundPlayer {
        override fun playSeedPlaced() {}
        override fun playSeedMoved() {}
        override fun playInvalidMove() {}
        override fun playVictory() {}
        override fun release() {}
    }

    private fun vmWith(state: GameState, adjacentMovementOnly: Boolean = false): GameViewModel {
        val handle = SavedStateHandle(mapOf("saved_game_state" to GameStateCodec.encode(state)))
        return GameViewModel(handle, FakeSettings(adjacentMovementOnly), FakeSound())
    }

    private fun movementState(board: Board, toMove: Player = Player.ONE): GameState = GameState(
        board = board,
        currentPlayer = toMove,
        phase = GamePhase.MOVEMENT,
        seedsRemaining = Player.entries.associateWith { 0 },
        // The codec round-trips through history, so the history must
        // actually describe this board (as GameEngine.finishTurn does).
        history = listOf(com.threeseeds.engine.BoardSnapshot(board, toMove))
    )

    @Test
    fun `every rule-legal destination is reachable by tapping seed then target`() {
        // P1 at 1 (top middle), P2 at 2 (top right), P1 also at 6, P2 at 4, P1 at 8, P2 at 3.
        val board = Board()
            .placed(Position(1), Player.ONE)
            .placed(Position(6), Player.ONE)
            .placed(Position(8), Player.ONE)
            .placed(Position(2), Player.TWO)
            .placed(Position(4), Player.TWO)
            .placed(Position(3), Player.TWO)
        val state = movementState(board, Player.ONE)
        val vm = vmWith(state)

        // What the rules say for the seed at 1: neighbours 0 (empty), 2 (occupied), 4 (occupied).
        val ruleDestinations = MoveValidator.legalDestinations(state, Position(1))
        assertTrue(Position(0) in ruleDestinations, "sanity: 1->0 must be legal here")

        vm.onPointTapped(Position(1))
        assertEquals(Position(1), vm.uiState.value.selectedSeed)
        assertEquals(ruleDestinations, vm.uiState.value.legalDestinations)

        for (dest in ruleDestinations) {
            val vm = vmWith(state) // fresh instance per destination: a move ends the turn
            vm.onPointTapped(Position(1))
            assertEquals(Position(1), vm.uiState.value.selectedSeed)
            vm.onPointTapped(dest)
            assertTrue(
                vm.uiState.value.gameState.board[dest] == Player.ONE,
                "tapping legal destination $dest must move the seed"
            )
        }
    }

    @Test
    fun `under standard rules the reported blocked point IS reachable`() {
        // The reported shape: [X][O][.] along the top row — X wants the empty 2.
        val board = Board()
            .placed(Position(0), Player.ONE)
            .placed(Position(1), Player.TWO)
            .placed(Position(4), Player.TWO)
            .placed(Position(3), Player.ONE)
            .placed(Position(7), Player.ONE)
            .placed(Position(8), Player.TWO)
        val state = movementState(board, Player.ONE)
        val vm = vmWith(state)

        vm.onPointTapped(Position(0))
        assertEquals(Position(0), vm.uiState.value.selectedSeed)
        // Standard rule: any vacant point (2, 5, 6) may be moved to.
        assertEquals(setOf(Position(2), Position(5), Position(6)), vm.uiState.value.legalDestinations)

        vm.onPointTapped(Position(2))

        assertTrue(vm.uiState.value.gameState.board[Position(2)] == Player.ONE, "the seed must land on 2")
        assertTrue(vm.uiState.value.gameState.board.isEmpty(Position(0)), "the seed must leave 0")
        assertNull(vm.uiState.value.selectedSeed, "selection ends after a move")
    }

    @Test
    fun `in adjacent-only mode a blocked non-adjacent empty point never moves the seed`() {
        val board = Board()
            .placed(Position(0), Player.ONE)
            .placed(Position(1), Player.TWO)
            .placed(Position(4), Player.TWO)
            .placed(Position(3), Player.ONE)
            .placed(Position(7), Player.ONE)
            .placed(Position(8), Player.TWO)
        val state = movementState(board, Player.ONE)
        val vm = vmWith(state, adjacentMovementOnly = true)

        vm.onPointTapped(Position(0))
        assertEquals(Position(0), vm.uiState.value.selectedSeed)
        // Neighbours of 0 are 1 (P2), 3 (own seed), 4 (P2): nothing may be offered.
        assertEquals(emptySet(), vm.uiState.value.legalDestinations)
        assertEquals(emptySet(), MoveValidator.legalDestinations(state, Position(0), MovementRules.TAPATAN))

        vm.onPointTapped(Position(2)) // empty but not connected to 0

        assertTrue(vm.uiState.value.gameState.board[Position(0)] == Player.ONE, "seed must stay put")
        assertTrue(vm.uiState.value.gameState.board[Position(2)] == null, "nothing may appear on the blocked target")
        assertEquals(Position(0), vm.uiState.value.selectedSeed, "the selection survives so the player can retry")
        assertEquals(Position(2), vm.uiState.value.invalidMoveFlash, "the missed point must flash")
        assertEquals(UiHint.BLOCKED_PATH, vm.uiState.value.hintMessage, "and the reason must be explained")
    }

    @Test
    fun `an adjacent empty target behind no blocker always moves`() {
        val board = Board()
            .placed(Position(1), Player.ONE)
            .placed(Position(2), Player.TWO)
            .placed(Position(6), Player.ONE)
            .placed(Position(7), Player.ONE)
            .placed(Position(4), Player.TWO)
            .placed(Position(8), Player.TWO)
        val vm = vmWith(movementState(board, Player.ONE))

        vm.onPointTapped(Position(1))
        assertTrue(Position(0) in vm.uiState.value.legalDestinations, "1->0 must be highlighted")
        vm.onPointTapped(Position(0))
        assertTrue(vm.uiState.value.gameState.board[Position(0)] == Player.ONE, "1->0 must land")
        assertEquals(Player.TWO, vm.uiState.value.gameState.currentPlayer, "turn must pass")
    }

    @Test
    fun `opponent seed is never selectable and never a destination`() {
        val board = Board()
            .placed(Position(0), Player.ONE)
            .placed(Position(5), Player.ONE)
            .placed(Position(8), Player.ONE)
            .placed(Position(1), Player.TWO)
            .placed(Position(4), Player.TWO)
            .placed(Position(7), Player.TWO)
        val vm = vmWith(movementState(board, Player.ONE))

        vm.onPointTapped(Position(1)) // opponent's seed
        assertNull(vm.uiState.value.selectedSeed)
        assertTrue(vm.uiState.value.legalDestinations.isEmpty())
    }
}
