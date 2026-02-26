package me.eternal.purrfectsnap.ui.util

import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import me.eternal.purrfectsnap.ui.manager.theme.PurrfectPalette

@Composable
fun purrfectSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = PurrfectPalette.glowSecondary.copy(alpha = 0.55f),
    checkedBorderColor = PurrfectPalette.glowSecondary.copy(alpha = 0.5f),
    uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
    uncheckedTrackColor = Color.White.copy(alpha = 0.25f),
    uncheckedBorderColor = Color.White.copy(alpha = 0.3f),
    disabledCheckedTrackColor = PurrfectPalette.glowSecondary.copy(alpha = 0.2f),
    disabledUncheckedTrackColor = Color.White.copy(alpha = 0.12f),
    disabledCheckedThumbColor = Color.White.copy(alpha = 0.5f),
    disabledUncheckedThumbColor = Color.White.copy(alpha = 0.35f)
)
