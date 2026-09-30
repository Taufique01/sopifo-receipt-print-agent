package com.sopifo.printagent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val StatusGood = Color(0xFF1B873F)
val StatusBad = Color(0xFFC62828)
val StatusNeutral = Color(0xFF6B7280)

private val Light = lightColorScheme(
    primary = Color(0xFF1F4FD1),
    onPrimary = Color.White,
    secondary = Color(0xFF3B4A6B),
    background = Color(0xFFF7F8FA),
    surface = Color.White,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9DB6FF),
    secondary = Color(0xFFB9C6E4),
)

@Composable
fun SopifoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
