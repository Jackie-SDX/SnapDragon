package com.threeseeds.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.threeseeds.app.R
import com.threeseeds.app.theme.LocalGameTheme

private const val MAX_NAME_LENGTH = 20

/**
 * First-run registration: pick a display name once, persist it, and
 * step into the menu. Rendered on the themed background like every
 * other screen, so the brand moment doubles as the welcome moment.
 */
@Composable
fun WelcomeScreen(
    onDone: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    val canStart = name.isNotBlank()
    val theme = LocalGameTheme.current
    val start = {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) onDone(trimmed)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(brush = theme.accentBrush(), fontWeight = FontWeight.Black)) {
                    append(stringResource(R.string.app_name))
                }
            },
            style = MaterialTheme.typography.displayMedium,
        )
        Text(
            text = stringResource(R.string.tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = theme.accentColor,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(R.string.welcome_ask),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 40.dp, bottom = 16.dp),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { input -> if (input.length <= MAX_NAME_LENGTH) name = input },
            label = { Text(stringResource(R.string.welcome_name_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { start() }),
        )
        Text(
            text = stringResource(R.string.welcome_hint),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Button(
            onClick = start,
            enabled = canStart,
            modifier = Modifier
                .padding(top = 32.dp)
                .width(240.dp)
                .height(56.dp),
        ) {
            Text(stringResource(R.string.welcome_start), style = MaterialTheme.typography.titleMedium)
        }
    }
}
