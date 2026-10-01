package com.threeseeds.app.state

import com.threeseeds.engine.GamePhase
import com.threeseeds.engine.GameState
import com.threeseeds.engine.Player
import com.threeseeds.engine.Position

/** Why a tap did nothing — shown by the game screen as a short coach message. */
enum class UiHint {
    /** Empty point, but the line to it is blocked under Tapatan-style rules. */
    BLOCKED_PATH,

    /** The player tapped the opponent's seed. */
    OPPONENT_SEED,

    /** The selected seed has no legal destination at all. */
    BOXED_IN,

    /** Placement on an already occupied point. */
    OCCUPIED,

    /** The engine rejected the move for another reason. */
    ILLEGAL_MOVE
}

/**
 * Everything the UI needs to render a frame. [gameState] is the
 * authoritative engine snapshot; everything else here is ephemeral UI
 * state that has no business living inside the engine (a "selected
 * seed" for the tap-then-tap movement flow is a local interaction
 * detail, not part of the game's rules).
 */
data class GameUiState(
    val gameState: GameState = GameState(),
    val selectedSeed: Position? = null,
    val legalDestinations: Set<Position> = emptySet(),
    /** Own seeds that currently have at least one legal destination — dimmed/highlighted by the board. */
    val movableSeeds: Set<Position> = emptySet(),
    /** Transient coach message shown after an illegal tap; cleared with [invalidMoveFlash]. */
    val hintMessage: UiHint? = null,
    /** False = any vacant point (standard rule); true = drawn lines only. The Settings preference. */
    val adjacentMovementOnly: Boolean = false,
    /** The ruleset THIS match was started with — fixed for the match's duration. */
    val matchAdjacentMovementOnly: Boolean = false,
    /** Who sits at the board. In VS_AI, Player Two is the engine. */
    val gameMode: GameMode = GameMode.PASS_AND_PLAY,
    /** True while the computer is choosing its move; taps are ignored. */
    val aiThinking: Boolean = false,
    /** Coins awarded when the current match ended; shown on the end overlay, cleared on restart. */
    val lastCoinsEarned: Int? = null,
    val isPaused: Boolean = false,
    val invalidMoveFlash: Position? = null,
    val soundEnabled: Boolean = true,
    val musicEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val debugModeEnabled: Boolean = false,
    /** Nearby play: link lifecycle (see [LinkStatus]). */
    val linkStatus: LinkStatus = LinkStatus.NONE,
    /** Nearby play: the other player's display name, once known. */
    val peerName: String? = null,
    /** Nearby play: this device's seat; null when not in a nearby match. */
    val mySeat: Player? = null
) {
    /**
     * Soundtrack intensity (0..1), derived purely from the board: the
     * menu and early placement ride the calm track, the score builds
     * as seeds land and movement drags on, and a decided match hits
     * full tension. The music layer tweens toward this on every state
     * change, so the soundtrack breathes with the match.
     */
    val musicIntensity: Float
        get() = when (gameState.phase) {
            GamePhase.WON -> 1f
            GamePhase.DRAW -> 0.85f
            GamePhase.PLACEMENT -> {
                val placed = (GameState.SEEDS_PER_PLAYER * 2) - gameState.seedsRemaining.values.sum()
                0.08f + 0.47f * (placed / 6f).coerceIn(0f, 1f)
            }
            GamePhase.MOVEMENT -> {
                // history = start + 6 placements + movement half-moves.
                val moves = (gameState.history.size - 7).coerceAtLeast(0)
                0.55f + 0.40f * (moves / 10f).coerceAtMost(1f)
            }
        }
}
