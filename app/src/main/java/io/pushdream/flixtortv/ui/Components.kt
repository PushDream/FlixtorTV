package io.pushdream.flixtortv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The logo's tile, drawn: a blue-to-magenta rounded square with an orange-to-magenta play triangle. */
@Composable
fun PlayBadge(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawRoundRect(
            brush = Brush.linearGradient(listOf(Brand.Blue, Brand.Violet, Brand.Magenta), Offset.Zero, Offset(w, h)),
            cornerRadius = CornerRadius(w * 0.26f)
        )
        val triangle = Path().apply {
            moveTo(w * 0.39f, h * 0.29f)
            lineTo(w * 0.73f, h * 0.50f)
            lineTo(w * 0.39f, h * 0.71f)
            close()
        }
        val fill = Brush.linearGradient(listOf(Brand.Orange, Brand.Magenta), Offset(0f, h * 0.29f), Offset(0f, h * 0.71f))
        drawPath(triangle, fill)
        // Same path stroked with round joins softens the corners, like the logo's triangle
        drawPath(triangle, fill, style = Stroke(width = w * 0.07f, join = StrokeJoin.Round))
    }
}

/** A round badge in the brand gradient holding a white icon. */
@Composable
fun IconBadge(icon: ImageVector, size: Dp = 64.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .shadow(20.dp, CircleShape, ambientColor = Brand.Magenta, spotColor = Brand.Violet)
            .background(Brand.DiagonalGradient, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * A pill button for the remote. Focused, it fills with the brand gradient, grows a little and glows;
 * [primary] gives it a gradient outline while it is not focused.
 */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "buttonScale"
    )
    val shape = RoundedCornerShape(50)
    val surface = when {
        focused -> Modifier.background(Brand.HorizontalGradient, shape)
        primary -> Modifier.background(Brand.SurfaceRaised, shape).border(1.5.dp, Brand.HorizontalGradient, shape)
        else -> Modifier.background(Color.White.copy(alpha = 0.06f), shape).border(1.dp, Brand.Outline, shape)
    }

    Row(
        modifier = modifier
            .scale(scale)
            .shadow(if (focused) 22.dp else 0.dp, shape, ambientColor = Brand.Magenta, spotColor = Brand.Violet)
            .clip(shape)
            .then(surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 30.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (focused) Color.White else Brand.TextSecondary,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = text,
            fontFamily = Montserrat,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = if (focused) Color.White else Brand.TextPrimary
        )
    }
}

/** A dark rounded card with a faint gradient edge, for dialogs. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val edge = Brush.linearGradient(listOf(Brand.Blue.copy(alpha = 0.55f), Brand.Magenta.copy(alpha = 0.25f), Color.Transparent))
    Column(
        modifier = modifier
            .shadow(36.dp, shape, ambientColor = Brand.Violet, spotColor = Brand.Violet)
            .clip(shape)
            .background(Brand.Surface)
            .border(1.dp, edge, shape)
            .padding(horizontal = 52.dp, vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

/** Soft brand-coloured light behind centred content. */
fun Modifier.brandGlow(): Modifier = this.background(
    Brush.radialGradient(
        listOf(Brand.Violet.copy(alpha = 0.28f), Brand.Blue.copy(alpha = 0.08f), Color.Transparent),
        radius = 1100f
    )
)

