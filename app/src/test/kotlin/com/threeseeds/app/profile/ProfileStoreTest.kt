package com.threeseeds.app.profile

import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileStoreTest {

    @Test
    fun `a fresh profile is unregistered until the welcome screen names it`() {
        assertEquals("", ProfileData().playerName)
    }

    @Test
    fun `player name updates flow through the store`() {
        val store = InMemoryProfileStore()
        store.update { it.copy(playerName = "Jackie") }
        assertEquals("Jackie", store.data.value.playerName)
    }
}
