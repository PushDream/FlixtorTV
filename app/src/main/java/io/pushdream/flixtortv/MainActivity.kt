package io.pushdream.flixtortv

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

private const val TAG = "FlixtorTV"
private const val HOME_URL = "https://flixtor.to/"
private const val PREF_LAST_URL = "last_url"

private const val SPLASH_MIN_MS = 1200L       // the logo always shows at least this long
private const val SPLASH_MAX_MS = 4000L       // and never longer than this, ready or not
private const val PAGE_READY_PROGRESS = 90    // the page counts as ready to show from here

private const val MAX_AUTO_RETRIES = 3
private const val RETRY_DELAY_S = 5

private val Accent = Color(0xFF00BFFF)

private fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        @Suppress("DEPRECATION") return cm.activeNetworkInfo?.isConnected == true
    }
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** What went wrong, in words, for the error screen's headline. */
private fun describeError(context: Context, errorCode: Int?): String = when {
    !isOnline(context) -> "Your TV is not connected to the internet."
    errorCode == WebViewClient.ERROR_HOST_LOOKUP -> "Couldn't find the site. Your internet or DNS may be down."
    errorCode == WebViewClient.ERROR_TIMEOUT -> "The site took too long to respond."
    errorCode == WebViewClient.ERROR_CONNECT -> "Couldn't connect to the site."
    errorCode == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "A secure connection to the site failed."
    else -> "The page couldn't be loaded."
}

/** Pages of the site itself. Everything else (pop-unders, ad redirects, app links) is kept out. */
private fun isSiteUrl(uri: Uri?): Boolean {
    val scheme = uri?.scheme?.lowercase()
    if (scheme != "https" && scheme != "http") return false
    val host = uri.host?.lowercase() ?: return false
    return host == "flixtor.to" || host.endsWith(".flixtor.to")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = getPreferences(Context.MODE_PRIVATE).getString(PREF_LAST_URL, null)
        val initialUrl = saved?.takeIf { isSiteUrl(Uri.parse(it)) } ?: HOME_URL

        setContent {
            FlixtorApp(initialUrl = initialUrl)
        }
    }
}

/**
 * The splash is drawn first; the WebView (about two seconds of Chromium start-up on a TV, on the main thread)
 * is created right after, so the logo is on screen during that time and the page loads behind it. The splash
 * fades out when the page is ready to show.
 */
@Composable
fun FlixtorApp(initialUrl: String) {
    var webStarted by remember { mutableStateOf(false) }
    var pageReady by remember { mutableStateOf(false) }
    var splashDone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withFrameNanos { }   // the splash's first frame
        withFrameNanos { }   // and the one that puts it on screen
        webStarted = true
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (webStarted) {
            FlixtorWebView(initialUrl = initialUrl, onPageReady = { pageReady = true })
        }
        if (!splashDone) {
            SplashScreen(webStarted = webStarted, pageReady = pageReady, onFinished = { splashDone = true })
        }
    }
}

