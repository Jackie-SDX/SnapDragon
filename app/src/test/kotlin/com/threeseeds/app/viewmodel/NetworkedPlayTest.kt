package com.threeseeds.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.threeseeds.app.audio.SoundPlayer
import com.threeseeds.app.net.InMemoryTransport
import com.threeseeds.app.net.LinkSession
import com.threeseeds.app.settings.SettingsStore
import com.threeseeds.app.state.GameMode
import com.threeseeds.app.state.LinkStatus
import com.threeseeds.engine.GamePhase
import com.threeseeds.engine.Player
import com.threeseeds.engine.Position
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkedPlayTest {

    private class FakeSettings(
        override var soundEnabled: Boolean = true,
        override var hapticsEnabled: Boolean = true,
        override var debugModeEnabled: Boolean = false,
        override var adjacentMovementOnly: Boolean = false
    ) : SettingsStore

    private class FakeSound : SoundPlayer {
        override fun playSeedPlaced() {}
        override fun playSeedMoved() {}
        override fun playInvalidMove() {}
        override fun playVictory() {}
        override fun release() {}
    }

    /** A brand-new board carries its initial snapshot — history is never empty. */
    private val FRESH_HISTORY = com.threeseeds.engine.GameState().history.size

    private fun vm(settings: FakeSettings = FakeSettings()) =
        GameViewModel(SavedStateHandle(), settings, FakeSound())

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Host + guest viewmodels over a synchronous in-memory pair, both
     * handshaked. Returns them ready to play.
     */
    private fun connectedPair(hostSettings: FakeSettings = FakeSettings()): Pair<GameViewModel, GameViewModel> {
        val host = vm(hostSettings)
        val guest = vm()
        val (hostTransport, guestTransport) = InMemoryTransport.pair()
        host.attachSession(LinkSession(isHost = true, transport = hostTransport, localName = "Host") {
            if (hostSettings.adjacentMovementOnly) "TAPATAN" else "FREE"
        })
        guest.attachSession(LinkSession(isHost = false, transport = guestTransport, localName = "Guest"))
        return host to guest
    }

    @Test
    fun `handshake assigns seats, modes, and adopts the host ruleset`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val (host, guest) = connectedPair(hostSettings = FakeSettings(adjacentMovementOnly = true))

        val hs = host.uiState.value
        assertEquals(LinkStatus.CONNECTED, hs.linkStatus)
        assertEquals(GameMode.NEARBY_HOST, hs.gameMode)
        assertEquals(Player.ONE, hs.mySeat)
        assertEquals("Guest", hs.peerName)

        val gs = guest.uiState.value
        assertEquals(LinkStatus.CONNECTED, gs.linkStatus)
        assertEquals(GameMode.NEARBY_GUEST, gs.gameMode)
        assertEquals(Player.TWO, gs.mySeat)
        assertEquals("Host", gs.peerName)
        // The guest's own settings say FREE, but the host froze TAPATAN —
        // the match runs under the host's rules on both devices.
        assertEquals(true, gs.matchAdjacentMovementOnly, "guest adopts the host ruleset")
        assertEquals(true, hs.matchAdjacentMovementOnly)
    }

    @Test
    fun `guest tap is applied by the host and mirrored back to the guest`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val (host, guest) = connectedPair()

        // Host is Player One: host places first.
        host.onPointTapped(Position(4))
        assertEquals(Player.ONE, host.uiState.value.gameState.board[Position(4)])
        assertEquals(Player.ONE, guest.uiState.value.gameState.board[Position(4)], "state broadcast mirrors")

        // Guest's turn: its tap reaches the host engine and comes back.
        guest.onPointTapped(Position(0))
        assertEquals(Player.TWO, host.uiState.value.gameState.board[Position(0)])
        assertEquals(Player.TWO, guest.uiState.value.gameState.board[Position(0)])
        assertTrue(host.uiState.value.gameState.board[Position(0)] == Player.TWO)
        assertEquals(GamePhase.PLACEMENT, guest.uiState.value.gameState.phase)
    }

    @Test
    fun `guest cannot move on the host's turn and vice versa`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val (host, guest) = connectedPair()

        guest.onPointTapped(Position(4)) // not guest's turn yet
        assertNull(guest.uiState.value.gameState.board[Position(4)])
        assertEquals(FRESH_HISTORY, guest.uiState.value.gameState.history.size)

        host.onPointTapped(Position(4)) // now it is the guest's turn...
        guest.onPointTapped(Position(4)) // ...guest tries the taken point
        assertEquals(Player.ONE, host.uiState.value.gameState.board[Position(4)], "occupied point must not double-place")
        assertEquals(Player.TWO, host.uiState.value.gameState.currentPlayer)
    }

    @Test
    fun `a full match ends on both sides and both get the overlay state`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val (host, guest) = connectedPair()

        // P1: 0,1,2 win line placement order 0,3,1,4,2
        host.onPointTapped(Position(0)) // P1
        guest.onPointTapped(Position(3)) // P2
        host.onPointTapped(Position(1)) // P1
        guest.onPointTapped(Position(4)) // P2
        host.onPointTapped(Position(2)) // P1 completes 0-1-2

        assertEquals(GamePhase.WON, host.uiState.value.gameState.phase)
        assertEquals(GamePhase.WON, guest.uiState.value.gameState.phase)
        assertEquals(Player.ONE, guest.uiState.value.gameState.winner)
        assertNotNull(host.uiState.value.lastCoinsEarned, "host awards on terminal state")
        assertNotNull(guest.uiState.value.lastCoinsEarned, "guest awards on mirrored terminal state")
    }

    @Test
    fun `host restart broadcasts a fresh board and the guest mirrors it`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val (host, guest) = connectedPair()

        host.onPointTapped(Position(4))
        guest.onPointTapped(Position(0))
        assertTrue(host.uiState.value.gameState.history.isNotEmpty())
        assertTrue(guest.uiState.value.gameState.history.isNotEmpty())

        host.restart()

        assertEquals(FRESH_HISTORY, host.uiState.value.gameState.history.size)
        assertEquals(FRESH_HISTORY, guest.uiState.value.gameState.history.size)
        assertEquals(GamePhase.PLACEMENT, guest.uiState.value.gameState.phase)
    }

    @Test
    fun `undo is disabled in nearby play`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val (host, _) = connectedPair()

        host.onPointTapped(Position(4))
        host.undo()

        assertEquals(FRESH_HISTORY + 1, host.uiState.value.gameState.history.size, "undo must be a no-op over the wire")
    }

    @Test
    fun `leaving the match drops the link and tells the peer`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val (host, guest) = connectedPair()

        host.leaveLink()

        assertEquals(LinkStatus.NONE, host.uiState.value.linkStatus)
        assertEquals(LinkStatus.LOST, guest.uiState.value.linkStatus, "peer learns the host left")
        assertNull(host.uiState.value.peerName)
    }

    @Test
    fun `guest taps are ignored after the link is lost`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val (host, guest) = connectedPair()

        host.leaveLink()
        guest.onPointTapped(Position(4))

        assertEquals(FRESH_HISTORY, guest.uiState.value.gameState.history.size)
    }
}
