package com.threeseeds.app.profile

import com.threeseeds.app.state.GameMode
import com.threeseeds.engine.Player
import com.threeseeds.engine.ai.AiDifficulty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole economy is pure math on a [ProfileData], so every rule the
 * shop and the reward line depend on is pinned down here without a
 * single Android dependency.
 */
class EconomyTest {

    private fun fresh() = ProfileData()

    @Test
    fun `a new profile starts with the documented purse`() {
        assertEquals(100, Economy.STARTING_COINS)
        assertEquals(Economy.STARTING_COINS, fresh().coins)
    }

    @Test
    fun `vs ai wins pay more the harder the chosen opponent`() {
        for (difficulty in AiDifficulty.entries) {
            val expected = 10 + 6 * difficulty.ordinal + 2 // base + first-win streak bonus
            assertEquals(expected, Economy.reward(fresh(), GameMode.VS_AI, Player.ONE, difficulty))
        }
        // MASTER is worth four times BEGINNER: 40 vs 10 before bonuses.
        assertEquals(40 + 2, Economy.reward(fresh(), GameMode.VS_AI, Player.ONE, AiDifficulty.MASTER))
        assertEquals(10 + 2, Economy.reward(fresh(), GameMode.VS_AI, Player.ONE, AiDifficulty.BEGINNER))
    }

    @Test
    fun `vs ai draws pay 8 and losses pay a token 2`() {
        assertEquals(8, Economy.reward(fresh(), GameMode.VS_AI, null, AiDifficulty.MEDIUM))
        assertEquals(2, Economy.reward(fresh(), GameMode.VS_AI, Player.TWO, AiDifficulty.MEDIUM))
        assertEquals(8, Economy.reward(fresh(), GameMode.VS_AI, null, AiDifficulty.MASTER))
        assertEquals(2, Economy.reward(fresh(), GameMode.VS_AI, Player.TWO, AiDifficulty.BEGINNER))
    }

    @Test
    fun `pass and play pays per seat with no difficulty scaling`() {
        assertEquals(8, Economy.reward(fresh(), GameMode.PASS_AND_PLAY, Player.ONE, AiDifficulty.MASTER))
        assertEquals(4, Economy.reward(fresh(), GameMode.PASS_AND_PLAY, Player.TWO, AiDifficulty.BEGINNER))
        assertEquals(5, Economy.reward(fresh(), GameMode.PASS_AND_PLAY, null, AiDifficulty.MEDIUM))
    }

    @Test
    fun `the win streak grows on ai wins, resets on draws and losses, and the bonus caps`() {
        var profile = fresh()
        var expectedCoins = Economy.STARTING_COINS
        repeat(9) { i ->
            profile = Economy.applyResult(profile, GameMode.VS_AI, Player.ONE, AiDifficulty.BEGINNER)
            assertEquals(i + 1, profile.streak)
            // Bonus = min(newStreak, 8) * 2 on top of the 10-coin base, cumulative.
            expectedCoins += 10 + (i + 1).coerceAtMost(8) * 2
            assertEquals(expectedCoins, profile.coins)
        }
        assertEquals(9, profile.bestStreak)

        // A draw anywhere resets the streak to zero...
        profile = Economy.applyResult(profile, GameMode.VS_AI, null, AiDifficulty.MEDIUM)
        assertEquals(0, profile.streak)
        assertEquals(9, profile.bestStreak) // but the record stands

        // ...and so does a loss.
        profile = Economy.applyResult(profile, GameMode.VS_AI, Player.ONE, AiDifficulty.EASY)
        assertEquals(1, profile.streak)
        profile = Economy.applyResult(profile, GameMode.VS_AI, Player.TWO, AiDifficulty.EASY)
        assertEquals(0, profile.streak)
    }

    @Test
    fun `counters track wins losses and draws per finished match`() {
        var profile = fresh()
        profile = Economy.applyResult(profile, GameMode.VS_AI, Player.ONE, AiDifficulty.MEDIUM)
        assertEquals(1, profile.wins)
        assertEquals(0, profile.losses)
        assertEquals(0, profile.draws)

        profile = Economy.applyResult(profile, GameMode.VS_AI, Player.TWO, AiDifficulty.MEDIUM)
        assertEquals(1, profile.wins)
        assertEquals(1, profile.losses)

        profile = Economy.applyResult(profile, GameMode.PASS_AND_PLAY, Player.TWO, AiDifficulty.MEDIUM)
        assertEquals(1, profile.wins)
        assertEquals(2, profile.losses)

        profile = Economy.applyResult(profile, GameMode.PASS_AND_PLAY, null, AiDifficulty.MEDIUM)
        assertEquals(1, profile.draws)
    }

    @Test
    fun `affordability is exactly-enough inclusive`() {
        val profile = fresh().copy(coins = 150)
        assertTrue(Economy.canAfford(profile, 150))
        assertFalse(Economy.canAfford(profile, 151))
        assertTrue(Economy.canAfford(profile, 0))
    }

    @Test
    fun `buying a theme deducts the price and unlocks it`() {
        val bought = Economy.buyTheme(fresh(), "candy", 75)
        assertNotNull(bought)
        assertEquals(25, bought.coins)
        assertTrue("candy" in bought.unlockedThemes)
    }

    @Test
    fun `an unaffordable or already-owned purchase is refused without changing anything`() {
        val poor = fresh().copy(coins = 50)
        assertNull(Economy.buyTheme(poor, "aurora", 250))
        assertEquals(50, poor.coins)
        assertEquals(setOf("indigo-night"), poor.unlockedThemes)

        val owner = fresh()
        assertNull(Economy.buyTheme(owner, "indigo-night", 0))
        assertEquals(Economy.STARTING_COINS, owner.coins)
    }
}
