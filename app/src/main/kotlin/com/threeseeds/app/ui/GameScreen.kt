package com.threeseeds.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.threeseeds.app.R
import com.threeseeds.app.state.GameMode
import com.threeseeds.app.state.GameUiState
import com.threeseeds.app.state.UiHint
import com.threeseeds.app.theme.AccentMagenta
import com.threeseeds.app.theme.InvalidFlashColor
import com.threeseeds.app.theme.LegalDestinationColor
import com.threeseeds.app.theme.LocalGameTheme
import com.threeseeds.engine.GamePhase
import com.threeseeds.engine.Player
import com.threeseeds.engine.Position
import kotlinx.coroutines.delay

private val MIN_TAP_HEIGHT = 48.dp

@Composable
fun GameScreen(
    uiState: GameUiState,
    hapticTick: Long,
    onPointTapped: (Position) -> Unit,
    onUndo: () -> Unit,
    onRestart: () -> Unit,
    onPlayAgain: () -> Unit,
    onTogglePause: () -> Unit,
    onExitToMenu: () -> Unit,
    onClearInvalidFlash: () -> Unit,
    onClearHint: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val gameState = uiState.gameState

    LaunchedEffect(hapticTick) {
        if (hapticTick > 0) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    LaunchedEffect(uiState.invalidMoveFlash) {
        if (uiState.invalidMoveFlash != null) {
            delay(450)
            onClearInvalidFlash()
        }
    }

    LaunchedEffect(uiState.hintMessage) {
        if (uiState.hintMessage != null) {
            delay(2000)
            onClearHint()
        }
    }

    var showRestartConfirm by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            TopBar(
                isPaused = uiState.isPaused,
                onUndoClick = onUndo,
                onRestartClick = { showRestartConfirm = true },
                onPauseClick = onTogglePause
            )

            Spacer(modifier = Modifier.height(8.dp))

            TurnBanner(
                currentPlayerNumber = if (gameState.currentPlayer == Player.ONE) 1 else 2,
                isPlacementPhase = gameState.phase == GamePhase.PLACEMENT,
                remainingForCurrentPlayer = gameState.seedsRemaining[gameState.currentPlayer] ?: 0,
                seedsOnBoard = gameState.board.positionsOf(gameState.currentPlayer).size,
                adjacentMovementOnly = uiState.matchAdjacentMovementOnly,
                computerSeat = uiState.gameMode == GameMode.VS_AI &&
                    gameState.currentPlayer == Player.TWO
            )

            AnimatedVisibility(
                visible = uiState.aiThinking,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Text(
                    text = stringResource(R.string.ai_thinking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            AnimatedVisibility(
                visible = uiState.hintMessage != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                val hint = uiState.hintMessage ?: return@AnimatedVisibility
                Text(
                    text = stringResource(hintTextResource(hint)),
                    color = InvalidFlashColor,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                BoardCanvas(
                    gameState = gameState,
                    selectedSeed = uiState.selectedSeed,
                    legalDestinations = uiState.legalDestinations,
                    movableSeeds = uiState.movableSeeds,
                    invalidFlashPosition = uiState.invalidMoveFlash,
                    onPointTapped = onPointTapped,
                    modifier = Modifier.aspectRatio(1f).padding(8.dp)
                )
            }

            if (uiState.debugModeEnabled) {
                DebugPanel(uiState)
            }
        }

        if (uiState.isPaused) {
            PauseOverlay(onResume = onTogglePause, onExitToMenu = onExitToMenu)
        } else if (gameState.phase == GamePhase.WON || gameState.phase == GamePhase.DRAW) {
            EndOfGameOverlay(
                phase = gameState.phase,
                winnerNumber = gameState.winner?.let { if (it == Player.ONE) 1 else 2 },
                coinsEarned = uiState.lastCoinsEarned,
                vsAi = uiState.gameMode == GameMode.VS_AI,
                onPlayAgain = onPlayAgain,
                onExitToMenu = onExitToMenu
            )
        }
    }

    if (showRestartConfirm) {
        AlertDialog(
            onDismissRequest = { showRestartConfirm = false },
            title = { Text(stringResource(R.string.restart_confirm_title)) },
            text = { Text(stringResource(R.string.restart_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { showRestartConfirm = false; onRestart() }) { Text(stringResource(R.string.restart)) }
            },
            dismissButton = {
                TextButton(onClick = { showRestartConfirm = false }) { Text(stringResource(R.string.close)) }
            }
        )
    }
}

@Composable
private fun hintTextResource(hint: UiHint): Int = when (hint) {
    UiHint.BLOCKED_PATH -> R.string.hint_blocked_path
    UiHint.OPPONENT_SEED -> R.string.hint_opponent_seed
    UiHint.BOXED_IN -> R.string.hint_boxed_in
    UiHint.OCCUPIED -> R.string.hint_occupied
    UiHint.ILLEGAL_MOVE -> R.string.hint_illegal
}

@Composable
private fun TopBar(isPaused: Boolean, onUndoClick: () -> Unit, onRestartClick: () -> Unit, onPauseClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onUndoClick, modifier = Modifier.heightIn(min = MIN_TAP_HEIGHT)) {
            Text(stringResource(R.string.undo))
        }
        Row {
            TextButton(onClick = onRestartClick, modifier = Modifier.heightIn(min = MIN_TAP_HEIGHT)) {
                Text(stringResource(R.string.restart))
            }
            TextButton(onClick = onPauseClick, modifier = Modifier.heightIn(min = MIN_TAP_HEIGHT)) {
                Text(stringResource(if (isPaused) R.string.resume else R.string.pause))
            }
        }
    }
}

@Composable
private fun TurnBanner(
    currentPlayerNumber: Int,
    isPlacementPhase: Boolean,
    remainingForCurrentPlayer: Int,
    seedsOnBoard: Int,
    adjacentMovementOnly: Boolean,
    computerSeat: Boolean
) {
    val theme = LocalGameTheme.current
    val playerColor = if (currentPlayerNumber == 1) theme.playerOne else theme.playerTwo
    val title = when {
        !computerSeat -> stringResource(
            if (isPlacementPhase) R.string.turn_placement else R.string.turn_movement,
            currentPlayerNumber
        )

        isPlacementPhase -> stringResource(R.string.turn_computer_place)
        else -> stringResource(R.string.turn_computer_move)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = theme.surfaceColor.copy(alpha = 0.85f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(Brush.linearGradient(listOf(playerColor, playerColor.copy(alpha = 0.55f))), CircleShape)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            if (isPlacementPhase) {
                Text(
                    text = stringResource(R.string.seeds_remaining, remainingForCurrentPlayer),
                    style = MaterialTheme.typography.bodyLarge
                )
            } else {
                Text(
                    text = if (computerSeat) {
                        stringResource(R.string.seeds_computer, seedsOnBoard)
                    } else {
                        stringResource(R.string.seeds_on_board, currentPlayerNumber, seedsOnBoard)
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "  ·  " + stringResource(
                        if (adjacentMovementOnly) R.string.rules_tapatan else R.string.rules_free
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = LegalDestinationColor
                )
            }
        }
    }
}

@Composable
private fun PauseOverlay(onResume: () -> Unit, onExitToMenu: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.65f), androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.80f)))), contentAlignment = Alignment.Center) {
        Card(modifier = Modifier.padding(32.dp), shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.paused_title), style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = onResume, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.resume))
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = onExitToMenu, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.main_menu))
                }
            }
        }
    }
}

