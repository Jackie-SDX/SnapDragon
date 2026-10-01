package com.threeseeds.app.theme

import com.threeseeds.app.profile.ProfileData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The catalog is the product's shop inventory: sizes, prices, and
 * rotation behavior are promises to the player, so they are pinned.
 */
class ThemeCatalogTest {

    @Test
    fun `the catalog ships 26 themes with unique ids`() {
        assertEquals(26, ThemeCatalog.ALL.size)
        assertEquals(26, ThemeCatalog.ALL.map { it.id }.toSet().size)
        assertEquals(26, ThemeCatalog.ALL.map { it.name }.toSet().size)
    }

    @Test
    fun `ten themes are free and the rest sit on the documented price tiers`() {
        val free = ThemeCatalog.ALL.count { it.cost == 0 }
        assertEquals(10, free)

        val counts = ThemeCatalog.ALL.groupingBy { it.cost }.eachCount()
        assertEquals(10, counts[0])
        assertEquals(5, counts[75])
        assertEquals(6, counts[150])
        assertEquals(5, counts[250])

        val legalTiers = setOf(0, 75, 150, 250)
        assertTrue(ThemeCatalog.ALL.all { it.cost in legalTiers })
    }

    @Test
    fun `the default theme is free and resolvable, and unknown ids fall back to it`() {
        val default = ThemeCatalog.byId(ThemeCatalog.DEFAULT_ID)
        assertEquals(ThemeCatalog.DEFAULT, default)
        assertEquals(0, default.cost)
        assertEquals(ThemeCatalog.DEFAULT_ID, default.id)

        assertEquals(ThemeCatalog.DEFAULT, ThemeCatalog.byId("no-such-theme"))
        assertTrue(ThemeCatalog.DEFAULT_ID in ProfileData().unlockedThemes)
    }

    @Test
    fun `rotation walks only unlocked themes and wraps around`() {
        val unlocked = setOf("indigo-night", "ocean", "cosmic")

        val hops = generateSequence(ThemeCatalog.byId("indigo-night")) {
            ThemeCatalog.nextUnlocked(it.id, unlocked)
        }.take(7).toList()

        assertTrue(hops.all { it.id in unlocked }, "rotation may only visit unlocked themes")
        // Indigo -> Ocean -> Cosmic -> Indigo -> ...
        assertEquals(listOf("indigo-night", "ocean", "cosmic", "indigo-night", "ocean", "cosmic", "indigo-night"), hops.map { it.id })
    }

    @Test
    fun `rotation from a locked or unknown id starts at the first unlocked theme`() {
        val unlocked = setOf("blossom", "neon")
        val next = ThemeCatalog.nextUnlocked("not-unlocked", unlocked)
        assertTrue(next.id in unlocked)
    }

    @Test
    fun `every theme has background stops and distinct player colors to draw with`() {
        for (theme in ThemeCatalog.ALL) {
            assertTrue(theme.backgroundStops.size >= 2, "${theme.id} needs a real gradient")
            assertTrue(theme.playerOne != theme.playerTwo, "${theme.id} seeds must be distinguishable")
        }
    }
}
