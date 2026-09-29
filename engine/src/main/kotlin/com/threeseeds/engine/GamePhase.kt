package com.threeseeds.engine

/**
 * The engine's own phases. "Main Menu" and "Restart", from the product
 * spec's state list, are app-navigation concerns owned by the UI layer
 * (which creates or resets a GameEngine instance) — the engine itself
 * only models phases that affect move legality.
 */
enum class GamePhase {
    PLACEMENT,
    MOVEMENT,
    WON,
    DRAW
}
