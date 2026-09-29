package com.threeseeds.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threeseeds.app.audio.SoundPlayer
import com.threeseeds.app.profile.Economy
import com.threeseeds.app.profile.InMemoryProfileStore
import com.threeseeds.app.profile.ProfileStore
import com.threeseeds.app.settings.SettingsStore
import com.threeseeds.app.state.GameMode
import com.threeseeds.app.state.GameStateCodec
import com.threeseeds.app.state.GameUiState
import com.threeseeds.app.state.UiHint
import com.threeseeds.engine.GameEngine
import com.threeseeds.engine.GameEvent
import com.threeseeds.engine.GamePhase
import com.threeseeds.engine.GameState
import com.threeseeds.engine.Move
import com.threeseeds.engine.MovementRules
import com.threeseeds.engine.Player
import com.threeseeds.engine.Position
import com.threeseeds.engine.ai.AiPlayer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GameViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val settings: SettingsStore,
    private val soundPlayer: SoundPlayer,
    private val profile: ProfileStore = InMemoryProfileStore(),
    /** Where the search runs; injected so tests can drive it deterministically. */
    private val aiDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Overrides the profile's think-speed delay; tests pass 0. */
    private val aiDelayMsOverride: Long? = null,
    /** Fixed RNG seed for the AI; tests pass a constant, the app passes null (time-based). */
    private val aiSeedOverride: Long? = null,
) : ViewModel() {

    /**
     * The ruleset of THIS match, frozen when the match starts: a mid-match
     * settings toggle must not rewrite the rules under a game in progress.
     * Restored across process death like the board itself.
     */
    private val matchRules: MovementRules = savedStateHandle.get<String>(KEY_SAVED_RULES)
        ?.let { runCatching { MovementRules.valueOf(it) }.getOrNull() }
        ?: settings.movementRules

    private val engine = GameEngine(
        initialState = GameStateCodec.decode(savedStateHandle.get<String>(KEY_SAVED_GAME) ?: ""),
        movementRules = matchRules
    )

    private val initialMode: GameMode = savedStateHandle.get<String>(KEY_SAVED_MODE)
        ?.let { runCatching { GameMode.valueOf(it) }.getOrNull() }
        ?: GameMode.PASS_AND_PLAY

    /** Bumped whenever a match ends/restarts so an in-flight AI move can't land on the new board. */
    private var matchEpoch = 0

    /** Set the moment a finished match's reward is granted; one match, one reward. */
    private var matchRecorded = false

    private var aiJob: Job? = null

    private val _uiState = MutableStateFlow(
        GameUiState(
            gameState = engine.state,
            movableSeeds = movableSeedsIn(engine.state),
            adjacentMovementOnly = settings.adjacentMovementOnly,
            matchAdjacentMovementOnly = matchRules == MovementRules.TAPATAN,
            gameMode = initialMode,
            soundEnabled = settings.soundEnabled,
            hapticsEnabled = settings.hapticsEnabled,
            debugModeEnabled = settings.debugModeEnabled
        )
    )
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    /** Incremented once per haptic-worthy event; the UI observes this and pulses, then moves on. */
    private val _hapticTick = MutableStateFlow(0L)
    val hapticTick: StateFlow<Long> = _hapticTick.asStateFlow()

    init {
        // A match may have been restored while the computer was to move.
        maybeStartAiTurn()
    }

    /**
     * Starts or resumes a match in [mode]. Same mode with a live match
     * resumes it; a different mode or a finished match begins anew.
     */
    fun startMatch(mode: GameMode) {
        val terminal = engine.state.phase == GamePhase.WON || engine.state.phase == GamePhase.DRAW
        if (mode != uiState.value.gameMode || terminal) {
            newMatch(mode)
        } else {
            maybeStartAiTurn()
        }
    }

    /**
     * The one entry point for taps. This method decides only WHETHER to
     * ask the engine to place, relocate, select, or deselect — it never
     * decides whether a move is legal. That question always goes to
     * GameEngine.apply(), same as it would from a future network client.
     */
    fun onPointTapped(position: Position) {
        // The computer's seat is not touchable.
        if (uiState.value.gameMode == GameMode.VS_AI && engine.state.currentPlayer == Player.TWO) return

        val state = _uiState.value
        val gameState = state.gameState

        val moveToAttempt: Move? = when {
            gameState.phase == GamePhase.PLACEMENT -> Move.Place(position)

            gameState.phase == GamePhase.MOVEMENT && state.selectedSeed == null -> {
                if (gameState.board.isOccupiedBy(position, gameState.currentPlayer)) {
                    selectSeed(position)
                }
                null
            }

            gameState.phase == GamePhase.MOVEMENT && state.selectedSeed != null -> {
                val selected = state.selectedSeed
                val destinations = engine.legalDestinations(selected)
                when {
                    position == selected -> {
                        deselect()
                        null
                    }

                    gameState.board.isOccupiedBy(position, gameState.currentPlayer) -> {
                        selectSeed(position)
                        null
                    }

                    position in destinations -> Move.Relocate(selected, position)

                    gameState.board[position] != null -> {
                        // Opponent's seed: explain instead of silently cancelling.
                        flagFeedback(position, UiHint.OPPONENT_SEED)
                        null
                    }

                    else -> {
                        // Empty point that is not reachable right now. Under the
                        // standard rules every vacant point is reachable, so this
                        // only happens in the stricter adjacent-only variant —
                        // keep the selection so the player can retry.
                        flagFeedback(position, UiHint.BLOCKED_PATH)
                        null
                    }
                }
            }

            else -> null // WON or DRAW: taps on the board do nothing
        }

        moveToAttempt?.let { applyMove(it) }
    }

    fun undo() {
        cancelAi()
        var result = engine.undo()
        // In VS_AI a "turn" is human + computer: undo both plies so the
        // player lands back on their own decision, not on the computer's.
        // The second ply is only there when the computer already answered
        // (its ply ends with the human to move and history still showing
        // the computer's position); if the player undoes while the
        // computer is still thinking, one undo is already correct.
        if (uiState.value.gameMode == GameMode.VS_AI &&
            engine.state.currentPlayer == Player.TWO &&
            engine.state.history.isNotEmpty()
        ) {
            result = engine.undo()
        }
        persist()
        _uiState.update {
            it.copy(
                gameState = result.state,
                selectedSeed = null,
                legalDestinations = emptySet(),
                invalidMoveFlash = null,
                hintMessage = null
            ).withMovableSeeds(movableSeedsIn(result.state))
        }
    }

    fun restart() = newMatch(uiState.value.gameMode)

    /** Same as restart(), named for where it's called from — the Won/Draw overlay, not the menu. */
    fun playAgain() = restart()

    fun togglePause() {
        val pausing = !uiState.value.isPaused
        _uiState.update { it.copy(isPaused = pausing) }
        if (pausing) {
            cancelAi()
        } else {
            maybeStartAiTurn()
        }
    }

    fun clearInvalidFlash() {
        _uiState.update { it.copy(invalidMoveFlash = null) }
    }

    fun clearHint() {
        _uiState.update { it.copy(hintMessage = null) }
    }

    fun setSoundEnabled(enabled: Boolean) {
        settings.soundEnabled = enabled
        _uiState.update { it.copy(soundEnabled = enabled) }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        settings.hapticsEnabled = enabled
        _uiState.update { it.copy(hapticsEnabled = enabled) }
    }

    fun setDebugModeEnabled(enabled: Boolean) {
        settings.debugModeEnabled = enabled
        _uiState.update { it.copy(debugModeEnabled = enabled) }
    }

    /**
     * Persists the movement preference. The CURRENT match keeps the
     * rules it started with (see [matchRules]); the new preference is
     * picked up when the next match is created.
     */
    fun setAdjacentMovementOnly(enabled: Boolean) {
        settings.adjacentMovementOnly = enabled
        _uiState.update { it.copy(adjacentMovementOnly = enabled) }
    }

    private fun newMatch(mode: GameMode) {
        matchEpoch++
        cancelAi()
        matchRecorded = false
        engine.movementRules = settings.movementRules
        savedStateHandle[KEY_SAVED_RULES] = engine.movementRules.name
        savedStateHandle[KEY_SAVED_MODE] = mode.name
        engine.reset()
        persist()
        _uiState.update {
            it.copy(
                gameState = engine.state,
                gameMode = mode,
                matchAdjacentMovementOnly = engine.movementRules == MovementRules.TAPATAN,
                selectedSeed = null,
                legalDestinations = emptySet(),
                invalidMoveFlash = null,
                hintMessage = null,
                isPaused = false,
                aiThinking = false,
                lastCoinsEarned = null
            ).withMovableSeeds(movableSeedsIn(engine.state))
        }
    }

    /**
     * Launches the computer's move when it is the engine's seat. Every
     * guard runs BEFORE any job is touched so a legal re-entry (e.g. the
     * AI calling back into applyMove) never cancels its own coroutine.
     */
    private fun maybeStartAiTurn() {
        val current = _uiState.value
        if (current.gameMode != GameMode.VS_AI) return
        if (current.isPaused) return
        val state = engine.state
        if (state.currentPlayer != Player.TWO) return
        if (state.phase != GamePhase.PLACEMENT && state.phase != GamePhase.MOVEMENT) return

        val epoch = matchEpoch
        cancelAi()
        _uiState.update { it.copy(aiThinking = true) }
        aiJob = viewModelScope.launch {
            val waitMs = aiDelayMsOverride ?: profile.data.value.thinkSpeed.delayMs
            if (waitMs > 0) delay(waitMs)

            val snapshot = engine.state
            val move = withContext(aiDispatcher) {
                AiPlayer.chooseMove(
                    state = snapshot,
                    rules = engine.movementRules,
                    difficulty = profile.data.value.difficulty,
                    personality = profile.data.value.personality,
                    seed = aiSeedOverride ?: System.nanoTime(),
                    timeBudgetMs = profile.data.value.difficulty.timeBudgetMs,
                )
            }
            // Stale: a new match started or the board changed while thinking.
            if (epoch != matchEpoch || engine.state != snapshot) return@launch

            _uiState.update { it.copy(aiThinking = false) }
            if (move != null) applyMove(move)
        }
    }

    private fun cancelAi() {
        aiJob?.cancel()
        aiJob = null
        if (_uiState.value.aiThinking) {
            _uiState.update { it.copy(aiThinking = false) }
        }
    }

    /** Grants the configured reward exactly once when a match reaches a terminal state. */
    private fun maybeRecordResult() {
        if (matchRecorded) return
        val state = engine.state
        if (state.phase != GamePhase.WON && state.phase != GamePhase.DRAW) return
        matchRecorded = true

        val mode = uiState.value.gameMode
        var earned = 0
        profile.update { current ->
            val updated = Economy.applyResult(current, mode, state.winner, current.difficulty)
            earned = updated.coins - current.coins
            updated
        }
        _uiState.update { it.copy(lastCoinsEarned = earned) }
    }

    private fun selectSeed(position: Position) {
        val destinations = engine.legalDestinations(position)
        _uiState.update {
            it.copy(
                selectedSeed = position,
                legalDestinations = destinations,
                invalidMoveFlash = null,
                hintMessage = if (destinations.isEmpty()) UiHint.BOXED_IN else null
            )
        }
    }

    private fun deselect() {
        _uiState.update { it.copy(selectedSeed = null, legalDestinations = emptySet(), invalidMoveFlash = null, hintMessage = null) }
    }

    /** Marks an illegal tap: keep the selection, flash the point, explain why. */
    private fun flagFeedback(position: Position, hint: UiHint) {
        if (uiState.value.soundEnabled) soundPlayer.playInvalidMove()
        pulseHaptic()
        _uiState.update { it.copy(invalidMoveFlash = position, hintMessage = hint) }
    }

    private fun applyMove(move: Move) {
        val result = engine.apply(move)
        persist()

        val rejection = result.events.filterIsInstance<GameEvent.MoveRejected>().firstOrNull()
        playFeedback(result.events)

        _uiState.update { current ->
            current.copy(
                gameState = result.state,
                selectedSeed = if (rejection != null) current.selectedSeed else null,
                legalDestinations = if (rejection != null) current.legalDestinations else emptySet(),
                invalidMoveFlash = if (rejection != null) tappedPositionOf(move) else null,
                hintMessage = if (rejection != null) hintFor(move, rejection) else null
            ).withMovableSeeds(movableSeedsIn(result.state))
        }

        maybeRecordResult()
        if (rejection == null) maybeStartAiTurn()
    }

    private fun hintFor(move: Move, rejection: GameEvent.MoveRejected): UiHint = when {
        move is Move.Place -> UiHint.OCCUPIED
        rejection.reason.contains("occupied") -> UiHint.OCCUPIED
        else -> UiHint.ILLEGAL_MOVE
    }

    /** Seeds of the player to move that currently have at least one legal destination. */
    private fun movableSeedsIn(state: GameState): Set<Position> {
        if (state.phase != GamePhase.MOVEMENT) return emptySet()
        return state.board.positionsOf(state.currentPlayer)
            .filterTo(mutableSetOf()) { engine.legalDestinations(it).isNotEmpty() }
    }

    private fun tappedPositionOf(move: Move): Position = when (move) {
        is Move.Place -> move.position
        is Move.Relocate -> move.to
    }

    private fun playFeedback(events: List<GameEvent>) {
        val soundOn = _uiState.value.soundEnabled
        for (event in events) {
            when (event) {
                is GameEvent.SeedPlaced -> { if (soundOn) soundPlayer.playSeedPlaced(); pulseHaptic() }
                is GameEvent.SeedMoved -> { if (soundOn) soundPlayer.playSeedMoved(); pulseHaptic() }
                is GameEvent.Won -> { if (soundOn) soundPlayer.playVictory(); pulseHaptic() }
                is GameEvent.Drawn -> pulseHaptic()
                is GameEvent.MoveRejected -> if (soundOn) soundPlayer.playInvalidMove()
            }
        }
    }

    private fun pulseHaptic() {
        if (_uiState.value.hapticsEnabled) _hapticTick.update { it + 1 }
    }

    private fun persist() {
        savedStateHandle[KEY_SAVED_GAME] = GameStateCodec.encode(engine.state)
        // Rules travel with the board: a mid-match settings toggle must
        // not rewrite this match after process death either.
        savedStateHandle[KEY_SAVED_RULES] = engine.movementRules.name
    }

    override fun onCleared() {
        soundPlayer.release()
        super.onCleared()
    }

    private companion object {
        const val KEY_SAVED_GAME = "saved_game_state"
        const val KEY_SAVED_RULES = "saved_rules"
        const val KEY_SAVED_MODE = "saved_mode"
    }
}

private fun GameUiState.withMovableSeeds(movable: Set<Position>): GameUiState =
    if (movable == movableSeeds) this else copy(movableSeeds = movable)
