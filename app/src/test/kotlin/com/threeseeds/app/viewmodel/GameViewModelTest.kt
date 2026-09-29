package com.threeseeds.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.threeseeds.app.audio.SoundPlayer
import com.threeseeds.app.settings.SettingsStore
import com.threeseeds.app.state.UiHint
import com.threeseeds.engine.GamePhase
import com.threeseeds.engine.Player
import com.threeseeds.engine.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeSettingsStore(
    override var soundEnabled: Boolean = true,
    override var hapticsEnabled: Boolean = true,
    override var debugModeEnabled: Boolean = false,
    override var adjacentMovementOnly: Boolean = false
) : SettingsStore

private class FakeSoundPlayer : SoundPlayer {
    val played = mutableListOf<String>()
    override fun playSeedPlaced() { played += "placed" }
    override fun playSeedMoved() { played += "moved" }
    override fun playInvalidMove() { played += "invalid" }
    override fun playVictory() { played += "victory" }
    override fun release() { played += "released" }
}

class GameViewModelTest {

    private fun newViewModel(
        settings: FakeSettingsStore = FakeSettingsStore(),
        sound: FakeSoundPlayer = FakeSoundPlayer()
    ) = GameViewModel(SavedStateHandle(), settings, sound)

    @Test
    fun `initial ui state reflects a fresh game and the settings store`() {
        val settings = FakeSettingsStore(soundEnabled = false, hapticsEnabled = false, debugModeEnabled = true)
        val viewModel = newViewModel(settings = settings)

        val state = viewModel.uiState.value
        assertEquals(GamePhase.PLACEMENT, state.gameState.phase)
        assertEquals(false, state.soundEnabled)
        assertEquals(false, state.hapticsEnabled)
        assertEquals(true, state.debugModeEnabled)
        assertNull(state.selectedSeed)
    }

    @Test
    fun `tapping an empty point during placement places a seed and plays a sound`() {
        val sound = FakeSoundPlayer()
        val viewModel = newViewModel(sound = sound)

        viewModel.onPointTapped(Position(4))

        assertEquals(Player.ONE, viewModel.uiState.value.gameState.board[Position(4)])
        assertTrue("placed" in sound.played)
    }

    @Test
    fun `tapping an occupied point during placement is rejected and flashes, without crashing`() {
        val viewModel = newViewModel()
        viewModel.onPointTapped(Position(0)) // P1
        viewModel.onPointTapped(Position(0)) // P2 tries the same point

        assertEquals(Position(0), viewModel.uiState.value.invalidMoveFlash)
        assertEquals(Player.TWO, viewModel.uiState.value.gameState.currentPlayer) // still P2's turn
    }

    @Test
    fun `tapping own seed during movement selects it and computes legal destinations`() {
        val viewModel = newViewModel()
        listOf(0, 3, 1, 4, 5, 7).forEach { viewModel.onPointTapped(Position(it)) } // -> MOVEMENT, P1: 0,1,5

        viewModel.onPointTapped(Position(1))

        assertEquals(Position(1), viewModel.uiState.value.selectedSeed)
        assertTrue(viewModel.uiState.value.legalDestinations.isNotEmpty())
    }

    @Test
    fun `tapping a legal destination after selection moves the seed and clears selection`() {
        val viewModel = newViewModel()
        listOf(0, 3, 1, 4, 5, 7).forEach { viewModel.onPointTapped(Position(it)) }
        viewModel.onPointTapped(Position(1)) // select
        viewModel.onPointTapped(Position(2)) // 1 -> 2 is legal here

        assertNull(viewModel.uiState.value.selectedSeed)
        assertEquals(Player.ONE, viewModel.uiState.value.gameState.board[Position(2)])
        assertTrue(viewModel.uiState.value.gameState.board.isEmpty(Position(1)))
    }

    @Test
    fun `tapping the same selected seed again deselects it`() {
        val viewModel = newViewModel()
        listOf(0, 3, 1, 4, 5, 7).forEach { viewModel.onPointTapped(Position(it)) }
        viewModel.onPointTapped(Position(1))
        viewModel.onPointTapped(Position(1))

        assertNull(viewModel.uiState.value.selectedSeed)
    }

    @Test
    fun `winning plays the victory sound and pulses haptics`() {
        val sound = FakeSoundPlayer()
        val viewModel = newViewModel(sound = sound)
        listOf(0, 3, 1, 4).forEach { viewModel.onPointTapped(Position(it)) }
        val hapticBefore = viewModel.hapticTick.value

        viewModel.onPointTapped(Position(2)) // completes top row

        assertEquals(GamePhase.WON, viewModel.uiState.value.gameState.phase)
        assertTrue("victory" in sound.played)
        assertTrue(viewModel.hapticTick.value > hapticBefore)
    }

    @Test
    fun `under standard rules a selected seed can be sent to any vacant point`() {
        val viewModel = newViewModel()
        listOf(0, 3, 1, 4, 5, 7).forEach { viewModel.onPointTapped(Position(it)) } // -> MOVEMENT, P1: 0,1,5, empty: 2,6,8
        viewModel.onPointTapped(Position(0)) // select P1's seed at 0; its neighbours 1,3,4 are all occupied

        viewModel.onPointTapped(Position(6)) // vacant, not adjacent, and reachable under the standard rule

        assertNull(viewModel.uiState.value.selectedSeed)
        assertEquals(Player.ONE, viewModel.uiState.value.gameState.board[Position(6)])
        assertTrue(viewModel.uiState.value.gameState.board.isEmpty(Position(0)))
    }

