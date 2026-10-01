package com.threeseeds.app.profile

import android.content.Context
import android.content.SharedPreferences
import com.threeseeds.engine.ai.AiDifficulty
import com.threeseeds.engine.ai.AiPersonality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How long the computer visibly "thinks" before it moves. */
enum class ThinkSpeed(val delayMs: Long) {
    /** The move lands the moment it is calculated. */
    INSTANT(0),

    /** A natural beat, long enough to read the board. */
    NATURAL(450),

    /** Deliberate pacing that makes higher difficulties feel weighty. */
    THOUGHTFUL(1100)
}

/**
 * The persistent player record: economy, statistics, and preferences
 * that outlive a single match. Pure data — behavior lives in [Economy].
 */
data class ProfileData(
    /** Display name picked on the welcome screen; blank until registered. */
    val playerName: String = "",
    val coins: Int = Economy.STARTING_COINS,
    /** Games won by Player One — the human seat in VS_AI. */
    val wins: Int = 0,
    /** Games won by Player Two — the computer seat in VS_AI. */
    val losses: Int = 0,
    val draws: Int = 0,
    /** Consecutive VS_AI wins; drives the streak bonus. */
    val streak: Int = 0,
    val bestStreak: Int = 0,
    val difficulty: AiDifficulty = AiDifficulty.MEDIUM,
    val personality: AiPersonality = AiPersonality.BALANCED,
    val thinkSpeed: ThinkSpeed = ThinkSpeed.NATURAL,
    val themeId: String = "indigo-night",
    /** When true the board theme rotates through unlocked themes every few seconds. */
    val dynamicThemes: Boolean = true,
    val unlockedThemes: Set<String> = setOf("indigo-night"),
)

/** Observable, updatable profile — implemented in memory for tests and on disk for the app. */
interface ProfileStore {
    val data: StateFlow<ProfileData>
    fun update(transform: (ProfileData) -> ProfileData)
}

/** Non-persistent default: keeps existing constructors and tests working with zero setup. */
class InMemoryProfileStore(initial: ProfileData = ProfileData()) : ProfileStore {
    private val _data = MutableStateFlow(initial)
    override val data: StateFlow<ProfileData> = _data.asStateFlow()
    override fun update(transform: (ProfileData) -> ProfileData) {
        _data.update(transform)
    }
}

/**
 * SharedPreferences-backed store. The profile is a handful of scalars
 * plus a string set — the same reasoning SettingsRepository uses for
 * staying on SharedPreferences rather than adding a DataStore.
 */
class ProfileRepository(context: Context) : ProfileStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _data = MutableStateFlow(load())
    override val data: StateFlow<ProfileData> = _data.asStateFlow()

    override fun update(transform: (ProfileData) -> ProfileData) {
        val next = transform(_data.value)
        _data.value = next
        persist(next)
    }

    private fun load(): ProfileData = ProfileData(
        playerName = prefs.getString(KEY_PLAYER_NAME, null) ?: "",
        coins = prefs.getInt(KEY_COINS, Economy.STARTING_COINS),
        wins = prefs.getInt(KEY_WINS, 0),
        losses = prefs.getInt(KEY_LOSSES, 0),
        draws = prefs.getInt(KEY_DRAWS, 0),
        streak = prefs.getInt(KEY_STREAK, 0),
        bestStreak = prefs.getInt(KEY_BEST_STREAK, 0),
        difficulty = enumOrDefault(prefs.getString(KEY_DIFFICULTY, null), AiDifficulty.MEDIUM),
        personality = enumOrDefault(prefs.getString(KEY_PERSONALITY, null), AiPersonality.BALANCED),
        thinkSpeed = enumOrDefault(prefs.getString(KEY_THINK_SPEED, null), ThinkSpeed.NATURAL),
        themeId = prefs.getString(KEY_THEME, null) ?: ProfileData().themeId,
        dynamicThemes = prefs.getBoolean(KEY_DYNAMIC_THEMES, true),
        unlockedThemes = prefs.getStringSet(KEY_UNLOCKED, null)
            ?.let { if (it.isEmpty()) null else it.toSet() }
            ?: ProfileData().unlockedThemes,
    )

    private fun persist(p: ProfileData) {
        prefs.edit()
            .putString(KEY_PLAYER_NAME, p.playerName)
            .putInt(KEY_COINS, p.coins)
            .putInt(KEY_WINS, p.wins)
            .putInt(KEY_LOSSES, p.losses)
            .putInt(KEY_DRAWS, p.draws)
            .putInt(KEY_STREAK, p.streak)
            .putInt(KEY_BEST_STREAK, p.bestStreak)
            .putString(KEY_DIFFICULTY, p.difficulty.name)
            .putString(KEY_PERSONALITY, p.personality.name)
            .putString(KEY_THINK_SPEED, p.thinkSpeed.name)
            .putString(KEY_THEME, p.themeId)
            .putBoolean(KEY_DYNAMIC_THEMES, p.dynamicThemes)
            .putStringSet(KEY_UNLOCKED, p.unlockedThemes)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    private companion object {
        const val PREFS_NAME = "three_seeds_profile"
        const val KEY_PLAYER_NAME = "player_name"
        const val KEY_COINS = "coins"
        const val KEY_WINS = "wins"
        const val KEY_LOSSES = "losses"
        const val KEY_DRAWS = "draws"
        const val KEY_STREAK = "streak"
        const val KEY_BEST_STREAK = "best_streak"
        const val KEY_DIFFICULTY = "difficulty"
        const val KEY_PERSONALITY = "personality"
        const val KEY_THINK_SPEED = "think_speed"
        const val KEY_THEME = "theme_id"
        const val KEY_DYNAMIC_THEMES = "dynamic_themes"
        const val KEY_UNLOCKED = "unlocked_themes"
    }
}
