package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

private val DarkColorScheme =
  darkColorScheme(
    primary = DesignPrimaryPurple,
    secondary = DesignSecondaryPurple,
    tertiary = DesignAccentYellow,
    background = DesignBg,
    surface = DesignSurfaceCard,
    onBackground = DesignTextDark,
    onSurface = DesignTextDark
  )

private val LightColorScheme =
  lightColorScheme(
    primary = DesignPrimaryPurple,
    secondary = DesignSecondaryPurple,
    tertiary = DesignAccentYellow,
    background = DesignBg,
    surface = DesignWhite,
    onBackground = DesignTextDark,
    onSurface = DesignTextDark
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Disable dynamic color so the Bold Typography theme colors always show identically
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
