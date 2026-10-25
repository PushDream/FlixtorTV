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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.pushdream.flixtortv.ui.Brand
import io.pushdream.flixtortv.ui.ErrorScreen
import io.pushdream.flixtortv.ui.ExitDialog
import io.pushdream.flixtortv.ui.LoadingBar
import io.pushdream.flixtortv.ui.RemoteHint
import io.pushdream.flixtortv.ui.SplashLoader
import io.pushdream.flixtortv.ui.brandGlow
import kotlinx.coroutines.delay

private const val TAG = "FlixtorTV"
private const val HOME_URL = "https://flixtor.to/"
private const val PREF_LAST_URL = "last_url"

private const val SPLASH_MIN_MS = 1200L       // the logo always shows at least this long
private const val SPLASH_MAX_MS = 4000L       // and never longer than this, ready or not
private const val PAGE_READY_PROGRESS = 90    // the page counts as ready to show from here

private const val PREF_HINTS_SHOWN = "remote_hints_shown"
private const val HINT_LAUNCHES = 3            // the remote hint shows on the first three launches
private const val HINT_DELAY_MS = 800L
private const val HINT_SHOW_MS = 7000L

private const val MAX_AUTO_RETRIES = 3
private const val RETRY_DELAY_S = 5

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
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val saved = prefs.getString(PREF_LAST_URL, null)
        val initialUrl = saved?.takeIf { isSiteUrl(Uri.parse(it)) } ?: HOME_URL

        // The remote hint is for newcomers: the first few launches only
        val hintsShown = prefs.getInt(PREF_HINTS_SHOWN, 0)
        val showRemoteHint = hintsShown < HINT_LAUNCHES
        if (showRemoteHint) prefs.edit().putInt(PREF_HINTS_SHOWN, hintsShown + 1).apply()

        setContent {
            FlixtorApp(initialUrl = initialUrl, showRemoteHint = showRemoteHint)
        }
    }
}

/**
 * The splash is drawn first; the WebView (about two seconds of Chromium start-up on a TV, on the main thread)
 * is created right after, so the logo is on screen during that time and the page loads behind it. The splash
 * fades out when the page is ready to show.
 */
@Composable
fun FlixtorApp(initialUrl: String, showRemoteHint: Boolean) {
    var webStarted by remember { mutableStateOf(false) }
    var pageReady by remember { mutableStateOf(false) }
    var splashDone by remember { mutableStateOf(false) }
    var hintVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withFrameNanos { }   // the splash's first frame
        withFrameNanos { }   // and the one that puts it on screen
        webStarted = true
    }

    LaunchedEffect(splashDone) {
        if (splashDone && showRemoteHint) {
            delay(HINT_DELAY_MS)
            hintVisible = true
            delay(HINT_SHOW_MS)
            hintVisible = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Brand.Background)) {
        if (webStarted) {
            FlixtorWebView(initialUrl = initialUrl, onPageReady = { pageReady = true })
        }
        AnimatedVisibility(
            visible = hintVisible,
            enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 2 },
            exit = fadeOut(tween(500)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
        ) {
            RemoteHint()
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
            .background(Brand.Background)
            .brandGlow(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "FlixtorTV Logo",
                modifier = Modifier
                    .size(220.dp)
                    .graphicsLayer {
                        alpha = logoIn.value
                        val scale = 0.85f + 0.15f * logoIn.value
                        scaleX = scale
                        scaleY = scale
                    }
            )
            Spacer(Modifier.height(8.dp))
            SplashLoader(modifier = Modifier.alpha(logoIn.value))
        }
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
            LoadingBar(progress = progress, modifier = Modifier.align(Alignment.TopCenter))
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
            ExitDialog(
                onStay = {
                    showExitDialog = false
                    // The dialog had focus: give it back to the page and its pointer
                    cursorWebView.restoreCursorFocus()
                },
                onExit = { activity.finishAffinity() }
            )
        }
    }
}
