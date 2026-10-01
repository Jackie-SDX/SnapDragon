package com.threeseeds.app.audio

/**
 * Pure soundtrack mixing: maps a 0..1 game intensity to volumes for
 * the three bundled vocal tracks (calm → mid → high). Kept free of
 * Android types so the crossfade curve is unit-testable on the JVM.
 */
object MusicMix {

    /** Track resource ids, ordered calm → mid → high. */
    const val TRACK_COUNT = 3

    /**
     * Linear piecewise crossfade: intensity 0 plays the calm track,
     * 0.5 the mid track, 1.0 the high track, with exactly two tracks
     * audible during each transition. Weights always sum to 1.
     */
    fun weights(intensity: Float): FloatArray {
        val t = intensity.coerceIn(0f, 1f)
        return if (t <= 0.5f) {
            val mid = t * 2f
            floatArrayOf(1f - mid, mid, 0f)
        } else {
            val high = (t - 0.5f) * 2f
            floatArrayOf(0f, 1f - high, high)
        }
    }
}
