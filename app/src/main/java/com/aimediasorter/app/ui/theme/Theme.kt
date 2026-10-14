package com.aimediasorter.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AmoledColorScheme = darkColorScheme(
    primary = PrimaryPurple,
    onPrimary = TextPrimaryWhite,
    primaryContainer = PrimaryPurpleContainer,
    onPrimaryContainer = PrimaryPurpleLight,
    secondary = PrimaryPurpleLight,
    onSecondary = AmoledPureBlack,
    background = AmoledPureBlack,
    onBackground = TextPrimaryWhite,
    surface = AmoledPureBlack,
    onSurface = TextPrimaryWhite,
    surfaceVariant = AmoledCardSurface,
    onSurfaceVariant = TextSecondaryMuted,
    outline = AmoledSubtleBorder,
    error = StatusCoral
)

@Composable
fun AIMediaSorterTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AmoledColorScheme,
        typography = AppTypography,
        content = content
    )
}
