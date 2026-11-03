package com.sol.sillytavern.native.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * App theme for the native client.
 *
 * Colors come from Material 3 defaults for now; screens only depend on [SolTheme], so a
 * later task can swap in the SillyTavern palette without touching every screen.
 */
@Composable
fun SolTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()
