package com.threeseeds.engine.ai

/**
 * Style, deliberately separate from [AiDifficulty]. A personality only
 * re-weights the evaluation and adds variety — it never changes the
 * search depth, so an Aggressive Master plays at Master strength with
 * a racing style, not at a different strength. Forced tactics (an
 * opponent's immediate win) are found by the search itself and are
 * therefore respected by every personality.
 */
enum class AiPersonality(
    val threatWeight: Float,
    val blockWeight: Float,
    val centerWeight: Float,
    val mobilityWeight: Float,
    val noiseScale: Float,
) {
    /** Even-handed: values its own threats, blocks, centre and mobility equally. */
    BALANCED(1.0f, 1.0f, 1.0f, 1.0f, 1.0f),

    /** Builds threats eagerly; blocks a little less promptly when free to do so. */
    AGGRESSIVE(1.6f, 0.7f, 1.1f, 1.0f, 1.4f),

    /** Cautious: cares most about never leaving a threat standing. */
    DEFENSIVE(0.7f, 1.5f, 1.0f, 1.2f, 0.8f),

    /** Corrals the seeds: centre control and freedom of movement first. */
    POSITIONAL(1.0f, 1.0f, 2.0f, 1.8f, 1.0f),

    /** Wider taste and wider mood swings — fun to play against. */
    EXPERIMENTAL(1.3f, 1.0f, 1.3f, 1.2f, 2.5f)
}
