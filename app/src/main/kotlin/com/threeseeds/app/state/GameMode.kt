package com.threeseeds.app.state

/** Who sits at each side of the board for the current match. */
enum class GameMode {
    /** Two humans sharing the device. */
    PASS_AND_PLAY,

    /** Human is Player One; the engine plays Player Two. */
    VS_AI
}
