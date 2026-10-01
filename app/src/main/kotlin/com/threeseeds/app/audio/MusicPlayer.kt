package com.threeseeds.app.audio

import android.content.Context
import android.media.MediaPlayer
import com.threeseeds.app.R

/**
 * Looped background soundtrack ("A New Town" by The Cynic Project,
 * CC0 — credited in About). One MediaPlayer for the activity's
 * lifetime: [setEnabled] follows the Settings toggle, [play]/[pause]
 * follow the activity lifecycle so the music never keeps playing
 * behind another app. Failures degrade to silence, not crashes.
 */
class MusicPlayer(context: Context, enabled: Boolean) {

    private var player: MediaPlayer? =
        runCatching {
            MediaPlayer.create(context.applicationContext, R.raw.a_new_town)?.apply {
                isLooping = true
                setVolume(VOLUME, VOLUME)
            }
        }.getOrNull()

    var enabled: Boolean = enabled
        private set

    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) play() else pause()
    }

    fun play() {
        if (!enabled) return
        runCatching {
            player?.takeIf { !it.isPlaying }?.start()
        }
    }

    fun pause() {
        runCatching { player?.pause() }
    }

    fun release() {
        runCatching { player?.release() }
        player = null
    }

    private companion object {
        /** Under the sound effects, not over them. */
        const val VOLUME = 0.45f
    }
}
