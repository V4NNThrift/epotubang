package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Bold Typography Design Theme Colors
val DesignBg = Color(0xFFFEF7FF)              // Warm lavender-white light bg
val DesignTextDark = Color(0xFF1D1B20)        // Deep near-black text
val DesignPrimaryPurple = Color(0xFF6750A4)   // Deep royal purple accent
val DesignSecondaryPurple = Color(0xFFE8DEF8) // Semi-transparent / light lavender accent
val DesignSurfaceCard = Color(0xFFF3EDF7)     // Grey-violet surface cards
val DesignBorder = Color(0xFFCAC4D0)          // M3 style clean outline border
val DesignAccentYellow = Color(0xFFFFBD2E)    // Warm yellow warning/badge highlight
val DesignWhite = Color(0xFFFFFFFF)

// Compatibility fallbacks (keep compile happy)
val SlateDarkBg = DesignBg
val SlateCardBg = DesignWhite
val SlateCardOutlined = DesignBorder
val ElectricMint = DesignPrimaryPurple
val SkyBlueAccent = DesignSecondaryPurple
val SlateTextLight = DesignTextDark
val SlateTextMuted = Color(0xFF49454F)        // M3 style dark gray/muted text

val Purple80 = DesignPrimaryPurple
val PurpleGrey80 = DesignSecondaryPurple
val Pink80 = DesignAccentYellow

val Purple40 = DesignPrimaryPurple
val PurpleGrey40 = DesignSecondaryPurple
val Pink40 = DesignAccentYellow

