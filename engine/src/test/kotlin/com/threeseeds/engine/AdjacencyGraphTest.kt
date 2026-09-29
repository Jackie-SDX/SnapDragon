package com.threeseeds.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdjacencyGraphTest {

    @Test
    fun `the center connects to all 8 other points`() {
        val center = Position(4)
        val expected = Position.ALL.filter { it != center }.toSet()
        assertEquals(expected, AdjacencyGraph.neighborsOf(center))
    }

    @Test
    fun `every corner has exactly 3 neighbors, including the center via a diagonal`() {
        for (corner in listOf(0, 2, 6, 8)) {
            val neighbors = AdjacencyGraph.neighborsOf(Position(corner))
            assertEquals(3, neighbors.size, "corner $corner should have 3 neighbors")
            assertTrue(Position(4) in neighbors, "corner $corner should connect to the center")
        }
    }

    @Test
    fun `every edge midpoint has exactly 3 neighbors, including the center`() {
        for (edge in listOf(1, 3, 5, 7)) {
            val neighbors = AdjacencyGraph.neighborsOf(Position(edge))
            assertEquals(3, neighbors.size, "edge $edge should have 3 neighbors")
            assertTrue(Position(4) in neighbors, "edge $edge should connect to the center")
        }
    }

    @Test
    fun `two corners on the same side are not directly connected to each other`() {
        // e.g. 0 and 2 (top-left, top-right) only connect via 1 or via the center's diagonals
        assertTrue(!AdjacencyGraph.areConnected(Position(0), Position(2)))
    }

    @Test
    fun `adjacency is always symmetric`() {
        for (a in Position.ALL) {
            for (b in AdjacencyGraph.neighborsOf(a)) {
                assertTrue(AdjacencyGraph.areConnected(b, a), "$b should connect back to $a")
            }
        }
    }

    @Test
    fun `no point is its own neighbor`() {
        for (p in Position.ALL) {
            assertTrue(p !in AdjacencyGraph.neighborsOf(p))
        }
    }
}
