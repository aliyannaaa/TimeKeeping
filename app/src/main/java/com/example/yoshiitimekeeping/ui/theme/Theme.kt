package com.example.yoshiitimekeeping.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// We use unique names here so they don't clash with Color.kt
val YoshiiPrimary = Color(0xFF1A46B8)
val YoshiiSecondary = Color(0xFF7E19D4)
val YoshiiBackground = Color(0xFFF8F9FF)

// Unique names for the default colors to stop the "Red" errors
val DefaultPurple = Color(0xFFD0BCFF)
val DefaultGrey = Color(0xFFCCC2DC)
val DefaultPink = Color(0xFFEFB8C8)

private val DarkColorScheme = darkColorScheme(
    primary = YoshiiPrimary,
    secondary = YoshiiSecondary,
    tertiary = DefaultPink
)

private val LightColorScheme = lightColorScheme(
    primary = YoshiiPrimary,
    secondary = YoshiiSecondary,
    tertiary = DefaultPink,
    background = YoshiiBackground,
    surface = Color.White
)

@Composable
fun YoshiiTimeKeepingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}