@Composable
private fun Title(text: String) {
    Text(
        text = text,
        fontFamily = Montserrat,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        color = Brand.TextPrimary,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun Body(text: String, color: Color = Brand.TextSecondary, size: Int = 17) {
    Text(
        text = text,
        fontFamily = Montserrat,
        fontSize = size.sp,
        lineHeight = (size * 1.45f).sp,
        color = color,
        textAlign = TextAlign.Center
    )
}

/** Asked before leaving the app. "Keep watching" has focus, so a stray Back press does not close the app. */
@Composable
fun ExitDialog(onStay: () -> Unit, onExit: () -> Unit) {
    val stayFocus = remember { FocusRequester() }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        stayFocus.requestFocus()
        appear.animateTo(1f, tween(durationMillis = 240, easing = FastOutSlowInEasing))
    }
    BackHandler(onBack = onStay)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(Brand.Scrim),
        contentAlignment = Alignment.Center
    ) {
        GlassCard(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .graphicsLayer {
                    val s = 0.92f + 0.08f * appear.value
                    scaleX = s
                    scaleY = s
                }
        ) {
            PlayBadge(size = 68.dp)
            Spacer(Modifier.height(24.dp))
            Title("Leaving already?")
            Spacer(Modifier.height(10.dp))
            Body("Exit FlixtorTV and go back to the home screen.")
            Spacer(Modifier.height(34.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                TvButton(
                    text = "Keep watching",
                    onClick = onStay,
                    modifier = Modifier.focusRequester(stayFocus),
                    icon = Icons.Rounded.PlayArrow,
                    primary = true
                )
                TvButton(
                    text = "Exit",
                    onClick = onExit,
                    icon = Icons.AutoMirrored.Rounded.ExitToApp
                )
            }
        }
    }
}

/** The page itself failed to load. */
@Composable
fun ErrorScreen(errorMessage: String, errorDetail: String, secondsToRetry: Int, onRetry: () -> Unit) {
    val retryFocus = remember { FocusRequester() }
    // The remote lands on Try again straight away
    LaunchedEffect(Unit) { retryFocus.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brand.Background)
            .brandGlow(),
        contentAlignment = Alignment.Center
    ) {
        GlassCard(modifier = Modifier.widthIn(max = 680.dp)) {
            IconBadge(Icons.Rounded.Warning)
            Spacer(Modifier.height(24.dp))
            Title("Something went wrong")
            Spacer(Modifier.height(10.dp))
            Body(errorMessage, color = Brand.TextPrimary, size = 19)
            if (errorDetail.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Body(errorDetail, color = Brand.TextMuted, size = 13)
            }
            Spacer(Modifier.height(18.dp))
            Body(
                text = when {
                    secondsToRetry > 1 -> "Trying again in $secondsToRetry seconds…"
                    secondsToRetry == 1 -> "Trying again in 1 second…"
                    else -> "It will try again by itself when the connection returns."
                },
                color = Brand.Blue.copy(alpha = 0.9f),
                size = 15
            )
            Spacer(Modifier.height(30.dp))
            TvButton(
                text = "Try again",
                onClick = onRetry,
                modifier = Modifier.focusRequester(retryFocus),
                icon = Icons.Rounded.Refresh,
                primary = true
            )
        }
    }
}

/** Page load progress: a thin line through the brand colours that glides instead of jumping. */
@Composable
fun LoadingBar(progress: Int, modifier: Modifier = Modifier) {
    val fraction by animateFloatAsState(
        targetValue = (progress / 100f).coerceIn(0.03f, 1f),
        animationSpec = tween(durationMillis = 280),
        label = "loadingBar"
    )
    Box(modifier = modifier.fillMaxWidth().height(3.dp)) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(Brand.HorizontalGradient, RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
        )
    }
}

/** A short gradient sweeping along a faint track, under the splash logo. */
@Composable
fun SplashLoader(modifier: Modifier = Modifier) {
    val trackWidth = 160.dp
    val sweep = rememberInfiniteTransition(label = "splashLoader")
    val position by sweep.animateFloat(
        initialValue = -0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1100, easing = FastOutSlowInEasing)),
        label = "splashSweep"
    )
    Box(
        modifier = modifier
            .width(trackWidth)
            .height(3.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.08f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.45f)
                .offset { IntOffset((position * trackWidth.toPx()).toInt(), 0) }
                .background(Brand.HorizontalGradient, RoundedCornerShape(50))
        )
    }
}

@Composable
private fun KeyCap(label: String) {
    Box(
        modifier = Modifier
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, fontFamily = Montserrat, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Brand.TextPrimary)
    }
}

@Composable
private fun HintItem(key: String, action: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        KeyCap(key)
        Text(action, fontFamily = Montserrat, fontSize = 15.sp, color = Brand.TextSecondary)
    }
}

/** How the remote works, in one glass pill. Shown for the first few launches. */
@Composable
fun RemoteHint(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .shadow(10.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Brand.Surface.copy(alpha = 0.94f))
            .border(1.dp, Brand.Outline, shape)
            .padding(horizontal = 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        HintItem("◀ ▲ ▼ ▶", "Move")
        HintItem("Hold", "Faster")
        HintItem("OK", "Click")
        HintItem("Back", "Go back")
    }
}