    @Test
    fun `in adjacent-only mode a blocked tap keeps the selection, flashes, and explains why`() {
        val settings = FakeSettingsStore(adjacentMovementOnly = true)
        val viewModel = newViewModel(settings = settings)
        listOf(0, 3, 1, 4, 5, 7).forEach { viewModel.onPointTapped(Position(it)) }
        viewModel.onPointTapped(Position(0)) // neighbours 1,3,4 all occupied -> boxed in

        viewModel.onPointTapped(Position(6)) // vacant but not along a drawn line from 0

        assertEquals(Position(0), viewModel.uiState.value.selectedSeed, "selection must survive the miss")
        assertEquals(Position(6), viewModel.uiState.value.invalidMoveFlash)
        assertEquals(UiHint.BLOCKED_PATH, viewModel.uiState.value.hintMessage)
        assertTrue(viewModel.uiState.value.gameState.board.isEmpty(Position(6)), "nothing may move")
    }

    @Test
    fun `tapping the opponent seed while selected explains instead of cancelling`() {
        val viewModel = newViewModel()
        listOf(0, 3, 1, 4, 5, 7).forEach { viewModel.onPointTapped(Position(it)) } // P1: 0,1,5  P2: 3,4,7
        viewModel.onPointTapped(Position(1)) // select

        viewModel.onPointTapped(Position(4)) // opponent's seed

        assertEquals(Position(1), viewModel.uiState.value.selectedSeed)
        assertEquals(UiHint.OPPONENT_SEED, viewModel.uiState.value.hintMessage)
        assertEquals(Position(4), viewModel.uiState.value.invalidMoveFlash)
    }

    @Test
    fun `the adjacent-only setting persists but never rewrites the rules of a match in progress`() {
        val settings = FakeSettingsStore()
        val viewModel = newViewModel(settings = settings)
        listOf(0, 3, 1, 4, 5, 7).forEach { viewModel.onPointTapped(Position(it)) } // -> MOVEMENT

        viewModel.setAdjacentMovementOnly(true)

        assertTrue(settings.adjacentMovementOnly)
        assertTrue(viewModel.uiState.value.adjacentMovementOnly, "the preference is reflected")
        assertFalse(viewModel.uiState.value.matchAdjacentMovementOnly, "the running match keeps its original ruleset")

        // The in-progress match is still free-movement: 0's neighbours are all
        // occupied, yet the vacant 6 must stay reachable.
        viewModel.onPointTapped(Position(0))
        assertTrue(Position(6) in viewModel.uiState.value.legalDestinations)
        viewModel.onPointTapped(Position(6))
        assertEquals(Player.ONE, viewModel.uiState.value.gameState.board[Position(6)])

        // The NEXT match picks the new rules up.
        viewModel.restart()
        assertTrue(viewModel.uiState.value.matchAdjacentMovementOnly, "restart adopts the saved preference")
        listOf(0, 3, 1, 4, 5, 7).forEach { viewModel.onPointTapped(Position(it)) }
        viewModel.onPointTapped(Position(0))
        assertEquals(emptySet(), viewModel.uiState.value.legalDestinations) // 1,3,4 occupied; only lines count now
    }

    @Test
    fun `undo reverts the last move through the view model`() {
        val viewModel = newViewModel()
        viewModel.onPointTapped(Position(0))
        viewModel.undo()
        assertTrue(viewModel.uiState.value.gameState.board.isEmpty(Position(0)))
    }

    @Test
    fun `restart and playAgain both reset to a fresh game`() {
        val viewModel = newViewModel()
        viewModel.onPointTapped(Position(0))
        viewModel.restart()
        assertEquals(GamePhase.PLACEMENT, viewModel.uiState.value.gameState.phase)
        assertTrue(viewModel.uiState.value.gameState.board.isEmpty(Position(0)))

        viewModel.onPointTapped(Position(0))
        viewModel.playAgain()
        assertTrue(viewModel.uiState.value.gameState.board.isEmpty(Position(0)))
    }

    @Test
    fun `togglePause flips isPaused without touching game state`() {
        val viewModel = newViewModel()
        assertEquals(false, viewModel.uiState.value.isPaused)
        viewModel.togglePause()
        assertEquals(true, viewModel.uiState.value.isPaused)
        viewModel.togglePause()
        assertEquals(false, viewModel.uiState.value.isPaused)
    }

    @Test
    fun `setting changes are written through to the settings store and reflected in ui state`() {
        val settings = FakeSettingsStore()
        val viewModel = newViewModel(settings = settings)

        viewModel.setSoundEnabled(false)
        viewModel.setHapticsEnabled(false)
        viewModel.setDebugModeEnabled(true)

        assertEquals(false, settings.soundEnabled)
        assertEquals(false, settings.hapticsEnabled)
        assertEquals(true, settings.debugModeEnabled)
        assertEquals(false, viewModel.uiState.value.soundEnabled)
        assertEquals(false, viewModel.uiState.value.hapticsEnabled)
        assertEquals(true, viewModel.uiState.value.debugModeEnabled)
    }

    @Test
    fun `sound is not played for placement or movement when disabled in settings`() {
        val sound = FakeSoundPlayer()
        val viewModel = newViewModel(settings = FakeSettingsStore(soundEnabled = false), sound = sound)
        viewModel.onPointTapped(Position(0))
        assertTrue(sound.played.isEmpty())
    }

    @Test
    fun `game state survives being reconstructed from the same SavedStateHandle`() {
        val handle = SavedStateHandle()
        val settings = FakeSettingsStore()
        val first = GameViewModel(handle, settings, FakeSoundPlayer())
        first.onPointTapped(Position(4))

        // Simulate process death + restore: a new ViewModel instance reads the same handle.
        val second = GameViewModel(handle, settings, FakeSoundPlayer())

        assertEquals(Player.ONE, second.uiState.value.gameState.board[Position(4)])
        assertEquals(Player.TWO, second.uiState.value.gameState.currentPlayer)
    }
}
