package com.threeseeds.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Modifier
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
import com.threeseeds.app.theme.rememberAnimatedTheme
import com.threeseeds.app.ui.GameScreen
import com.threeseeds.app.ui.LocalReduceMotion
import com.threeseeds.app.ui.MainMenuScreen
import com.threeseeds.app.ui.NearbyScreen
import com.threeseeds.app.ui.SettingsScreen
import com.threeseeds.app.ui.WelcomeScreen
import com.threeseeds.app.viewmodel.GameViewModel
import com.threeseeds.app.viewmodel.GameViewModelFactory
import com.threeseeds.engine.GamePhase
import kotlinx.coroutines.delay
import androidx.compose.animation.core.tween

private enum class Screen { WELCOME, MENU, GAME, SETTINGS, NEARBY }

class MainActivity : ComponentActivity() {

    private val settings by lazy { SettingsRepository(this) }
    private val soundEffects by lazy { SoundEffects(this) }
    private val profile by lazy { ProfileRepository(this) }
    private val musicPlayer by lazy { MusicPlayer(this, settings.musicEnabled) }

    private val gameViewModel: GameViewModel by viewModels {
        GameViewModelFactory(
            owner = this,
            settings = settings,
            soundPlayer = soundEffects,
            profile = profile,
            localName = {
                profile.data.value.playerName.ifBlank { android.os.Build.MODEL ?: "Player" }
            }
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
            // First launch (or pre-1.3 profile with no name yet): register first.
            var currentScreen by rememberSaveable {
                mutableStateOf(
                    if (profileData.playerName.isBlank()) Screen.WELCOME else Screen.MENU
                )
            }
            val uiState by gameViewModel.uiState.collectAsState()
            val hapticTick by gameViewModel.hapticTick.collectAsState()
            val peers by gameViewModel.peers.collectAsState()
            val linkError by gameViewModel.linkError.collectAsState()

            // System-wide "remove animations" accessibility setting.
            val reduceMotion = remember {
                android.provider.Settings.Global.getFloat(
                    contentResolver,
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f
                ) == 0f
            }

            // The equipped theme (profileData.themeId) is persistent and
            // changes ONLY on an explicit user tap. Rotation advances a
            // transient cursor and never writes back into the profile,
            // so the player's pick survives every screen.
            //
            // The DISPLAYED theme is derived every frame instead of
            // being re-anchored by a LaunchedEffect: effect-driven
            // writes land one composition late, which let a frame of a
            // stale rotated theme render on entry/restore (the
            // "flickers to another theme and comes back" bug).
            var rotatedThemeId by rememberSaveable { mutableStateOf(profileData.themeId) }
            val rotationActive = profileData.dynamicThemes &&
                currentScreen == Screen.GAME &&
                profileData.unlockedThemes.size > 1

            // While rotation is idle, keep the cursor pinned to the
            // equipped theme so every new game starts from the pick.
            LaunchedEffect(profileData.themeId, rotationActive) {
                if (!rotationActive) rotatedThemeId = profileData.themeId
            }

            val displayedThemeId = if (rotationActive) rotatedThemeId else profileData.themeId

            // Advance the cursor every few seconds while rotating.
            LaunchedEffect(displayedThemeId, rotationActive) {
                if (rotationActive) {
                    delay(6000)
                    rotatedThemeId = ThemeCatalog
                        .nextUnlocked(displayedThemeId, profileData.unlockedThemes).id
                }
            }

            // Settings toggle → live soundtrack state.
            LaunchedEffect(uiState.musicEnabled) {
                musicPlayer.setEnabled(uiState.musicEnabled)
            }

            // The score swells with the match: calm on menus/placement,
            // tense as the game decides. MusicPlayer tweens internally.
            LaunchedEffect(uiState.musicIntensity) {
                musicPlayer.setIntensity(uiState.musicIntensity)
            }

            // A decided match hands the mix to the sting: duck the score
            // while the overlay (and victory sound) plays.
            LaunchedEffect(uiState.gameState.phase) {
                musicPlayer.setDucked(
                    uiState.gameState.phase == GamePhase.WON ||
                        uiState.gameState.phase == GamePhase.DRAW
                )
            }

            // Handshake finished in the lobby → straight into the match.
            LaunchedEffect(uiState.linkStatus, currentScreen) {
                if (uiState.linkStatus == LinkStatus.CONNECTED && currentScreen == Screen.NEARBY) {
                    currentScreen = Screen.GAME
                }
            }

            val rawTheme = ThemeCatalog.byId(displayedThemeId)
            // UI palette animates over 500ms to match the background
            // crossfade, so a theme change morphs instead of snapping.
            val theme = rememberAnimatedTheme(rawTheme)

            // Back steps down one screen; on the menu the system default
            // (close the app) takes over.
            BackHandler(enabled = currentScreen != Screen.MENU && currentScreen != Screen.WELCOME) {
                if (currentScreen == Screen.GAME) gameViewModel.leaveLink()
                currentScreen = Screen.MENU
            }

            ThreeSeedsTheme(theme = theme) {
                CompositionLocalProvider(
                    LocalGameTheme provides theme,
                    LocalReduceMotion provides reduceMotion
                ) {
                    ThemedBackground(theme = rawTheme) {
                        // Android 15+ draws edge-to-edge; keep every screen
                        // clear of the status/nav bars (the game's top bar
                        // otherwise sits under the status bar, unreachable).
                        Box(modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars)) {
                        AnimatedContent(
                            targetState = currentScreen,
                            transitionSpec = {
                                if (reduceMotion) {
                                    EnterTransition.None togetherWith ExitTransition.None
                                } else {
                                    (fadeIn(tween(240)) +
                                        slideInHorizontally(tween(280)) { it / 10 }) togetherWith
                                        fadeOut(tween(160))
                                }
                            },
                            label = "screen"
                        ) { screen ->
                        when (screen) {
                            Screen.WELCOME -> WelcomeScreen(
                                onDone = { chosen ->
                                    profile.update { it.copy(playerName = chosen) }
                                    currentScreen = Screen.MENU
                                }
                            )

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
                                onClearHint = gameViewModel::clearHint,
                                firstRun = profileData.wins + profileData.losses + profileData.draws == 0
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
        }
    }

    override fun onDestroy() {
        musicPlayer.release()
        soundEffects.release()
        super.onDestroy()
    }
}
