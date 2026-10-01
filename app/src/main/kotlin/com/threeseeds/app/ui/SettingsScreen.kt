package com.threeseeds.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.threeseeds.app.R
import com.threeseeds.app.profile.Economy
import com.threeseeds.app.profile.ProfileData
import com.threeseeds.app.profile.ThinkSpeed
import com.threeseeds.app.theme.ThemeCatalog
import com.threeseeds.engine.ai.AiDifficulty
import com.threeseeds.engine.ai.AiPersonality

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    profile: ProfileData,
    soundEnabled: Boolean,
    musicEnabled: Boolean,
    hapticsEnabled: Boolean,
    debugModeEnabled: Boolean,
    adjacentMovementOnly: Boolean,
    onSoundChanged: (Boolean) -> Unit,
    onMusicChanged: (Boolean) -> Unit,
    onHapticsChanged: (Boolean) -> Unit,
    onDebugModeChanged: (Boolean) -> Unit,
    onAdjacentMovementOnlyChanged: (Boolean) -> Unit,
    onProfileChanged: ((ProfileData) -> ProfileData) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var shopHint by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.menu_settings), style = MaterialTheme.typography.headlineMedium)
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        SettingRow(label = stringResource(R.string.settings_sound), checked = soundEnabled, onCheckedChange = onSoundChanged)
        SettingRow(
            label = stringResource(R.string.settings_music),
            description = stringResource(R.string.settings_music_description),
            checked = musicEnabled,
            onCheckedChange = onMusicChanged
        )
        SettingRow(label = stringResource(R.string.settings_haptics), checked = hapticsEnabled, onCheckedChange = onHapticsChanged)
        SettingRow(
            label = stringResource(R.string.settings_adjacent),
            description = stringResource(R.string.settings_adjacent_description),
            checked = adjacentMovementOnly,
            onCheckedChange = onAdjacentMovementOnlyChanged
        )
        SettingRow(
            label = stringResource(R.string.settings_debug),
            description = stringResource(R.string.settings_debug_description),
            checked = debugModeEnabled,
            onCheckedChange = onDebugModeChanged
        )

        // ---- Computer opponent ----
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        Text(stringResource(R.string.settings_ai_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.settings_ai_description),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )

        ChipRow(label = stringResource(R.string.settings_difficulty))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiDifficulty.entries.forEach { difficulty ->
                FilterChip(
                    selected = profile.difficulty == difficulty,
                    onClick = { onProfileChanged { it.copy(difficulty = difficulty) } },
                    label = { Text(difficultyLabel(difficulty)) }
                )
            }
        }

        ChipRow(label = stringResource(R.string.settings_personality))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiPersonality.entries.forEach { personality ->
                FilterChip(
                    selected = profile.personality == personality,
                    onClick = { onProfileChanged { it.copy(personality = personality) } },
                    label = { Text(personalityLabel(personality)) }
                )
            }
        }

        ChipRow(label = stringResource(R.string.settings_think_speed))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThinkSpeed.entries.forEach { speed ->
                FilterChip(
                    selected = profile.thinkSpeed == speed,
                    onClick = { onProfileChanged { it.copy(thinkSpeed = speed) } },
                    label = { Text(thinkSpeedLabel(speed)) }
                )
            }
        }

        // ---- Themes ----
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        Text(stringResource(R.string.settings_themes), style = MaterialTheme.typography.titleLarge)
        SettingRow(
            label = stringResource(R.string.settings_dynamic_themes),
            description = stringResource(R.string.settings_dynamic_themes_description),
            checked = profile.dynamicThemes,
            onCheckedChange = { enabled -> onProfileChanged { it.copy(dynamicThemes = enabled) } }
        )
        Text(
            stringResource(R.string.settings_coins, profile.coins),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeCatalog.ALL.forEach { theme ->
                val unlocked = theme.id in profile.unlockedThemes
                val affordable = Economy.canAfford(profile, theme.cost)
                val lockedHint = stringResource(R.string.not_enough_coins, theme.cost)
                FilterChip(
                    selected = profile.themeId == theme.id,
                    onClick = {
                        when {
                            unlocked -> onProfileChanged { it.copy(themeId = theme.id) }
                            affordable -> {
                                onProfileChanged { Economy.buyTheme(it, theme.id, theme.cost)?.copy(themeId = theme.id) ?: it }
                                shopHint = null
                            }

                            else -> shopHint = lockedHint
                        }
                    },
                    label = {
                        Text(
                            if (unlocked) theme.name
                            else stringResource(R.string.theme_cost, theme.name, theme.cost)
                        )
                    }
                )
            }
        }
        shopHint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }

        // ---- Stats ----
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        Text(stringResource(R.string.stats_title), style = MaterialTheme.typography.titleLarge)
        StatRow(stringResource(R.string.stats_wins), profile.wins)
        StatRow(stringResource(R.string.stats_losses), profile.losses)
        StatRow(stringResource(R.string.stats_draws), profile.draws)
        StatRow(stringResource(R.string.stats_streak), profile.streak)
        StatRow(stringResource(R.string.stats_best_streak), profile.bestStreak)

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.close))
        }
    }
}

@Composable
private fun ChipRow(label: String) {
    Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
}

@Composable
private fun StatRow(label: String, value: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value.toString(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun difficultyLabel(difficulty: AiDifficulty): String = stringResource(
    when (difficulty) {
        AiDifficulty.BEGINNER -> R.string.difficulty_beginner
        AiDifficulty.EASY -> R.string.difficulty_easy
        AiDifficulty.MEDIUM -> R.string.difficulty_medium
        AiDifficulty.HARD -> R.string.difficulty_hard
        AiDifficulty.EXPERT -> R.string.difficulty_expert
        AiDifficulty.MASTER -> R.string.difficulty_master
    }
)

@Composable
private fun personalityLabel(personality: AiPersonality): String = stringResource(
    when (personality) {
        AiPersonality.BALANCED -> R.string.personality_balanced
        AiPersonality.AGGRESSIVE -> R.string.personality_aggressive
        AiPersonality.DEFENSIVE -> R.string.personality_defensive
        AiPersonality.POSITIONAL -> R.string.personality_positional
        AiPersonality.EXPERIMENTAL -> R.string.personality_experimental
    }
)

@Composable
private fun thinkSpeedLabel(speed: ThinkSpeed): String = stringResource(
    when (speed) {
        ThinkSpeed.INSTANT -> R.string.think_speed_instant
        ThinkSpeed.NATURAL -> R.string.think_speed_natural
        ThinkSpeed.THOUGHTFUL -> R.string.think_speed_thoughtful
    }
)

@Composable
private fun SettingRow(label: String, description: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.labelLarge)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