@Composable
fun SplashScreen(webStarted: Boolean, pageReady: Boolean, onFinished: () -> Unit) {
    val context = LocalContext.current
    val logoIn = remember { Animatable(0.6f) }    // the logo is visible from the first frame, then settles in
    val splashOut = remember { Animatable(1f) }   // 1 to 0: the whole splash fades out
    var minTimePassed by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val mediaPlayer = MediaPlayer.create(context, R.raw.launch_sound)
            mediaPlayer?.start()
            mediaPlayer?.setOnCompletionListener { it.release() }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing launch sound: ${e.message}")
        }
    }

    // Timed from when the WebView exists: its start-up blocks the main thread, and would otherwise use up
    // the splash's time before the page has even started loading.
    LaunchedEffect(webStarted) {
        if (!webStarted) return@LaunchedEffect
        logoIn.animateTo(1f, tween(durationMillis = 500, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(webStarted) {
        if (!webStarted) return@LaunchedEffect
        delay(SPLASH_MIN_MS)
        minTimePassed = true
        delay(SPLASH_MAX_MS - SPLASH_MIN_MS)
        timedOut = true
    }

    val leave = minTimePassed && (pageReady || timedOut)
    LaunchedEffect(leave) {
        if (leave) {
            splashOut.animateTo(0f, tween(durationMillis = 350))
            onFinished()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(splashOut.value)
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.logo),
            contentDescription = "FlixtorTV Logo",
            modifier = Modifier
                .size(200.dp)
                .graphicsLayer {
                    alpha = logoIn.value
                    val scale = 0.85f + 0.15f * logoIn.value
                    scaleX = scale
                    scaleY = scale
                }
        )
    }
}

@Composable
fun FlixtorWebView(initialUrl: String, onPageReady: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val pageReady by rememberUpdatedState(onPageReady)

    var progress by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }   // set while the page itself failed
    var errorDetail by remember { mutableStateOf("") }               // Chromium's own code, for reference
    var autoRetries by remember { mutableIntStateOf(0) }
    var secondsToRetry by remember { mutableIntStateOf(0) }
    var showExitDialog by remember { mutableStateOf(false) }

    val cursorWebView = remember {
        CursorWebView(activity).apply {
            var mainFrameFailed = false

            onProgressChanged = { newProgress ->
                progress = newProgress
                if (newProgress >= PAGE_READY_PROGRESS) pageReady()
            }

            setWebViewClient(object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    mainFrameFailed = false
                    isLoading = true
                    updateLastActivityTime()
                    Log.d(TAG, "Page started: $url")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isLoading = false
                    pageReady()
                    // Chromium also "finishes" its own error page: keep the error screen up for that one
                    if (!mainFrameFailed) {
                        errorMessage = null
                        autoRetries = 0
                        if (isSiteUrl(url?.let(Uri::parse))) {
                            activity.getPreferences(Context.MODE_PRIVATE).edit().putString(PREF_LAST_URL, url).apply()
                        }
                    }
                    restoreCursorFocus()
                    Log.d(TAG, "Page finished: $url, Content height: ${view?.contentHeight}")
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    // Frames (video players) navigate freely; the page itself stays on the site
                    if (request == null || !request.isForMainFrame) return false
                    val blocked = !isSiteUrl(request.url)
                    if (blocked) Log.d(TAG, "Blocked navigation away to ${request.url.host}")
                    return blocked
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    // A failed ad, image or tracker is not a broken page
                    if (request?.isForMainFrame != true) return
                    mainFrameFailed = true
                    isLoading = false
                    errorDetail = error?.description?.toString().orEmpty()
                    errorMessage = describeError(activity, error?.errorCode)
                    pageReady()
                    Log.d(TAG, "Error: $errorDetail, URL: ${request.url}")
                }

                @RequiresApi(Build.VERSION_CODES.O)
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    // Usually the TV ran out of memory. The WebView cannot be used again: start over
                    // with a fresh activity instead of crashing the app.
                    Log.e(TAG, "Renderer gone (crashed: ${detail?.didCrash()}), recreating")
                    activity.recreate()
                    return true
                }
            })
            loadUrl(initialUrl)
        }
    }

    fun retry() {
        errorMessage = null
        isLoading = true
        cursorWebView.loadUrl(cursorWebView.getUrl() ?: initialUrl)
    }

    // Pause the page whenever the app is not on screen, so video, scripts and timers stop
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> cursorWebView.onResume()
                Lifecycle.Event.ON_PAUSE -> cursorWebView.onPause()
                else -> {}
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            cursorWebView.onDestroy()
        }
    }

    // Back online while the error screen is up: try again right away
    DisposableEffect(activity) {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cursorWebView.post {
                    if (errorMessage != null) {
                        Log.d(TAG, "Network is back, retrying")
                        autoRetries = 0
                        retry()
                    }
                }
            }
        }
        val registered = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
        onDispose { if (registered) runCatching { cm.unregisterNetworkCallback(callback) } }
    }

    // Retry a failed page by itself a few times, counting down on the error screen
    LaunchedEffect(errorMessage, autoRetries) {
        cursorWebView.isErrorScreenVisible = errorMessage != null
        secondsToRetry = 0
        if (errorMessage != null && autoRetries < MAX_AUTO_RETRIES) {
            for (s in RETRY_DELAY_S downTo 1) {
                secondsToRetry = s
                delay(1000)
            }
            secondsToRetry = 0
            autoRetries++
            Log.d(TAG, "Retry attempt $autoRetries of $MAX_AUTO_RETRIES")
            retry()
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

        // A thin bar at the top instead of a spinner over the page
        if (isLoading && errorMessage == null && progress < 100) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.TopCenter),
                color = Accent,
                trackColor = Color.Transparent
            )
        }

        errorMessage?.let { message ->
            ErrorScreen(
                errorMessage = message,
                errorDetail = errorDetail,
                secondsToRetry = secondsToRetry,
                onRetry = {
                    autoRetries = 0
                    retry()
                }
            )
        }

        if (showExitDialog) {
            AlertDialog(
                onDismissRequest = { showExitDialog = false },
                confirmButton = {
                    TextButton(onClick = { activity.finishAffinity() }) { Text("Yes") }
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
fun ErrorScreen(errorMessage: String, errorDetail: String, secondsToRetry: Int, onRetry: () -> Unit) {
    val retryFocus = remember { FocusRequester() }
    val retryInteraction = remember { MutableInteractionSource() }
    val retryFocused by retryInteraction.collectIsFocusedAsState()
    // The remote lands on Retry straight away
    LaunchedEffect(Unit) { retryFocus.requestFocus() }

    val montserratFontFamily = FontFamily(
        Font(R.font.montserrat_regular, FontWeight.Normal),
        Font(R.font.montserrat_bold, FontWeight.Bold)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0F))
            .padding(16.dp),
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
            modifier = Modifier.padding(bottom = 16.dp)
        )
        Text(
            text = errorMessage,
            fontFamily = montserratFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 20.sp,
            color = Color.LightGray,
            textAlign = TextAlign.Center,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        if (errorDetail.isNotBlank()) {
            Text(
                text = errorDetail,
                fontFamily = montserratFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
        Text(
            text = when {
                secondsToRetry > 1 -> "Retrying in $secondsToRetry seconds..."
                secondsToRetry == 1 -> "Retrying in 1 second..."
                else -> "It will try again by itself when the connection returns, or press OK now."
            },
            fontFamily = montserratFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 18.sp,
            color = Color(0xFFBBDEFB),
            textAlign = TextAlign.Center,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        Button(
            onClick = onRetry,
            modifier = Modifier
                .focusRequester(retryFocus)
                .scale(if (retryFocused) 1.1f else 1f),
            interactionSource = retryInteraction,
            border = if (retryFocused) BorderStroke(2.dp, Color.White) else null,
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)
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
