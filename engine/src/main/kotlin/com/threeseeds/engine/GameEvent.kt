package com.threeseeds.engine

/**
 * What happened as a result of applying a move. The UI layer maps these
 * directly to haptics, sound effects, and animations, instead of trying
 * to re-derive "what just happened" by diffing two GameState snapshots.
 */
sealed class GameEvent {
    data class SeedPlaced(val position: Position, val player: Player) : GameEvent()
    data class SeedMoved(val from: Position, val to: Position, val player: Player) : GameEvent()
    data class Won(val player: Player, val line: List<Position>) : GameEvent()
    object Drawn : GameEvent()
    data class MoveRejected(val reason: String) : GameEvent()
}
