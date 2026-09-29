package com.threeseeds.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PositionTest {

    @Test
    fun `indices 0 through 8 are all valid`() {
        for (i in 0..8) {
            assertEquals(i, Position(i).index)
        }
    }

    @Test
    fun `Position ALL contains exactly the 9 board points, in order`() {
        assertEquals((0..8).toList(), Position.ALL.map { it.index })
    }

    @Test
    fun `an out-of-range index is rejected`() {
        assertFailsWith<IllegalArgumentException> { Position(9) }
        assertFailsWith<IllegalArgumentException> { Position(-1) }
    }
}
