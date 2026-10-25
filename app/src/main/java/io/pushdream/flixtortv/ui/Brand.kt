package io.pushdream.flixtortv.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.pushdream.flixtortv.R

/** The look of the app, taken from the logo: neon blue, violet, magenta and orange on near-black. */
object Brand {
    val Blue = Color(0xFF1E8FFF)
    val Violet = Color(0xFF6A3DF0)
    val Magenta = Color(0xFFD23CE0)
    val Orange = Color(0xFFFF8A3D)

    val Background = Color(0xFF07070C)
    val Surface = Color(0xFF14141F)
    val SurfaceRaised = Color(0xFF1C1C2B)
    val Outline = Color(0x26FFFFFF)
    val Scrim = Color(0xB8000000)

    val TextPrimary = Color(0xFFF5F5FA)
    val TextSecondary = Color(0xFFB4B4C8)
    val TextMuted = Color(0xFF7A7A90)

    private val Spectrum = listOf(Blue, Violet, Magenta, Orange)

    /** Left to right through the whole spectrum: progress, focused buttons. */
    val HorizontalGradient = Brush.horizontalGradient(Spectrum)

    /** Corner to corner, like the logo's tile. */
    val DiagonalGradient = Brush.linearGradient(listOf(Blue, Violet, Magenta))
}

val Montserrat = FontFamily(
    Font(R.font.montserrat_regular, FontWeight.Normal),
    Font(R.font.montserrat_bold, FontWeight.Bold)
)
