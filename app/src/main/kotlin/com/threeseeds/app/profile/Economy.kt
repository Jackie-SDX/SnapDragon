package com.threeseeds.app.profile

import com.threeseeds.app.state.GameMode
import com.threeseeds.engine.Player
import com.threeseeds.engine.ai.AiDifficulty

/**
 * Pure reward and progression math — no Android, no persistence, so
 * every rule here is unit-testable in isolation.
 *
 * Design intent: rewards scale with the difficulty the player chose to
 * face (beating MASTER is worth four times beating BEGINNER), draws pay
 * a little (the player still invested the match), and losses pay a
 * token amount so a losing streak never feels like zero progress.
 */
object Economy {

    /** Coins every new profile starts with — enough to buy a mid-tier theme. */
    const val STARTING_COINS = 100

    /** Consecutive-win bonus caps here so the economy can't be exploited forever. */
    private const val MAX_STREAK_BONUS_STREAK = 8

    /**
     * Total coins awarded for finishing one match.
     * [winner] is null for a draw. In VS_AI the human plays Player One.
     */
    fun reward(
        profile: ProfileData,
        mode: GameMode,
        winner: Player?,
        difficulty: AiDifficulty,
    ): Int {
        val base = when (mode) {
            GameMode.VS_AI -> when {
                winner == Player.ONE -> 10 + 6 * difficulty.ordinal // 10..40
                winner == null -> 8
                else -> 2
            }

            GameMode.PASS_AND_PLAY -> when (winner) {
                Player.ONE -> 8
                Player.TWO -> 4
                null -> 5
            }

            GameMode.NEARBY_HOST, GameMode.NEARBY_GUEST -> when {
                winner == null -> 5
                winner == seatOf(mode) -> 8
                else -> 4
            }
        }
        if (mode == GameMode.VS_AI && winner == Player.ONE) {
            val newStreak = profile.streak + 1
            return base + minOf(newStreak, MAX_STREAK_BONUS_STREAK) * 2
        }
        return base
    }

    /**
     * The full result of one finished match applied to the profile:
     * coins, win/loss/draw counters, and streak bookkeeping.
     * Returns a new value; never mutates its argument.
     */
    fun applyResult(
        profile: ProfileData,
        mode: GameMode,
        winner: Player?,
        difficulty: AiDifficulty,
    ): ProfileData {
        val gained = reward(profile, mode, winner, difficulty)
        val newStreak = if (mode == GameMode.VS_AI && winner == Player.ONE) profile.streak + 1 else 0
        // In nearby play this device owns a seat, so "win" means MY seat
        // won; every other mode keeps the device in Player One's seat.
        val iWon = if (mode.isNearby) winner == seatOf(mode) else winner == Player.ONE
        return profile.copy(
            coins = profile.coins + gained,
            wins = profile.wins + if (iWon) 1 else 0,
            losses = profile.losses + if (winner != null && !iWon) 1 else 0,
            draws = profile.draws + if (winner == null) 1 else 0,
            streak = newStreak,
            bestStreak = maxOf(profile.bestStreak, newStreak),
        )
    }

    /** The seat this device plays in a nearby match. */
    private fun seatOf(mode: GameMode): Player =
        if (mode == GameMode.NEARBY_HOST) Player.ONE else Player.TWO

    /** True when [themeCost] can be paid from [profile] without going negative. */
    fun canAfford(profile: ProfileData, themeCost: Int): Boolean =
        profile.coins >= themeCost

    /**
     * Buys [themeId] for [themeCost]: deducts the price and unlocks the
     * theme. Returns null when it is unaffordable or already owned —
     * the caller never has to check twice.
     */
    fun buyTheme(profile: ProfileData, themeId: String, themeCost: Int): ProfileData? {
        if (themeId in profile.unlockedThemes) return null
        if (!canAfford(profile, themeCost)) return null
        return profile.copy(
            coins = profile.coins - themeCost,
            unlockedThemes = profile.unlockedThemes + themeId,
        )
    }
}
