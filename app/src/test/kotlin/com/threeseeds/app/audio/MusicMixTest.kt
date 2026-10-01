package com.threeseeds.app.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MusicMixTest {

    @Test
    fun `endpoints play exactly one track`() {
        assertEquals(listOf(1f, 0f, 0f), MusicMix.weights(0f).toList())
        assertEquals(listOf(0f, 1f, 0f), MusicMix.weights(0.5f).toList())
        assertEquals(listOf(0f, 0f, 1f), MusicMix.weights(1f).toList())
    }

    @Test
    fun `weights always sum to one and never go negative`() {
        for (step in 0..100) {
            val t = step / 100f
            val w = MusicMix.weights(t)
            assertEquals(1f, w.sum(), 1e-5f, "weights must sum to 1 at t=$t")
            assertTrue(w.all { it >= 0f }, "weights must be non-negative at t=$t")
        }
    }

    @Test
    fun `out of range intensities are clamped`() {
        assertEquals(MusicMix.weights(0f).toList(), MusicMix.weights(-3f).toList())
        assertEquals(MusicMix.weights(1f).toList(), MusicMix.weights(9f).toList())
    }

    @Test
    fun `the crossfade is monotonic toward the higher track`() {
        // Calm fades out while mid rises…
        val a = MusicMix.weights(0.1f)
        val b = MusicMix.weights(0.4f)
        assertTrue(a[0] > b[0], "calm should fade as intensity grows")
        assertTrue(a[1] < b[1], "mid should rise as intensity grows")
        // …and mid fades while high rises.
        val c = MusicMix.weights(0.6f)
        val d = MusicMix.weights(0.9f)
        assertTrue(c[1] > d[1], "mid should fade toward high")
        assertTrue(c[2] < d[2], "high should rise")
    }
}
