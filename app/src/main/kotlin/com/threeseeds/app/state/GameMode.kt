package com.threeseeds.app.state

/** Who sits at each side of the board for the current match. */
enum class GameMode {
    /** Two humans sharing the device. */
    PASS_AND_PLAY,

    /** Human is Player One; the engine plays Player Two. */
    VS_AI,

    /** This device hosts a nearby match and plays Player One. */
    NEARBY_HOST,

    /** This device joined a nearby match and plays Player Two. */
    NEARBY_GUEST;

    val isNearby: Boolean
        get() = this == NEARBY_HOST || this == NEARBY_GUEST
}
