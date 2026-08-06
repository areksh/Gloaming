package sh.arek.gloaming.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650A4)
val PurpleGrey40 = Color(0xFF625B71)
val Pink40 = Color(0xFF7D5260)

/**
 * Night vision: red on black, and nothing else.
 *
 * Dark adaptation takes twenty to thirty minutes to build and a moment of white
 * light to destroy. Rod cells are close to blind above about 620 nm, so deep red
 * is legible without costing the adaptation - which is why every observatory,
 * ship's bridge and cockpit uses it.
 *
 * Pure red, not a red-tinted palette. A dimmed white screen still emits across
 * the spectrum; only removing the green and blue channels entirely does the job.
 */
val NightRed = Color(0xFFFF0000)
val NightRedDim = Color(0xFFB00000)
val NightRedFaint = Color(0xFF5A0000)
val NightBlack = Color(0xFF000000)
