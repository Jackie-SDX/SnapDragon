package com.threeseeds.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.threeseeds.app.audio.MusicPlayer
import com.threeseeds.app.audio.SoundEffects
import com.threeseeds.app.profile.ProfileRepository
import com.threeseeds.app.settings.SettingsRepository
import com.threeseeds.app.state.GameMode
import com.threeseeds.app.state.LinkStatus
import com.threeseeds.app.theme.LocalGameTheme
import com.threeseeds.app.theme.ThemedBackground
import com.threeseeds.app.theme.ThreeSeedsTheme
import com.threeseeds.app.theme.ThemeCatalog
import com.threeseeds.app.ui.GameScreen
import com.threeseeds.app.ui.MainMenuScreen
import com.threeseeds.app.ui.NearbyScreen
import com.threeseeds.app.ui.SettingsScreen
import com.threeseeds.app.viewmodel.GameViewModel
import com.threeseeds.app.viewmodel.GameViewModelFactory
import kotlinx.coroutines.delay

private enum class Screen { MENU, GAME, SETTINGS, NEARBY }

class MainActivity : ComponentActivity() {

    private val settings by lazy { SettingsRepository(this) }
    private val soundEffects by lazy { SoundEffects() }
    private val profile by lazy { ProfileRepository(this) }
    private val musicPlayer by lazy { MusicPlayer(this, settings.musicEnabled) }

    private val gameViewModel: GameViewModel by viewModels {
        GameViewModelFactory(
            owner = this,
            settings = settings,
            soundPlayer = soundEffects,
            profile = profile,
            localName = { android.os.Build.MODEL ?: "Player" }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Soundtrack follows the activity: play while visible (if the
        // Settings toggle allows it), pause when the app goes away.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> musicPlayer.play()
                Lifecycle.Event.ON_STOP -> musicPlayer.pause()
                else -> Unit
            }
        })

        setContent {
            val profileData by profile.data.collectAsState()
            var currentScreen by rememberSaveable { mutableStateOf(Screen.MENU) }
            val uiState by gameViewModel.uiState.collectAsState()
            val hapticTick by gameViewModel.hapticTick.collectAsState()
            val peers by gameViewModel.peers.collectAsState()
            val linkError by gameViewModel.linkError.collectAsState()

            // The equipped theme (profileData.themeId) is persistent and
            // changes ONLY on an explicit user tap. Rotation advances a
            // transient DISPLAYED theme and never writes back into the
            // profile, so the player's pick survives every screen.
            var displayedThemeId by rememberSaveable { mutableStateOf(profileData.themeId) }
            val rotationActive = profileData.dynamicThemes &&
                currentScreen == Screen.GAME &&
                profileData.unlockedThemes.size > 1

            // Re-anchor to the equipped theme whenever it changes or the
            // rotation isn't running (menu, settings, rotation off).
            LaunchedEffect(profileData.themeId, profileData.dynamicThemes, currentScreen) {
                if (!rotationActive) displayedThemeId = profileData.themeId
            }

            // Advance the displayed theme every few seconds while rotating.
            LaunchedEffect(displayedThemeId, rotationActive) {
                if (rotationActive) {
                    delay(6000)
                    displayedThemeId = ThemeCatalog
                        .nextUnlocked(displayedThemeId, profileData.unlockedThemes).id
                }
            }

            // Settings toggle → live soundtrack state.
            LaunchedEffect(uiState.musicEnabled) {
                musicPlayer.setEnabled(uiState.musicEnabled)
            }

            // Handshake finished in the lobby → straight into the match.
            LaunchedEffect(uiState.linkStatus, currentScreen) {
                if (uiState.linkStatus == LinkStatus.CONNECTED && currentScreen == Screen.NEARBY) {
                    currentScreen = Screen.GAME
                }
            }

            val theme = ThemeCatalog.byId(displayedThemeId)

            // Back steps down one screen; on the menu the system default
            // (close the app) takes over.
            BackHandler(enabled = currentScreen != Screen.MENU) {
                if (currentScreen == Screen.GAME) gameViewModel.leaveLink()
                currentScreen = Screen.MENU
            }

            ThreeSeedsTheme(theme = theme) {
                CompositionLocalProvider(LocalGameTheme provides theme) {
                    ThemedBackground(theme = theme) {
                        when (currentScreen) {
                            Screen.MENU -> MainMenuScreen(
                                profile = profileData,
                                onPlayLocal = {
                                    gameViewModel.startMatch(GameMode.PASS_AND_PLAY)
                                    currentScreen = Screen.GAME
                                },
                                onPlayVsAi = {
                                    gameViewModel.startMatch(GameMode.VS_AI)
                                    currentScreen = Screen.GAME
                                },
                                onPlayNearby = { currentScreen = Screen.NEARBY },
                                onSettings = { currentScreen = Screen.SETTINGS }
                            )

                            Screen.GAME -> GameScreen(
                                uiState = uiState,
                                hapticTick = hapticTick,
                                onPointTapped = gameViewModel::onPointTapped,
                                onUndo = gameViewModel::undo,
                                onRestart = gameViewModel::restart,
                                onPlayAgain = gameViewModel::playAgain,
                                onTogglePause = gameViewModel::togglePause,
                                onExitToMenu = {
                                    gameViewModel.leaveLink()
                                    currentScreen = Screen.MENU
                                },
                                onClearInvalidFlash = gameViewModel::clearInvalidFlash,
                                onClearHint = gameViewModel::clearHint
                            )

                            Screen.SETTINGS -> SettingsScreen(
                                profile = profileData,
                                soundEnabled = uiState.soundEnabled,
                                musicEnabled = uiState.musicEnabled,
                                hapticsEnabled = uiState.hapticsEnabled,
                                debugModeEnabled = uiState.debugModeEnabled,
                                adjacentMovementOnly = uiState.adjacentMovementOnly,
                                onSoundChanged = gameViewModel::setSoundEnabled,
                                onMusicChanged = gameViewModel::setMusicEnabled,
                                onHapticsChanged = gameViewModel::setHapticsEnabled,
                                onDebugModeChanged = gameViewModel::setDebugModeEnabled,
                                onAdjacentMovementOnlyChanged = gameViewModel::setAdjacentMovementOnly,
                                onProfileChanged = { transform -> profile.update(transform) },
                                onBack = { currentScreen = Screen.MENU }
                            )

                            Screen.NEARBY -> NearbyScreen(
                                uiState = uiState,
                                peers = peers,
                                linkError = linkError,
                                onHost = gameViewModel::startHosting,
                                onScan = gameViewModel::scanPeers,
                                onJoin = gameViewModel::joinPeer,
                                onCancel = gameViewModel::leaveLink,
                                onClearError = gameViewModel::clearLinkError,
                                onBack = {
                                    gameViewModel.leaveLink()
                                    currentScreen = Screen.MENU
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        musicPlayer.release()
        soundEffects.release()
        super.onDestroy()
    }
}
