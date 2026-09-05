package me.kavishdevar.librepods.wear.ui

import androidx.compose.ui.graphics.Color

/**
 * Material You-inspired color scheme for LibrePods Wear
 * Based on the dark theme from the alarm-sync-wear simulator
 */
object LibrePodsColors {
    // Neutral colors for backgrounds and surfaces
    val Neutral950 = Color(0xFF0A0A0A)  // Deepest background
    val Neutral900 = Color(0xFF171717)  // Main background
    val Neutral800 = Color(0xFF262626)  // Card background
    val Neutral700 = Color(0xFF404040)  // Borders
    val Neutral600 = Color(0xFF525252)  // Secondary text
    val Neutral500 = Color(0xFF737373)  // Tertiary text
    val Neutral400 = Color(0xFFA3A3A3)  // Placeholder text
    val Neutral300 = Color(0xFFD4D4D4)  // Primary text
    val Neutral200 = Color(0xFFE5E5E5)  // High-emphasis text
    val Neutral100 = Color(0xFFF5F5F5)  // Highest-emphasis text

    // Accent colors
    val Blue600 = Color(0xFF2563EB)    // Primary action
    val Blue500 = Color(0xFF3B82F6)    // Hover state
    val Blue400 = Color(0xFF60A5FA)    // Light accent
    val Blue300 = Color(0xFF93C5FD)    // Subtle accent

    // Status colors
    val Emerald500 = Color(0xFF10B981)  // Success/connected
    val Amber500 = Color(0xFFF59E0B)    // Warning
    val Red600 = Color(0xFFDC2626)      // Error/danger
    val Red500 = Color(0xFFEF4444)      // Error hover

    // Semi-transparent backgrounds
    val Blue600_30 = Color(0x4D2563EB)   // 30% opacity
    val Blue500_15 = Color(0x263B82F6)  // 15% opacity
    val Red500_20 = Color(0x33EF4444)   // 20% opacity
    val Neutral800_90 = Color(0xE6262626) // 90% opacity
    val Neutral900_40 = Color(0x66171717) // 40% opacity
}