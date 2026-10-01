package com.threeseeds.app.profile

import com.threeseeds.app.state.GameMode
import com.threeseeds.engine.Player
import com.threeseeds.engine.ai.AiDifficulty
import kotlin.test.Test
import kotlin.test.assertEquals

class EconomyNearbyTest {

    private val fresh = ProfileData()
    private val difficulty = AiDifficulty.MEDIUM

    @Test
    fun `host wins pay like a pass-and-play win and count as a win`() {
        val result = Economy.applyResult(fresh, GameMode.NEARBY_HOST, Player.ONE, difficulty)
        assertEquals(8, result.coins - fresh.coins)
        assertEquals(1, result.wins)
        assertEquals(0, result.losses)
    }

    @Test
    fun `host losses pay the consolation amount and count as a loss`() {
        val result = Economy.applyResult(fresh, GameMode.NEARBY_HOST, Player.TWO, difficulty)
        assertEquals(4, result.coins - fresh.coins)
        assertEquals(0, result.wins)
        assertEquals(1, result.losses)
    }

    @Test
    fun `guest wins are worth the same as host wins from the guest seat`() {
        val result = Economy.applyResult(fresh, GameMode.NEARBY_GUEST, Player.TWO, difficulty)
        assertEquals(8, result.coins - fresh.coins)
        assertEquals(1, result.wins)
        assertEquals(0, result.losses)
    }

    @Test
    fun `guest losses count as losses for the guest seat`() {
        val result = Economy.applyResult(fresh, GameMode.NEARBY_GUEST, Player.ONE, difficulty)
        assertEquals(4, result.coins - fresh.coins)
        assertEquals(1, result.losses)
        assertEquals(0, result.wins)
    }

    @Test
    fun `nearby draws pay five and count as a draw on both seats`() {
        val hostDraw = Economy.applyResult(fresh, GameMode.NEARBY_HOST, null, difficulty)
        val guestDraw = Economy.applyResult(fresh, GameMode.NEARBY_GUEST, null, difficulty)
        assertEquals(5, hostDraw.coins - fresh.coins)
        assertEquals(5, guestDraw.coins - fresh.coins)
        assertEquals(1, hostDraw.draws)
        assertEquals(1, guestDraw.draws)
    }

    @Test
    fun `nearby wins do not extend the computer win streak`() {
        val streaked = fresh.copy(streak = 4, bestStreak = 6)
        val result = Economy.applyResult(streaked, GameMode.NEARBY_HOST, Player.ONE, difficulty)
        assertEquals(0, result.streak)
        assertEquals(6, result.bestStreak)
    }
}
