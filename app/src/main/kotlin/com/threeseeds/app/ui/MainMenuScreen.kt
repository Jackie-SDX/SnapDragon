package com.threeseeds.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.threeseeds.app.BuildConfig
import com.threeseeds.app.R
import com.threeseeds.app.profile.ProfileData
import com.threeseeds.app.theme.LocalGameTheme

private val MIN_BUTTON_HEIGHT = 48.dp

@Composable
fun MainMenuScreen(
    profile: ProfileData,
    onPlayLocal: () -> Unit,
    onPlayVsAi: () -> Unit,
    onPlayNearby: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAbout by remember { mutableStateOf(false) }
    val theme = LocalGameTheme.current

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(PaddingValues(24.dp)).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(brush = theme.accentBrush(), fontWeight = FontWeight.Black)) {
                        append(stringResource(R.string.app_name))
                    }
                },
                style = MaterialTheme.typography.displayLarge
            )
            Text(
                text = stringResource(R.string.tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = theme.accentColor,
                modifier = Modifier.padding(top = 8.dp)
            )

            Text(
                text = if (profile.playerName.isBlank()) {
                    stringResource(
                        R.string.menu_profile_summary,
                        profile.coins,
                        profile.wins,
                        profile.losses,
                        profile.draws
                    )
                } else {
                    stringResource(
                        R.string.menu_profile_summary_named,
                        profile.playerName,
                        profile.coins,
                        profile.wins,
                        profile.losses,
                        profile.draws
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
            )

            // Gradient play button: the brush sits behind a transparent button.
            Box(
                modifier = Modifier
                    .width(240.dp)
                    .height(MIN_BUTTON_HEIGHT)
                    .background(Brush.linearGradient(listOf(theme.accentColor, theme.playerOne)), RoundedCornerShape(50)),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = onPlayLocal,
                    modifier = Modifier.fillMaxWidth().height(MIN_BUTTON_HEIGHT),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White),
                    shape = RoundedCornerShape(50)
                ) { Text(stringResource(R.string.menu_play_local), fontWeight = FontWeight.Bold) }
            }

            Column(modifier = Modifier.padding(top = 16.dp)) {
                OutlinedButton(
                    onClick = onPlayVsAi,
                    modifier = Modifier.width(240.dp).height(MIN_BUTTON_HEIGHT)
                ) { Text(stringResource(R.string.menu_play_ai)) }
            }

            Column(modifier = Modifier.padding(top = 16.dp)) {
                OutlinedButton(
                    onClick = onPlayNearby,
                    modifier = Modifier.width(240.dp).height(MIN_BUTTON_HEIGHT)
                ) { Text(stringResource(R.string.menu_play_nearby)) }
            }

            Column(modifier = Modifier.padding(top = 16.dp)) {
                OutlinedButton(
                    onClick = onSettings,
                    modifier = Modifier.width(240.dp).height(MIN_BUTTON_HEIGHT)
                ) { Text(stringResource(R.string.menu_settings)) }
            }

            Column(modifier = Modifier.padding(top = 8.dp)) {
                TextButton(
                    onClick = { showAbout = true },
                    modifier = Modifier.width(240.dp).height(MIN_BUTTON_HEIGHT)
                ) { Text(stringResource(R.string.menu_about)) }
            }
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(stringResource(R.string.menu_about)) },
            text = {
                Text(
                    stringResource(R.string.about_body) + "\n\n" +
                        stringResource(R.string.about_version, BuildConfig.VERSION_NAME) + "\n" +
                        stringResource(R.string.about_author) + "\n" +
                        stringResource(R.string.about_source) + "\n" +
                        stringResource(R.string.about_music)
                )
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text(stringResource(R.string.close)) }
            }
        )
    }
}
