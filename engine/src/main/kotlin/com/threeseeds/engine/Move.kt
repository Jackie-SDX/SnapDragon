package com.threeseeds.engine

sealed class Move {
    data class Place(val position: Position) : Move()
    data class Relocate(val from: Position, val to: Position) : Move()
}
