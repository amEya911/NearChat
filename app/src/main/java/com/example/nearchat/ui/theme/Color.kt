package com.example.nearchat.ui.theme

import androidx.compose.ui.graphics.Color

// ─── Brand Palette ───────────────────────────────────────────────────────────
val NearChatPurple = Color(0xFF6C63FF)
val NearChatPurpleLight = Color(0xFF9D97FF)
val NearChatPurpleDark = Color(0xFF4A42D4)

val NearChatCyan = Color(0xFF00D9FF)
val NearChatGreen = Color(0xFF00E676)
val NearChatAmber = Color(0xFFFFD740)
val NearChatRed = Color(0xFFFF5252)

// ─── Dark Mode ───────────────────────────────────────────────────────────────
val DarkBackground = Color(0xFF0A0E21)
val DarkSurface = Color(0xFF1A1F36)
val DarkSurfaceVariant = Color(0xFF252A40)
val DarkSurfaceHigh = Color(0xFF2E3450)
val DarkOnBackground = Color(0xFFE8EAF6)
val DarkOnSurface = Color(0xFFE0E0E0)
val DarkOnSurfaceVariant = Color(0xFF9E9EAF)

val DarkPrimary = NearChatPurple
val DarkOnPrimary = Color.White
val DarkPrimaryContainer = Color(0xFF3A35A0)
val DarkOnPrimaryContainer = Color(0xFFE0DEFF)
val DarkSecondary = NearChatCyan
val DarkOnSecondary = Color(0xFF003544)
val DarkSecondaryContainer = Color(0xFF004D63)
val DarkOnSecondaryContainer = Color(0xFFB3F0FF)
val DarkTertiary = NearChatGreen
val DarkOnTertiary = Color(0xFF003300)
val DarkError = Color(0xFFFF6B6B)
val DarkOnError = Color(0xFF370000)
val DarkOutline = Color(0xFF404565)

// ─── Light Mode ──────────────────────────────────────────────────────────────
val LightBackground = Color(0xFFF5F5FA)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEEEDF5)
val LightOnBackground = Color(0xFF1A1A2E)
val LightOnSurface = Color(0xFF1A1A2E)
val LightOnSurfaceVariant = Color(0xFF5E5C71)

val LightPrimary = NearChatPurple
val LightOnPrimary = Color.White
val LightPrimaryContainer = Color(0xFFE0DEFF)
val LightOnPrimaryContainer = Color(0xFF1A0073)
val LightSecondary = Color(0xFF00ACC1)
val LightOnSecondary = Color.White
val LightSecondaryContainer = Color(0xFFB3F0FF)
val LightOnSecondaryContainer = Color(0xFF001F26)
val LightTertiary = Color(0xFF00C853)
val LightOnTertiary = Color.White
val LightError = Color(0xFFD32F2F)
val LightOnError = Color.White
val LightOutline = Color(0xFFC4C3D0)

// ─── Chat Bubble Colors ─────────────────────────────────────────────────────
val BubbleSentDark = NearChatPurple
val BubbleSentLight = NearChatPurple
val BubbleReceivedDark = DarkSurfaceVariant
val BubbleReceivedLight = LightSurfaceVariant
val BubbleTextSent = Color.White
val BubbleTimeSent = Color.White.copy(alpha = 0.7f)
val BubbleTextReceivedDark = Color(0xFFE0E0E0)
val BubbleTimeReceivedDark = Color(0xFF9E9EAF)
val BubbleTextReceivedLight = Color(0xFF1A1A2E)
val BubbleTimeReceivedLight = Color(0xFF5E5C71)

// Legacy aliases for compatibility
val BubbleMine = BubbleSentDark
val BubbleMineLight = BubbleSentLight
val BubbleTheirs = BubbleReceivedDark
val BubbleTheirsLight = BubbleReceivedLight