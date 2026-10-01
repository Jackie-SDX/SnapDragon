package com.threeseeds.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threeseeds.app.audio.SoundPlayer
import com.threeseeds.app.net.LinkKind
import com.threeseeds.app.net.LinkPeer
import com.threeseeds.app.net.LinkSession
import com.threeseeds.app.net.StreamLineTransport
import com.threeseeds.app.net.WifiLan
import com.threeseeds.app.net.asLineTransport
import com.threeseeds.app.profile.Economy
import com.threeseeds.app.profile.InMemoryProfileStore
import com.threeseeds.app.profile.ProfileStore
import com.threeseeds.app.settings.SettingsStore
import com.threeseeds.app.state.GameMode
import com.threeseeds.app.state.GameStateCodec
import com.threeseeds.app.state.GameUiState
import com.threeseeds.app.state.LinkStatus
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
import java.io.Closeable

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
    /** Name this device advertises in nearby play. */
    private val localName: () -> String = { "Player" },
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

    // ---- Nearby play (WiFi / Bluetooth) -----------------------------------
    /** The live link session once a nearby match is connected; null in every other mode. */
    private var link: LinkSession? = null

    /** Running host listener or scan; closed by leaveLink(). */
    private var linkHandle: Closeable? = null

    /** Host-side ruleset frozen at match start; guest-side adopted from WELCOME. */
    private var networkRules: MovementRules? = null

    private val _uiState = MutableStateFlow(
        GameUiState(
            gameState = engine.state,
            movableSeeds = movableSeedsIn(engine.state),
            adjacentMovementOnly = settings.adjacentMovementOnly,
            matchAdjacentMovementOnly = matchRules == MovementRules.TAPATAN,
            gameMode = initialMode,
            soundEnabled = settings.soundEnabled,
            musicEnabled = settings.musicEnabled,
            hapticsEnabled = settings.hapticsEnabled,
            debugModeEnabled = settings.debugModeEnabled,
            // A nearby match cannot survive process death: sockets are gone,
            // so the restored board reports the link as lost.
            linkStatus = if (initialMode.isNearby) LinkStatus.LOST else LinkStatus.NONE,
            mySeat = when (initialMode) {
                GameMode.NEARBY_HOST -> Player.ONE
                GameMode.NEARBY_GUEST -> Player.TWO
                else -> null
            }
        )
    )
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    /** Discovered nearby hosts, refreshed while scanning. */
    private val _peers = MutableStateFlow<List<LinkPeer>>(emptyList())
    val peers: StateFlow<List<LinkPeer>> = _peers.asStateFlow()

    /** Last lobby-level error (permission, adapter off, dial failed). */
    private val _linkError = MutableStateFlow<String?>(null)
    val linkError: StateFlow<String?> = _linkError.asStateFlow()

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

        // Nearby play: each device only touches its own seat's turn.
        if (uiState.value.gameMode == GameMode.NEARBY_HOST) {
            if (uiState.value.linkStatus != LinkStatus.CONNECTED) return
            if (engine.state.currentPlayer != Player.ONE) return
        }
        if (uiState.value.gameMode == GameMode.NEARBY_GUEST) {
            if (uiState.value.linkStatus != LinkStatus.CONNECTED) return
            if (engine.state.currentPlayer != Player.TWO) return
            // The guest decides locally (selection UX stays instant) but the
            // host owns the engine: an actual move becomes a wire message.
            decideTap(position)?.let { move -> link?.sendTap(tappedPositionOf(move).index) }
            return
        }

        decideTap(position)?.let { applyMove(it) }
    }

    /**
     * Runs the tap-then-tap interaction for the CURRENT player and
     * returns the engine move to apply (or null for select/deselect/
     * rejected taps). Side effects — selection, flashes, sounds — happen
     * here; committing the move is the caller's job (local apply, host
     * apply, or guest send).
     */
    private fun decideTap(position: Position): Move? {
        val state = _uiState.value
        val gameState = state.gameState

        return when {
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
    }

    fun undo() {
        // Nearby play is host-authoritative; an undo that only one side
        // performs would desync the boards, so it is off entirely.
        if (uiState.value.gameMode.isNearby) return
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

    fun setMusicEnabled(enabled: Boolean) {
        settings.musicEnabled = enabled
        _uiState.update { it.copy(musicEnabled = enabled) }
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

    // ---------------------------------------------------------------------
    // Nearby play (WiFi LAN / Bluetooth): lobby control and session wiring
    // ---------------------------------------------------------------------

    /** Lobby → Host tab: start listening for one guest on [kind]. */
    fun startHosting(kind: LinkKind) {
        clearLinkHandles()
        _linkError.value = null
        _peers.value = emptyList()
        _uiState.update { it.copy(linkStatus = LinkStatus.HOSTING, peerName = null, mySeat = null) }
        when (kind) {
            LinkKind.WIFI -> linkHandle = WifiLan.startHost(localName()) { socket ->
                viewModelScope.launch { hostAccepted(socket.asLineTransport()) }
            }

            LinkKind.BLUETOOTH -> linkHandle = com.threeseeds.app.net.BluetoothLinks.startHost({ socket ->
                viewModelScope.launch {
                    hostAccepted(
                        StreamLineTransport(socket.inputStream, socket.outputStream) { socket.close() },
                    )
                }
            }, { error -> linkFailed(error) })
        }
    }

    /** Lobby → Join tab: look for hosts on [kind]; results land in [peers]. */
    fun scanPeers(kind: LinkKind) {
        clearLinkHandles()
        _linkError.value = null
        _peers.value = emptyList()
        _uiState.update { it.copy(linkStatus = LinkStatus.SCANNING, peerName = null, mySeat = null) }
        when (kind) {
            LinkKind.WIFI -> linkHandle = WifiLan.scan { peer -> publishPeer(peer) }
            LinkKind.BLUETOOTH -> linkHandle = com.threeseeds.app.net.BluetoothLinks.startScan(
                onBonded = { device -> onBluetoothDeviceFound(device.name, device.address) },
                onError = { error -> linkFailed(error) },
            )
        }
    }

    /** A Bluetooth device arrived via ACTION_FOUND (the lobby screen owns the receiver). */
    fun onBluetoothDeviceFound(name: String?, address: String) {
        if (_uiState.value.linkStatus != LinkStatus.SCANNING) return
        publishPeer(
            LinkPeer(
                name = name?.takeIf { it.isNotBlank() } ?: "Nearby device",
                kind = LinkKind.BLUETOOTH,
                id = address,
                hostAddress = null,
                hostPort = 0,
                bluetoothAddress = address,
            )
        )
    }

    /** Lobby → Join: dial a discovered host and start the handshake. */
    fun joinPeer(peer: LinkPeer) {
        if (_uiState.value.linkStatus == LinkStatus.CONNECTING) return
        _linkError.value = null
        _uiState.update { it.copy(linkStatus = LinkStatus.CONNECTING) }
        when (peer.kind) {
            LinkKind.WIFI -> WifiLan.connect(
                peer,
                onConnected = { socket -> viewModelScope.launch { guestConnected(socket.asLineTransport()) } },
                onError = { error -> linkFailed(error) },
            )

            LinkKind.BLUETOOTH -> {
                val device = com.threeseeds.app.net.BluetoothLinks.deviceFor(peer.bluetoothAddress ?: "")
                if (device == null) {
                    linkFailed(IllegalStateException("Device not found"))
                    return
                }
                com.threeseeds.app.net.BluetoothLinks.connect(
                    device,
                    onConnected = { socket ->
                        viewModelScope.launch {
                            guestConnected(
                                StreamLineTransport(socket.inputStream, socket.outputStream) { socket.close() }
                            )
                        }
                    },
                    onError = { error -> linkFailed(error) },
                )
            }
        }
    }

    /**
     * Closes the link and resets the lobby — called when leaving a
     * nearby match (and harmless in every other mode).
     */
    fun leaveLink() {
        link?.setListener(null)
        link?.sendBye()
        link?.close()
        link = null
        clearLinkHandles()
        networkRules = null
        _peers.value = emptyList()
        _linkError.value = null
        _uiState.update { it.copy(linkStatus = LinkStatus.NONE, peerName = null, mySeat = null) }
    }

    fun clearLinkError() {
        _linkError.value = null
    }

    private fun hostAccepted(transport: StreamLineTransport) {
        val session = LinkSession(
            isHost = true,
            transport = transport,
            localName = localName(),
            hostRules = { (networkRules ?: settings.movementRules).name },
        )
        wireSession(session)
    }

    private fun guestConnected(transport: StreamLineTransport) {
        val session = LinkSession(isHost = false, transport = transport, localName = localName())
        wireSession(session)
    }

    /**
     * Test seam: production builds sessions inside hostAccepted /
     * guestConnected; tests hand in a session over an in-memory pair.
     */
    internal fun attachSession(session: LinkSession) = wireSession(session)

    private fun wireSession(session: LinkSession) {
        session.setListener(object : LinkSession.Listener {
            override fun onConnected(peerName: String, rules: String) {
                val isHost = session.isHost
                viewModelScope.launch {
                    if (!isHost) {
                        networkRules = runCatching { MovementRules.valueOf(rules) }.getOrNull()
                    }
                    _uiState.update {
                        it.copy(
                            linkStatus = LinkStatus.CONNECTED,
                            peerName = peerName,
                            mySeat = if (isHost) Player.ONE else Player.TWO,
                        )
                    }
                    // A NEW connection always means a fresh board — resuming
                    // a leftover match from a previous session would hand the
                    // guest a desynced board from the very first move.
                    newMatch(if (isHost) GameMode.NEARBY_HOST else GameMode.NEARBY_GUEST)
                }
            }

            override fun onGuestTap(index: Int) {
                viewModelScope.launch {
                    if (uiState.value.gameMode != GameMode.NEARBY_HOST) return@launch
                    if (uiState.value.linkStatus != LinkStatus.CONNECTED) return@launch
                    if (engine.state.currentPlayer != Player.TWO) return@launch
                    decideTap(Position(index))?.let { applyMove(it) }
                }
            }

            override fun onState(encoded: String) {
                viewModelScope.launch { applyRemoteState(encoded) }
            }

            override fun onRematch() {
                viewModelScope.launch {
                    if (session.isHost) restart() else newMatch(GameMode.NEARBY_GUEST)
                }
            }

            override fun onDisconnected(reason: String?) {
                viewModelScope.launch {
                    link = null
                    networkRules = null
                    val status = _uiState.value.linkStatus
                    when {
                        // Mid-match drop: keep the board, mark the link lost.
                        status == LinkStatus.CONNECTED ->
                            _uiState.update { it.copy(linkStatus = LinkStatus.LOST) }

                        // A dead pre-handshake session while hosting is not a
                        // failure — keep waiting for the next guest.
                        status == LinkStatus.HOSTING -> Unit

                        status == LinkStatus.SCANNING || status == LinkStatus.CONNECTING ->
                            _uiState.update { it.copy(linkStatus = LinkStatus.NONE) }
                    }
                    if (reason != null) _linkError.value = reason
                }
            }
        })
        link = session
        session.start()
    }

    /** Guest: adopt an authoritative host snapshot wholesale. */
    private fun applyRemoteState(encoded: String) {
        val before = engine.state
        val after = GameStateCodec.decode(encoded)
        engine.restore(after)
        persist()

        val soundOn = _uiState.value.soundEnabled
        when {
            before.phase != GamePhase.WON && after.phase == GamePhase.WON -> {
                if (soundOn) soundPlayer.playVictory()
                pulseHaptic()
            }

            after.history.size > before.history.size -> {
                if (soundOn) {
                    if (after.phase == GamePhase.PLACEMENT) soundPlayer.playSeedPlaced()
                    else soundPlayer.playSeedMoved()
                }
                pulseHaptic()
            }
        }

        _uiState.update {
            it.copy(
                gameState = after,
                selectedSeed = null,
                legalDestinations = emptySet(),
                invalidMoveFlash = null,
                hintMessage = null,
            ).withMovableSeeds(movableSeedsIn(after))
        }
        maybeRecordResult()
    }

    private fun publishPeer(peer: LinkPeer) {
        _peers.update { list ->
            if (list.any { it.id == peer.id }) list.map { if (it.id == peer.id) peer else it }
            else list + peer
        }
    }

    private fun linkFailed(error: Throwable) {
        viewModelScope.launch {
            clearLinkHandles()
            _uiState.update { it.copy(linkStatus = LinkStatus.ERROR) }
            _linkError.value = error.message ?: "Connection failed"
        }
    }

    private fun clearLinkHandles() {
        linkHandle?.close()
        linkHandle = null
    }

    private fun newMatch(mode: GameMode) {
        matchEpoch++
        cancelAi()
        matchRecorded = false
        // Nearby matches carry their own frozen ruleset: the host keeps
        // the one frozen at its start (or adopts the same value on every
        // rematch); the guest uses what WELCOME delivered.
        engine.movementRules = networkRules ?: settings.movementRules
        if (mode == GameMode.NEARBY_HOST) networkRules = engine.movementRules
        if (!mode.isNearby) networkRules = null
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

        // A fresh board must reach the guest: this covers match start,
        // the host's Restart, and REMATCH after the overlay. REMATCH goes
        // first so the guest clears its per-match bookkeeping (coins
        // awarded, recorded flag) before mirroring the new board.
        if (mode == GameMode.NEARBY_HOST) {
            link?.sendRematch()
            link?.sendState(GameStateCodec.encode(engine.state))
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

        // Host broadcast: every accepted change goes out verbatim; a
        // rejected tap changes nothing, so the guest's own selection
        // survives for its retry (it never saw the rejection).
        if (rejection == null && uiState.value.gameMode == GameMode.NEARBY_HOST) {
            link?.sendState(GameStateCodec.encode(result.state))
        }
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
