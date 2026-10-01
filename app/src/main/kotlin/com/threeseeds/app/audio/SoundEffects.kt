package com.threeseeds.app.audio

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import com.threeseeds.app.R

interface SoundPlayer {
    fun playSeedPlaced()
    fun playSeedMoved()
    fun playInvalidMove()
    fun playVictory()
    /** The computer won: a darker sting than [playVictory]. Default no-op keeps simple test fakes compiling. */
    fun playAiVictory() {}
    fun release()
}

/**
 * ToneGenerator synthesizes the move tones on the fly — no asset to
 * source, license, or bundle — while the computer's win plays a
 * bundled CC0 cackle ("Evil Laugh #2" by antumdeluge, credited in
 * About) so losing to the machine lands with a personality. Each
 * event gets a distinct tone (see ToneGenerator docs) so they're
 * distinguishable by ear, not just present/absent.
 */
class SoundEffects(context: Context) : SoundPlayer {

    private val appContext = context.applicationContext

    private var generator: ToneGenerator? = null

    private var aiWin: MediaPlayer? = runCatching {
        MediaPlayer.create(appContext, R.raw.ai_win)?.apply { setVolume(0.9f, 0.9f) }
    }.getOrNull()

    private fun get(): ToneGenerator =
        generator ?: ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME).also { generator = it }

    override fun playSeedPlaced() = safePlay { get().startTone(ToneGenerator.TONE_PROP_BEEP, 80) }
    override fun playSeedMoved() = safePlay { get().startTone(ToneGenerator.TONE_PROP_BEEP2, 120) }
    override fun playInvalidMove() = safePlay { get().startTone(ToneGenerator.TONE_PROP_NACK, 150) }
    override fun playVictory() = safePlay { get().startTone(ToneGenerator.TONE_PROP_ACK, 300) }

    override fun playAiVictory() = safePlay {
        aiWin?.takeIf { !it.isPlaying }?.apply {
            seekTo(0)
            start()
        }
    }

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
        runCatching { aiWin?.release() }
        aiWin = null
    }

    private companion object {
        const val VOLUME = 70 // 0-100
    }
}
