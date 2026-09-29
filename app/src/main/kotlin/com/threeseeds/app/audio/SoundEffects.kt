package com.threeseeds.app.audio

import android.media.AudioManager
import android.media.ToneGenerator

interface SoundPlayer {
    fun playSeedPlaced()
    fun playSeedMoved()
    fun playInvalidMove()
    fun playVictory()
    fun release()
}

/**
 * Deliberately asset-free: ToneGenerator synthesizes these tones on the
 * fly, so there's no audio file to source, license, or bundle. Each
 * event gets a distinct proprietary tone (see ToneGenerator docs) so
 * they're distinguishable by ear, not just present/absent.
 */
class SoundEffects : SoundPlayer {

    private var generator: ToneGenerator? = null

    private fun get(): ToneGenerator =
        generator ?: ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME).also { generator = it }

    override fun playSeedPlaced() = safePlay { get().startTone(ToneGenerator.TONE_PROP_BEEP, 80) }
    override fun playSeedMoved() = safePlay { get().startTone(ToneGenerator.TONE_PROP_BEEP2, 120) }
    override fun playInvalidMove() = safePlay { get().startTone(ToneGenerator.TONE_PROP_NACK, 150) }
    override fun playVictory() = safePlay { get().startTone(ToneGenerator.TONE_PROP_ACK, 300) }

    /** ToneGenerator can throw RuntimeException if the audio track can't be allocated; a missed sound effect is not worth crashing over. */
    private inline fun safePlay(action: () -> Unit) {
        try {
            action()
        } catch (_: RuntimeException) {
        }
    }

    override fun release() {
        generator?.release()
        generator = null
    }

    private companion object {
        const val VOLUME = 70 // 0-100
    }
}
