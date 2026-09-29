package com.threeseeds.engine

enum class Player {
    ONE,
    TWO;

    fun opponent(): Player = if (this == ONE) TWO else ONE
}