@Composable
private fun EndOfGameOverlay(
    phase: GamePhase,
    winnerNumber: Int?,
    coinsEarned: Int?,
    vsAi: Boolean,
    onPlayAgain: () -> Unit,
    onExitToMenu: () -> Unit
) {
    val theme = LocalGameTheme.current
    val winnerColor = when (winnerNumber) {
        1 -> theme.playerOne
        2 -> theme.playerTwo
        else -> LegalDestinationColor
    }
    Box(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f), androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.78f)))),
        contentAlignment = Alignment.Center
    ) {
        Card(modifier = Modifier.padding(32.dp), shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (phase == GamePhase.WON && winnerNumber != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.linearGradient(listOf(winnerColor, AccentMagenta.copy(alpha = 0.85f))),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when {
                                vsAi && winnerNumber == 2 -> stringResource(R.string.computer_wins)
                                vsAi && winnerNumber == 1 -> stringResource(R.string.you_win)
                                else -> stringResource(R.string.player_wins, winnerNumber ?: 1)
                            },
                            style = MaterialTheme.typography.headlineMedium,
                            color = androidx.compose.ui.graphics.Color.White
                        )
                    }
                } else {
                    Text(stringResource(R.string.draw_title), style = MaterialTheme.typography.headlineMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.draw_body), style = MaterialTheme.typography.bodyLarge)
                }
                if (coinsEarned != null && coinsEarned > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.coins_earned, coinsEarned),
                        style = MaterialTheme.typography.titleMedium,
                        color = theme.accentColor
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = onPlayAgain, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.play_again))
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = onExitToMenu, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.main_menu))
                }
            }
        }
    }
}

@Composable
private fun DebugPanel(uiState: GameUiState) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState())) {
        Text(text = stringResource(R.string.debug_toggle), style = MaterialTheme.typography.labelLarge)
        Text(text = uiState.gameState.toString(), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "selected=${uiState.selectedSeed} legalDestinations=${uiState.legalDestinations}",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
