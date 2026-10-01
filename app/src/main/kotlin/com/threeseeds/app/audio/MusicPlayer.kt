package com.threeseeds.app.audio

import android.animation.ValueAnimator
import android.content.Context
import android.media.MediaPlayer
import android.view.animation.AccelerateDecelerateInterpolator
import com.threeseeds.app.R

/**
 * Dynamic soundtrack: three real vocal songs (all credited in About)
 * mixed by game intensity. [MusicMix.weights] turns a 0..1 intensity
 * into per-track volumes and [setIntensity] tweens between mixes, so
 * the score swells from the calm track toward the tense one as the
 * match heats up instead of hard-switching.
 *
 * One MediaPlayer per track for the activity's lifetime: [setEnabled]
 * follows the Settings toggle, [play]/[pause] follow the activity
 * lifecycle so the music never keeps playing behind another app.
 * Failures degrade to silence, not crashes — a track that fails to
 * load simply drops out and the others share its volume.
 */
class MusicPlayer(context: Context, enabled: Boolean) {

    private val players: List<MediaPlayer?> = listOf(
        R.raw.music_calm,
        R.raw.music_mid,
        R.raw.music_high,
    ).map { res ->
        runCatching {
            MediaPlayer.create(context.applicationContext, res)?.apply {
                isLooping = true
                setVolume(0f, 0f)
            }
        }.getOrNull()
    }

    private var intensity = 0f
    private var ducked = false
    private var animator: ValueAnimator? = null

    var enabled: Boolean = enabled
        private set

    init {
        applyMix(intensity)
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) play() else pause()
    }

    /**
     * Retarget the mix toward [value] (0..1) with a smooth tween.
     * Called on every meaningful game-state change; an unchanged
     * target is a no-op, and a moving target re-tweens from the
     * current mix so rapid state changes glide instead of jumping.
     */
    fun setIntensity(value: Float) {
        val target = value.coerceIn(0f, 1f)
        if (target == intensity) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(intensity, target).apply {
            duration = CROSSFADE_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                intensity = it.animatedValue as Float
                applyMix(intensity)
            }
            start()
        }
    }

    /**
     * Duck the score (victory sting, dialog): the mix keeps its shape
     * but drops to a background level while [value] is true.
     */
    fun setDucked(value: Boolean) {
        if (ducked == value) return
        ducked = value
        applyMix(intensity)
    }

    fun play() {
        if (!enabled) return
        players.forEach { player ->
            runCatching { player?.takeIf { !it.isPlaying }?.start() }
        }
    }

    fun pause() {
        animator?.cancel()
        players.forEach { player -> runCatching { player?.pause() } }
    }

    fun release() {
        animator?.cancel()
        animator = null
        players.forEach { player -> runCatching { player?.release() } }
    }

    /** Push the mix to the players, redistributing around any that failed to load. */
    private fun applyMix(t: Float) {
        val wanted = MusicMix.weights(t)
        val live = players.indices.filter { players[it] != null }
        val liveSum = live.sumOf { wanted[it].toDouble() }.toFloat()
        val duck = if (ducked) DUCK_FACTOR else 1f
        players.forEachIndexed { index, player ->
            if (player == null) return@forEachIndexed
            val volume = if (liveSum > 0f) wanted[index] / liveSum * VOLUME * duck else 0f
            runCatching { player.setVolume(volume, volume) }
        }
    }

    private companion object {
        /** Under the sound effects, not over them. */
        const val VOLUME = 0.45f

        /** Same window as the theme crossfade — one "mood change" language. */
        const val CROSSFADE_MS = 1600L

        /** How far the score drops while a sting owns the moment. */
        const val DUCK_FACTOR = 0.3f
    }
}
