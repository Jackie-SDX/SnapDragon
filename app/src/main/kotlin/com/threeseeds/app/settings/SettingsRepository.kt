package com.threeseeds.app.settings

import android.content.Context
import android.content.SharedPreferences
import com.threeseeds.engine.MovementRules

interface SettingsStore {
    var soundEnabled: Boolean

    /** Looped CC0 soundtrack bundled with the app; on by default. */
    var musicEnabled: Boolean
    var hapticsEnabled: Boolean
    var debugModeEnabled: Boolean

    /**
     * When true, seeds may only slide along drawn lines (Murray's
     * Tapatan-style variant). Off by default: the standard rule for
     * three men's morris allows a move to any vacant point.
     */
    var adjacentMovementOnly: Boolean

    /** Derived view of [adjacentMovementOnly] in engine terms. */
    val movementRules: MovementRules
        get() = if (adjacentMovementOnly) MovementRules.TAPATAN else MovementRules.FREE
}

/**
 * Three booleans don't justify a DataStore dependency; SharedPreferences
 * is part of the platform already and is more than adequate here.
 */
class SettingsRepository(context: Context) : SettingsStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND, value).apply()

    override var musicEnabled: Boolean
        get() = prefs.getBoolean(KEY_MUSIC, true)
        set(value) = prefs.edit().putBoolean(KEY_MUSIC, value).apply()

    override var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    override var debugModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEBUG, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG, value).apply()

    override var adjacentMovementOnly: Boolean
        get() = prefs.getBoolean(KEY_ADJACENT, false)
        set(value) = prefs.edit().putBoolean(KEY_ADJACENT, value).apply()

    private companion object {
        const val PREFS_NAME = "three_seeds_settings"
        const val KEY_SOUND = "sound_enabled"
        const val KEY_MUSIC = "music_enabled"
        const val KEY_HAPTICS = "haptics_enabled"
        const val KEY_DEBUG = "debug_mode_enabled"
        const val KEY_ADJACENT = "adjacent_movement_only"
    }
}
