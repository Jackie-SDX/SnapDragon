package com.threeseeds.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.threeseeds.app.audio.SoundEffects
import com.threeseeds.app.profile.ProfileRepository
import com.threeseeds.app.settings.SettingsRepository
import com.threeseeds.app.state.GameMode
import com.threeseeds.app.theme.ThemedBackground
import com.threeseeds.app.theme.ThreeSeedsTheme
import com.threeseeds.app.theme.ThemeCatalog
import com.threeseeds.app.ui.GameScreen
import com.threeseeds.app.ui.MainMenuScreen
import com.threeseeds.app.ui.SettingsScreen
import com.threeseeds.app.viewmodel.GameViewModel
import com.threeseeds.app.viewmodel.GameViewModelFactory
import kotlinx.coroutines.delay

private enum class Screen { MENU, GAME, SETTINGS }

class MainActivity : ComponentActivity() {

    private val settings by lazy { SettingsRepository(this) }
    private val soundEffects by lazy { SoundEffects() }
    private val profile by lazy { ProfileRepository(this) }

    private val gameViewModel: GameViewModel by viewModels {
        GameViewModelFactory(owner = this, settings = settings, soundPlayer = soundEffects, profile = profile)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ThreeSeedsTheme {
                val profileData by profile.data.collectAsState()
                var currentScreen by rememberSaveable { mutableStateOf(Screen.MENU) }
                val uiState by gameViewModel.uiState.collectAsState()
                val hapticTick by gameViewModel.hapticTick.collectAsState()

                // Dynamic themes: hop to the next unlocked theme every few
                // seconds. Keyed on themeId so each hop restarts the timer.
                LaunchedEffect(profileData.dynamicThemes, profileData.themeId) {
                    if (profileData.dynamicThemes && profileData.unlockedThemes.size > 1) {
                        delay(6000)
                        profile.update { p ->
                            p.copy(themeId = ThemeCatalog.nextUnlocked(p.themeId, p.unlockedThemes).id)
                        }
                    }
                }

                val theme = ThemeCatalog.byId(profileData.themeId)

                // Back steps down one screen; on the menu the system default
                // (close the app) takes over.
                BackHandler(enabled = currentScreen != Screen.MENU) {
                    currentScreen = Screen.MENU
                }

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
                            onExitToMenu = { currentScreen = Screen.MENU },
                            onClearInvalidFlash = gameViewModel::clearInvalidFlash,
                            onClearHint = gameViewModel::clearHint
                        )

                        Screen.SETTINGS -> SettingsScreen(
                            profile = profileData,
                            soundEnabled = uiState.soundEnabled,
                            hapticsEnabled = uiState.hapticsEnabled,
                            debugModeEnabled = uiState.debugModeEnabled,
                            adjacentMovementOnly = uiState.adjacentMovementOnly,
                            onSoundChanged = gameViewModel::setSoundEnabled,
                            onHapticsChanged = gameViewModel::setHapticsEnabled,
                            onDebugModeChanged = gameViewModel::setDebugModeEnabled,
                            onAdjacentMovementOnlyChanged = gameViewModel::setAdjacentMovementOnly,
                            onProfileChanged = { transform -> profile.update(transform) },
                            onBack = { currentScreen = Screen.MENU }
                        )
                    }
                }
            }
        }
    }
}
