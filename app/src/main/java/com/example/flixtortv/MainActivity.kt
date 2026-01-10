package com.example.flixtortv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent as AndroidKeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedPrefs = getPreferences(Context.MODE_PRIVATE)
        val initialUrl = sharedPrefs.getString("last_url", "https://flixtor.to/") ?: "https://flixtor.to/"

        setContent {
            var showSplashScreen by remember { mutableStateOf(true) }

            if (showSplashScreen) {
                SplashScreen(
                    onAnimationFinished = { showSplashScreen = false }
                )
            } else {
                FlixtorWebView(initialUrl = initialUrl)
            }
        }
    }
}

@Composable
fun SplashScreen(onAnimationFinished: () -> Unit) {
    val context = LocalContext.current

    val alpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 1000),
        label = "fadeIn"
    )

    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 1000),
        label = "scaleUp"
    )

    val pulse by animateFloatAsState(
        targetValue = if (scale == 1f) 1.1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val rotation by animateFloatAsState(
        targetValue = 360f,
        animationSpec = tween(durationMillis = 2000, easing = LinearEasing),
        label = "rotation"
    )

    val shadowElevation by animateFloatAsState(
        targetValue = if (scale == 1f) 16f else 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shadowPulse"
    )

    LaunchedEffect(Unit) {
        try {
            val mediaPlayer = MediaPlayer.create(context, R.raw.launch_sound)
            mediaPlayer?.start()
            mediaPlayer?.setOnCompletionListener { it.release() }
        } catch (e: Exception) {
            Log.e("FlixtorTV", "Error playing launch sound: ${e.message}")
        }

        delay(2000)
        onAnimationFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.logo),
            contentDescription = "FlixtorTV Logo",
            modifier = Modifier
                .size(200.dp)
                .alpha(alpha)
                .scale(scale * pulse)
                .rotate(rotation)
                .shadow(
                    elevation = shadowElevation.dp,
                    shape = CircleShape,
                    ambientColor = Color(0xFF00BFFF),
                    spotColor = Color(0xFF00BFFF)
                )
        )
    }
}


@Composable
fun FlixtorWebView(initialUrl: String) {
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showExitDialog by remember { mutableStateOf(false) }
    var retryCount by remember { mutableStateOf(0) }
    val maxRetries = 3
    val context = LocalContext.current as MainActivity
    val focusRequester = remember { FocusRequester() }

    val cursorWebView = remember {
        CursorWebView(context).apply {
            setWebViewClient(object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    isLoading = true
                    hasError = false
                    errorMessage = null
                    retryCount = 0 // Reset retry count on new page load
                    Log.d("FlixtorTV", "Page started: $url")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isLoading = false
                    hasError = false
                    errorMessage = null
                    context.getPreferences(Context.MODE_PRIVATE)
                        .edit()
                        .putString("last_url", url)
                        .apply()
                    restoreCursorFocus()
                    val contentHeightLog = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        ", Content height: ${view?.contentHeight}"
                    } else {
                        ""
                    }
                    Log.d("FlixtorTV", "Page finished: $url$contentHeightLog")
                }

                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    return if (url?.contains("flixtor.to") == true) {
                        view?.loadUrl(url)
                        false
                    } else {
                        true
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    isLoading = false
                    hasError = true
                    errorMessage = error?.description?.toString() ?: "Unknown error"
                    Log.d("FlixtorTV", "Error: $errorMessage, URL: ${request?.url}")
                }
            })
            loadUrl(initialUrl)
        }
    }

    LaunchedEffect(hasError) {
        cursorWebView.isErrorScreenVisible = hasError
        if (hasError && retryCount < maxRetries) {
            delay(5000)
            if (hasError) {
                retryCount++
                Log.d("FlixtorTV", "Retry attempt $retryCount of $maxRetries")
                isLoading = true
                errorMessage = null
                cursorWebView.loadUrl(cursorWebView.getUrl() ?: initialUrl)
            }
        } else if (hasError && retryCount >= maxRetries) {
            Log.d("FlixtorTV", "Max retries reached, stopping automatic reload")
            // Keep error screen up for manual retry
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { cursorWebView },
            modifier = Modifier.fillMaxSize()
        )

        BackHandler {
            if (cursorWebView.canGoBack() &&
                cursorWebView.getUrl() != "https://flixtor.to/" &&
                cursorWebView.getUrl() != "https://flixtor.to/home") {
                cursorWebView.goBack()
            } else {
                showExitDialog = true
            }
        }

        if (isLoading && !hasError) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        if (hasError) {
            ErrorScreen(
                errorMessage = errorMessage,
                onRetry = {
                    hasError = false
                    isLoading = true
                    errorMessage = null
                    retryCount = 0 // Reset for manual retry
                    cursorWebView.loadUrl(cursorWebView.getUrl() ?: initialUrl)
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .focusRequester(focusRequester)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                            hasError = false
                            isLoading = true
                            errorMessage = null
                            retryCount = 0
                            cursorWebView.loadUrl(cursorWebView.getUrl() ?: initialUrl)
                            true
                        } else {
                            false
                        }
                    }
            )
        }

        if (showExitDialog) {
            AlertDialog(
                onDismissRequest = { showExitDialog = false },
                confirmButton = {
                    TextButton(onClick = { context.finishAffinity() }) { Text("Yes") }
                },
                dismissButton = {
                    TextButton(onClick = { showExitDialog = false }) { Text("No") }
                },
                title = { Text("Exit App?") },
                text = { Text("Do you want to exit Flixtor?") }
            )
        }
    }
}

@Composable
fun ErrorScreen(errorMessage: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    var countdown by remember { mutableStateOf(5) }

    LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1000)
            countdown -= 1
        }
    }

    val montserratFontFamily = FontFamily(
        Font(R.font.montserrat_regular, FontWeight.Normal),
        Font(R.font.montserrat_bold, FontWeight.Bold)
    )

    Column(
        modifier = modifier
            .padding(16.dp)
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Oops! Something went wrong.",
            fontFamily = montserratFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(bottom = 16.dp)
                .shadow(4.dp, shape = RoundedCornerShape(8.dp))
        )
        Text(
            text = errorMessage ?: "Check your internet connection or try again later.",
            fontFamily = montserratFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 20.sp,
            color = Color.LightGray,
            textAlign = TextAlign.Center,
            letterSpacing = 0.5.sp,
            modifier = Modifier
                .padding(bottom = 8.dp)
                .shadow(2.dp, shape = RoundedCornerShape(4.dp))
        )
        Text(
            text = "Retrying in $countdown seconds...",
            fontFamily = montserratFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 18.sp,
            color = Color(0xFFBBDEFB),
            textAlign = TextAlign.Center,
            letterSpacing = 0.5.sp,
            modifier = Modifier
                .padding(bottom = 8.dp)
                .shadow(2.dp, shape = RoundedCornerShape(4.dp))
        )
        Button(
            onClick = onRetry,
            modifier = Modifier
        ) {
            Text(
                text = "Retry Now",
                fontFamily = montserratFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}