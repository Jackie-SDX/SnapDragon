package com.threeseeds.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.threeseeds.app.audio.SoundPlayer
import com.threeseeds.app.profile.InMemoryProfileStore
import com.threeseeds.app.profile.ProfileData
import com.threeseeds.app.settings.SettingsStore
import com.threeseeds.app.state.GameMode
import com.threeseeds.app.state.GameStateCodec
import com.threeseeds.engine.Board
import com.threeseeds.engine.BoardSnapshot
import com.threeseeds.engine.GamePhase
import com.threeseeds.engine.GameState
import com.threeseeds.engine.Player
import com.threeseeds.engine.Position
import com.threeseeds.engine.ai.AiDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Vs Computer flow: the seat split, the think/act lifecycle, taps
 * landing while it is the machine's turn, two-ply undo, and restoring a
 * computer-to-move match after process death.
 *
 * Everything runs on one shared virtual scheduler: Main, the AI's
 * dispatcher, and the test body all share [TestScope.testScheduler], so
 * "the AI is mid-think" and "the AI has answered" are both exact,
 * deterministic states — no sleeping, no flakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiFlowTest {

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

    private fun TestScope.newViewModel(
        handle: SavedStateHandle = SavedStateHandle(),
        profile: InMemoryProfileStore = InMemoryProfileStore(ProfileData(difficulty = AiDifficulty.BEGINNER)),
        aiDelayMsOverride: Long? = 0L,
    ) = GameViewModel(
        savedStateHandle = handle,
        settings = FakeSettings(),
        soundPlayer = FakeSound(),
        profile = profile,
        aiDispatcher = UnconfinedTestDispatcher(testScheduler),
        aiDelayMsOverride = aiDelayMsOverride,
        aiSeedOverride = 42L,
    )

    @Test
    fun `the computer answers the human's move and hands the turn back`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val vm = newViewModel()
            vm.startMatch(GameMode.VS_AI)
            assertEquals(GameMode.VS_AI, vm.uiState.value.gameMode)
            assertEquals(GamePhase.PLACEMENT, vm.uiState.value.gameState.phase)
            assertFalse(vm.uiState.value.aiThinking)

            vm.onPointTapped(Position(0)) // human places; machine replies

            val state = vm.uiState.value.gameState
            assertEquals(Player.ONE, state.board[Position(0)])
            assertEquals(1, state.board.positionsOf(Player.TWO).size, "the machine must have answered")
            assertEquals(Player.ONE, state.currentPlayer, "control returns to the human")
            assertFalse(vm.uiState.value.aiThinking)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `taps are ignored while the computer is thinking and land when it finishes`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val vm = newViewModel(aiDelayMsOverride = 450L)
            vm.startMatch(GameMode.VS_AI)
            vm.onPointTapped(Position(0))

            // The AI coroutine is now parked on its think delay.
            assertTrue(vm.uiState.value.aiThinking, "the machine must be visibly thinking")
            var state = vm.uiState.value.gameState
            assertEquals(0, state.board.positionsOf(Player.TWO).size, "no move until the delay elapses")

            // A second human tap mid-think changes nothing: it is the machine's seat.
            vm.onPointTapped(Position(3))
            state = vm.uiState.value.gameState
            assertEquals(0, state.board.positionsOf(Player.TWO).size)
            assertEquals(Player.TWO, state.currentPlayer)
            assertTrue(vm.uiState.value.aiThinking)

            advanceTimeBy(451)

            state = vm.uiState.value.gameState
            assertEquals(1, state.board.positionsOf(Player.TWO).size, "the machine moved once time passed")
            assertEquals(Player.ONE, state.currentPlayer)
            assertFalse(vm.uiState.value.aiThinking)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `undo rewinds the computer's answer and the human's move together`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val vm = newViewModel()
            vm.startMatch(GameMode.VS_AI)
            vm.onPointTapped(Position(0)) // human + machine both move

            assertEquals(1, vm.uiState.value.gameState.board.positionsOf(Player.TWO).size)

            vm.undo()

            val state = vm.uiState.value.gameState
            assertEquals(0, state.board.positionsOf(Player.ONE).size + state.board.positionsOf(Player.TWO).size)
            assertEquals(Player.ONE, state.currentPlayer)
            assertEquals(GamePhase.PLACEMENT, state.phase)
            assertFalse(vm.uiState.value.aiThinking, "no machine turn may restart from an undo")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `undo during the machine's think cancels just the human's move`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val vm = newViewModel(aiDelayMsOverride = 450L)
            vm.startMatch(GameMode.VS_AI)
            vm.onPointTapped(Position(0))
            assertTrue(vm.uiState.value.aiThinking)

            vm.undo() // player takes the move back before the machine answers

            val state = vm.uiState.value.gameState
            assertEquals(0, state.board.positionsOf(Player.ONE).size + state.board.positionsOf(Player.TWO).size)
            assertEquals(Player.ONE, state.currentPlayer)
            assertFalse(vm.uiState.value.aiThinking)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a restored computer-to-move match resumes with the machine's move`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            // Exactly what process death would leave behind: the human's
            // placement was persisted, and it is the machine's seat.
            val board = Board().placed(Position(0), Player.ONE)
            val state = GameState(
                board = board,
                currentPlayer = Player.TWO,
                phase = GamePhase.PLACEMENT,
                seedsRemaining = Player.entries.associateWith {
                    GameState.SEEDS_PER_PLAYER - board.positionsOf(it).size
                },
                history = listOf(
                    BoardSnapshot(Board(), Player.ONE),
                    BoardSnapshot(board, Player.TWO),
                ),
            )
            val handle = SavedStateHandle(
                mapOf(
                    "saved_game_state" to GameStateCodec.encode(state),
                    "saved_mode" to GameMode.VS_AI.name,
                )
            )

            val vm = newViewModel(handle = handle)

            // Eager dispatch: the resumed machine move lands during construction.
            val resumed = vm.uiState.value.gameState
            assertEquals(Player.ONE, resumed.currentPlayer, "after the machine moves it is the human's seat")
            assertEquals(1, resumed.board.positionsOf(Player.TWO).size)
            assertEquals(Player.ONE, resumed.board[Position(0)], "the human's seed survives restore")
            assertFalse(vm.uiState.value.aiThinking)
            assertEquals(GameMode.VS_AI, vm.uiState.value.gameMode)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `pass and play never spawns a machine turn`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val vm = newViewModel()
            vm.startMatch(GameMode.PASS_AND_PLAY)

            vm.onPointTapped(Position(0)) // P1
            vm.onPointTapped(Position(3)) // P2

            val state = vm.uiState.value.gameState
            assertEquals(Player.ONE, state.currentPlayer)
            assertEquals(1, state.board.positionsOf(Player.TWO).size)
            assertFalse(vm.uiState.value.aiThinking)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
