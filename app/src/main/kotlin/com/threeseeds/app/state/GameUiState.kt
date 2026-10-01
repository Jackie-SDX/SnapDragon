package com.threeseeds.app.state

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
)
