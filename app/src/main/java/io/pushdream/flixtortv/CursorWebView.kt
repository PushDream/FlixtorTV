package io.pushdream.flixtortv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.KeyEvent as AndroidKeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * A WebView driven by a D-pad "mouse" pointer.
 *
 * Focus model: the inner WebView always holds Android focus (so Chromium's text input works the way it
 * does on a phone), and this parent intercepts the remote's keys in [dispatchKeyEvent] while the pointer
 * is active. Nothing ever has to steal focus back and forth.
 *
 * Text fields: a click is a real tap on the page. Afterwards we ask the page whether the focused element
 * is editable; if it is, we switch to typing mode (keyboard up, keys go to the keyboard, pointer hidden).
 * When the keyboard closes, typing mode ends and the pointer comes back where it was.
 */
class CursorWebView @SuppressLint("SetJavaScriptEnabled")
constructor(private val ctx: Context) : FrameLayout(ctx) {

    companion object {
        private const val TAG = "FlixtorTV"
        private const val CURSOR_HIDE_IDLE_TIMEOUT = 4000L

        // Cursor motion, as fractions of the screen width per second.
        private const val HOLD_DELAY_MS = 250f     // a press shorter than this is one step; longer starts gliding
        private const val SPEED_START = 0.14f      // first moments of a glide: slow enough to aim at a text field
        private const val SPEED_MAX = 0.80f        // full speed, reached ACCEL_TIME_MS into the glide
        private const val ACCEL_TIME_MS = 900f     // ease up to SPEED_MAX over this long
        private const val TAP_STEP = 1f / 110f     // one short press moves exactly this far (about 17 px at 1080p)
        private const val MAX_FRAME_MS = 50f       // never jump more than this much time in one frame

        private const val TAP_UP_DELAY_MS = 40L        // between the tap's down and up
        private const val EDIT_CHECK_DELAY_MS = 180L   // let the page's own click handlers run first
        private const val KEYBOARD_WAIT_MS = 1200L     // keyboard must appear within this after a text field click
        private const val KEYBOARD_CLOSE_DEBOUNCE_MS = 250L  // "Next" field: keyboard hides and comes right back

        // Is the page's focused element (looking into open shadow roots) something you can type into?
        private val FOCUSED_EDITABLE_JS = """
            (function() {
                try {
                    var a = document.activeElement;
                    while (a && a.shadowRoot && a.shadowRoot.activeElement) a = a.shadowRoot.activeElement;
                    if (!a || a.disabled || a.readOnly) return false;
                    var tag = (a.tagName || '').toLowerCase();
                    if (tag === 'textarea') return true;
                    if (tag === 'input') {
                        var type = (a.getAttribute('type') || 'text').toLowerCase();
                        return ['text', 'password', 'email', 'tel', 'url', 'search', 'number'].indexOf(type) >= 0;
                    }
                    return !!a.isContentEditable;
                } catch (e) { return false; }
            })()
        """.trimIndent()

        // Scroll the focused field above the keyboard now, and whenever focus moves to another field
        // (the keyboard's Next key), which does not hide and reshow the keyboard.
        private val SCROLL_FOCUSED_INTO_VIEW_JS = """
            (function() {
                function editable(a) {
                    if (!a) return false;
                    var tag = (a.tagName || '').toLowerCase();
                    return tag === 'input' || tag === 'textarea' || a.isContentEditable;
                }
                function reveal(a) {
                    try { if (editable(a) && a.scrollIntoView) a.scrollIntoView({block: 'center', inline: 'nearest'}); } catch (e) {}
                }
                if (!window.__flixtorFocusHook) {
                    window.__flixtorFocusHook = true;
                    document.addEventListener('focusin', function(e) {
                        var t = e.target;
                        setTimeout(function() { if (document.activeElement === t) reveal(t); }, 150);
                    }, true);
                }
                reveal(document.activeElement);
            })()
        """.trimIndent()

        private val BLUR_FOCUSED_EDITABLE_JS = """
            (function() {
                try {
                    var a = document.activeElement;
                    if (!a) return;
                    var tag = (a.tagName || '').toLowerCase();
                    if (tag === 'input' || tag === 'textarea' || a.isContentEditable) a.blur();
                } catch (e) {}
            })()
        """.trimIndent()

        // Scroll what is under the pointer: the innermost box that can still move that way (a dialog, a list,
        // a carousel), else the page. Called with CSS pixel arguments (x, y, dx, dy).
        private val SCROLL_AT_POINT_JS = """
            (function(x, y, dx, dy) {
                try {
                    function can(e) {
                        var s = getComputedStyle(e);
                        var oy = s.overflowY, ox = s.overflowX;
                        var sy = dy !== 0 && (oy === 'auto' || oy === 'scroll' || oy === 'overlay') &&
                            (dy > 0 ? e.scrollTop + e.clientHeight < e.scrollHeight - 1 : e.scrollTop > 0);
                        var sx = dx !== 0 && (ox === 'auto' || ox === 'scroll' || ox === 'overlay') &&
                            (dx > 0 ? e.scrollLeft + e.clientWidth < e.scrollWidth - 1 : e.scrollLeft > 0);
                        return sy || sx;
                    }
                    var el = document.elementFromPoint(x, y);
                    while (el && el !== document.body && el !== document.documentElement) {
                        if (can(el)) { el.scrollBy(dx, dy); return; }
                        el = el.parentElement;
                    }
                    window.scrollBy(dx, dy);
                } catch (e) { window.scrollBy(dx, dy); }
            })
        """.trimIndent()

        // Is a video or audio element on the page playing? (Players inside other sites' frames are not visible.)
        private val MEDIA_PLAYING_JS = """
            (function() {
                try {
                    return Array.prototype.some.call(document.querySelectorAll('video, audio'), function(m) {
                        return !m.paused && !m.ended && m.readyState > 2;
                    });
                } catch (e) { return false; }
            })()
        """.trimIndent()

        private const val PLAYBACK_CHECK_MS = 20_000L
        private const val HOVER_HEARTBEAT_MS = 800L
        private const val FALLBACK_CHROME_VERSION = "130.0.0.0"
    }

    /** Page load progress, 0 to 100, for a progress bar. */
    var onProgressChanged: ((Int) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val webView: WebView = WebView(ctx)

    var isErrorScreenVisible = false

    // Pointer state
    private val cursorPosition = PointF(0f, 0f)
    private var cursorVisible = false
    private var pointerModeEnabled = true   // false only while a fullscreen video is up
    private var holdLeft = false
    private var holdRight = false
    private var holdUp = false
    private var holdDown = false
    private var holdStartMs = 0L
    private var lastFrameNanos = 0L
    private var frameLoopRunning = false

    // Center button: a tap on release, or a drag when the pointer moves while it is held
    private var centerDown = false
    private var dragging = false
    private var dragDownTime = 0L
    private val pressPosition = PointF(0f, 0f)

    // Typing state
    private var typingMode = false
    private var isKeyboardVisible = false
    private var keyboardRetried = false

    // Drawing
    private val paint = Paint().apply { isAntiAlias = true }
    private var cursorRadius = 0f
    private var cursorRadiusPressed = 0f
    private var cursorStrokeWidth = 0f
    private var scrollEdge = 0

    // Fullscreen video support
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalSystemUiVisibility: Int = 0

    // Keyboard detection
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    // Edge scrolling, in view pixels not yet sent to the page; one script call in flight at a time
    private var pendingScrollX = 0f
    private var pendingScrollY = 0f
    private var scrollInFlight = false

    // Keep the TV awake while something plays, so the screensaver does not cut in
    private var mediaPlaying = false
    private val playbackCheckRunnable = object : Runnable {
        override fun run() {
            webView.evaluateJavascript(MEDIA_PLAYING_JS) { result ->
                mediaPlaying = result == "true"
                updateKeepScreenOn()
            }
            handler.postDelayed(this, PLAYBACK_CHECK_MS)
        }
    }

    private val hideCursorRunnable = Runnable {
        cursorVisible = false
        handler.removeCallbacks(hoverHeartbeatRunnable)
        invalidate()
    }

    // Over a fullscreen video, keep "moving the mouse" while the pointer shows, so the player's controls
    // stay up as long as the pointer does instead of fading after a second.
    private val hoverHeartbeatRunnable = object : Runnable {
        override fun run() {
            if (!cursorVisible || customView == null) return
            dispatchHover()
            handler.postDelayed(this, HOVER_HEARTBEAT_MS)
        }
    }

    private val keyboardTimeoutRunnable = object : Runnable {
        override fun run() {
            if (!typingMode || isKeyboardVisible) return
            if (!keyboardRetried) {
                keyboardRetried = true
                Log.d(TAG, "Keyboard did not appear, asking again")
                showKeyboard()
                handler.postDelayed(this, KEYBOARD_WAIT_MS)
            } else {
                Log.d(TAG, "Keyboard never appeared, back to the pointer")
                exitTypingMode(blur = false)
            }
        }
    }

    private val keyboardClosedRunnable = Runnable {
        if (typingMode && !isKeyboardVisible) exitTypingMode(blur = true)
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!frameLoopRunning) return
            val dtMs = if (lastFrameNanos == 0L) 16f
            else ((frameTimeNanos - lastFrameNanos) / 1_000_000f).coerceIn(0f, MAX_FRAME_MS)
            lastFrameNanos = frameTimeNanos
            moveCursor(dtMs)
            if (anyDirectionHeld() && pointerModeEnabled && !typingMode) {
                choreographer.postFrameCallback(this)
            } else {
                stopFrameLoop()
            }
        }
    }

    init {
        setWillNotDraw(false)
        initCursorParams()
        setupWebView()
        setupKeyboardDetection()
    }

    private fun initCursorParams() {
        val width = resources.displayMetrics.widthPixels.toFloat()
        cursorRadius = width / 110f
        cursorRadiusPressed = cursorRadius * 0.75f
        cursorStrokeWidth = width / 480f
        scrollEdge = (width / 15f).toInt()
    }

    private fun setupWebView() {
        // Focus goes to the WebView whenever anything asks this layout for it.
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        isFocusable = true

        webView.apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // Desktop Chrome, with the version this WebView really is: the browser's own version headers
                // report the real one, and a mismatch looks like a bot to anti-bot checks.
                val chromeVersion = Regex("Chrome/([\\d.]+)").find(WebSettings.getDefaultUserAgent(ctx))
                    ?.groupValues?.get(1) ?: FALLBACK_CHROME_VERSION
                userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/$chromeVersion Safari/537.36"
                setSupportZoom(false)
                builtInZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
                mediaPlaybackRequiresUserGesture = false

                // Caching for faster reloads
                cacheMode = WebSettings.LOAD_DEFAULT
                databaseEnabled = true

                // JavaScript optimizations - prevent popups
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)

                // Mixed content for compatibility
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                // Disable safe browsing on TV (can cause issues)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = false
                }
            }

            // Hardware acceleration for better performance
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            isFocusable = true
            isFocusableInTouchMode = true
            // The WebView always holds focus here; without this Android TV frames the whole screen in orange
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) defaultFocusHighlightEnabled = false
        }

        // Set up WebChromeClient for fullscreen video support
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }

                customView = view
                customViewCallback = callback
                originalSystemUiVisibility = systemUiVisibility

                // The pointer stays usable over the fullscreen player: its taps go to the player's controls
                exitTypingMode(blur = false)
                releaseAllKeys()

                view?.let {
                    it.setBackgroundColor(Color.BLACK)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) it.defaultFocusHighlightEnabled = false
                    addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                    webView.visibility = View.GONE
                    it.requestFocus()
                }
                updateKeepScreenOn()
                showCursor()

                Log.d(TAG, "Entered fullscreen video mode")
            }

            override fun onHideCustomView() {
                if (customView == null) return
                // The page ended fullscreen, often in the middle of our tap on the player's own exit button.
                // Removing the view inside that touch leaves Chromium's touch tracking stuck and later taps
                // are ignored, so let the touch finish first.
                post {
                    leaveFullscreen(notifyPage = false)
                    Log.d(TAG, "Exited fullscreen video mode")
                }
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onProgressChanged?.invoke(newProgress)
            }
        }

        addView(webView)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isInEditMode) return
        // First layout: start in the middle. Later (keyboard resize): keep the pointer on screen.
        if (oldw == 0 && oldh == 0) {
            cursorPosition.set(w / 2f, h / 2f)
            showCursor()
        } else {
            cursorPosition.set(cursorPosition.x.coerceIn(0f, w - 1f), cursorPosition.y.coerceIn(0f, h - 1f))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Keys
    // ---------------------------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: AndroidKeyEvent): Boolean {
        // The error screen and typing get the keys untouched.
        if (isInEditMode || !pointerModeEnabled || isErrorScreenVisible) return super.dispatchKeyEvent(event)
        if (typingMode) return dispatchTypingKey(event)

        val down = event.action == AndroidKeyEvent.ACTION_DOWN
        val up = event.action == AndroidKeyEvent.ACTION_UP
        val first = down && event.repeatCount == 0

        when (event.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { if (first) pressDirection { holdLeft = true } else if (up) { holdLeft = false; directionReleased() }; return true }
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { if (first) pressDirection { holdRight = true } else if (up) { holdRight = false; directionReleased() }; return true }
            AndroidKeyEvent.KEYCODE_DPAD_UP -> { if (first) pressDirection { holdUp = true } else if (up) { holdUp = false; directionReleased() }; return true }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { if (first) pressDirection { holdDown = true } else if (up) { holdDown = false; directionReleased() }; return true }
            AndroidKeyEvent.KEYCODE_DPAD_UP_LEFT -> { if (first) pressDirection { holdUp = true; holdLeft = true } else if (up) { holdUp = false; holdLeft = false; directionReleased() }; return true }
            AndroidKeyEvent.KEYCODE_DPAD_UP_RIGHT -> { if (first) pressDirection { holdUp = true; holdRight = true } else if (up) { holdUp = false; holdRight = false; directionReleased() }; return true }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN_LEFT -> { if (first) pressDirection { holdDown = true; holdLeft = true } else if (up) { holdDown = false; holdLeft = false; directionReleased() }; return true }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN_RIGHT -> { if (first) pressDirection { holdDown = true; holdRight = true } else if (up) { holdDown = false; holdRight = false; directionReleased() }; return true }

            AndroidKeyEvent.KEYCODE_DPAD_CENTER,
            AndroidKeyEvent.KEYCODE_ENTER,
            AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (first) centerPressed() else if (up) centerReleased()
                return true
            }

            AndroidKeyEvent.KEYCODE_BACK -> {
                // Fullscreen video: Back leaves fullscreen, it does not leave the page
                if (customView != null) {
                    if (up && !event.isCanceled) exitFullscreen()
                    return true
                }
                // Nothing to go back to: the activity gets both halves of the key (exit dialog)
                if (!webView.canGoBack()) return super.dispatchKeyEvent(event)
                if (up && !event.isCanceled) webView.goBack()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun dispatchTypingKey(event: AndroidKeyEvent): Boolean {
        // With the keyboard up it sees the keys before we do. These only arrive while it is hidden.
        if (!isKeyboardVisible) {
            when (event.keyCode) {
                AndroidKeyEvent.KEYCODE_BACK -> {
                    if (event.action == AndroidKeyEvent.ACTION_UP) exitTypingMode(blur = true)
                    return true
                }
                AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> {
                    // Center on a field whose keyboard was closed: bring the keyboard back
                    if (event.action == AndroidKeyEvent.ACTION_UP) showKeyboard()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private inline fun pressDirection(setHeld: () -> Unit) {
        val wasHeld = anyDirectionHeld()
        val beforeLeft = holdLeft; val beforeRight = holdRight; val beforeUp = holdUp; val beforeDown = holdDown
        setHeld()
        showCursor()

        // A short press moves exactly one small step, so a text field can be hit precisely.
        val step = width * TAP_STEP
        val dx = (if (holdRight && !beforeRight) step else 0f) - (if (holdLeft && !beforeLeft) step else 0f)
        val dy = (if (holdDown && !beforeDown) step else 0f) - (if (holdUp && !beforeUp) step else 0f)
        applyMove(dx, dy)

        if (!wasHeld) holdStartMs = SystemClock.uptimeMillis()
        startFrameLoop()
    }

    private fun directionReleased() {
        if (!anyDirectionHeld()) {
            stopFrameLoop()   // stop dead on release: no coasting past the target
            scheduleCursorHide()
        }
    }

    private fun anyDirectionHeld() = holdLeft || holdRight || holdUp || holdDown

    private fun releaseAllKeys() {
        holdLeft = false; holdRight = false; holdUp = false; holdDown = false
        stopFrameLoop()
        if (dragging) dispatchMotionEvent(cursorPosition.x, cursorPosition.y, MotionEvent.ACTION_CANCEL, dragDownTime)
        centerDown = false
        dragging = false
    }

    private fun centerPressed() {
        if (!cursorVisible) {   // first press only shows the pointer
            showCursor()
            return
        }
        centerDown = true
        dragging = false
        pressPosition.set(cursorPosition)
        showCursor()
        invalidate()
    }

    private fun centerReleased() {
        if (!centerDown) return
        centerDown = false
        if (dragging) {
            dispatchMotionEvent(cursorPosition.x, cursorPosition.y, MotionEvent.ACTION_UP, dragDownTime)
            dragging = false
        } else {
            tap(pressPosition.x, pressPosition.y)
        }
        invalidate()
        scheduleCursorHide()
    }

    // ---------------------------------------------------------------------------------------------
    // Pointer motion
    // ---------------------------------------------------------------------------------------------

    private fun startFrameLoop() {
        if (frameLoopRunning) return
        frameLoopRunning = true
        lastFrameNanos = 0L
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stopFrameLoop() {
        frameLoopRunning = false
        choreographer.removeFrameCallback(frameCallback)
    }

    private fun moveCursor(dtMs: Float) {
        val dirX = (if (holdRight) 1 else 0) - (if (holdLeft) 1 else 0)
        val dirY = (if (holdDown) 1 else 0) - (if (holdUp) 1 else 0)
        if (dirX == 0 && dirY == 0) return

        // A short press already moved one step. Held on, glide: slow at first for aiming, then ease up
        // to full speed for crossing the screen.
        val glidingMs = (SystemClock.uptimeMillis() - holdStartMs) - HOLD_DELAY_MS
        if (glidingMs < 0f) return
        val t = (glidingMs / ACCEL_TIME_MS).coerceIn(0f, 1f)
        val eased = t * t * (3f - 2f * t)
        val speed = width * (SPEED_START + (SPEED_MAX - SPEED_START) * eased)   // px per second
        val diagonal = if (dirX != 0 && dirY != 0) 0.7071f else 1f
        val distance = speed * diagonal * dtMs / 1000f

        applyMove(dirX * distance, dirY * distance)
    }

    private fun applyMove(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f || width == 0 || height == 0) return
        val oldX = cursorPosition.x
        val oldY = cursorPosition.y
        cursorPosition.set((oldX + dx).coerceIn(0f, width - 1f), (oldY + dy).coerceIn(0f, height - 1f))

        // Near an edge and still heading for it: scroll what is under the pointer instead of stopping (not over
        // a fullscreen video, which has nothing to scroll).
        if (customView == null) {
            if (dy > 0 && cursorPosition.y > height - scrollEdge || dy < 0 && cursorPosition.y < scrollEdge) pendingScrollY += dy
            if (dx > 0 && cursorPosition.x > width - scrollEdge || dx < 0 && cursorPosition.x < scrollEdge) pendingScrollX += dx
            flushScroll()
        }

        if (centerDown) {
            if (!dragging) {
                dragging = true
                dragDownTime = SystemClock.uptimeMillis()
                dispatchMotionEvent(pressPosition.x, pressPosition.y, MotionEvent.ACTION_DOWN, dragDownTime)
            }
            dispatchMotionEvent(cursorPosition.x, cursorPosition.y, MotionEvent.ACTION_MOVE, dragDownTime)
        } else {
            dispatchHover()
        }
        invalidate()
    }

    /**
     * Send the accumulated edge scroll to the page, converted to CSS pixels. One call at a time: what builds up
     * while a call runs goes out with the next one, so a slow page gets fewer, larger steps instead of a backlog.
     */
    private fun flushScroll() {
        if (scrollInFlight) return
        @Suppress("DEPRECATION") val scale = webView.scale.takeIf { it > 0f } ?: resources.displayMetrics.density
        val dx = (pendingScrollX / scale).toInt()
        val dy = (pendingScrollY / scale).toInt()
        if (dx == 0 && dy == 0) return
        pendingScrollX -= dx * scale
        pendingScrollY -= dy * scale
        val x = (cursorPosition.x / scale).toInt()
        val y = (cursorPosition.y / scale).toInt()
        scrollInFlight = true
        webView.evaluateJavascript("$SCROLL_AT_POINT_JS($x, $y, $dx, $dy)") {
            scrollInFlight = false
            if (anyDirectionHeld()) flushScroll() else { pendingScrollX = 0f; pendingScrollY = 0f }
        }
    }

    private fun updateKeepScreenOn() {
        keepScreenOn = customView != null || mediaPlaying
    }

    private fun showCursor() {
        handler.removeCallbacks(hideCursorRunnable)
        if (!cursorVisible) {
            cursorVisible = true
            invalidate()
            // Waking the pointer is moving the mouse: a video player shows its controls, without a click
            // that would also pause it.
            dispatchHover()
        }
        if (customView != null) {
            handler.removeCallbacks(hoverHeartbeatRunnable)
            handler.postDelayed(hoverHeartbeatRunnable, HOVER_HEARTBEAT_MS)
        }
        if (!anyDirectionHeld() && !centerDown) scheduleCursorHide()
    }

    private fun scheduleCursorHide() {
        handler.removeCallbacks(hideCursorRunnable)
        handler.postDelayed(hideCursorRunnable, CURSOR_HIDE_IDLE_TIMEOUT)
    }

    // ---------------------------------------------------------------------------------------------
    // Clicking and typing
    // ---------------------------------------------------------------------------------------------

    private fun tap(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        // Both halves of the tap go to the same view, even if fullscreen starts or ends in between
        val target = customView ?: webView
        if (!target.hasFocus()) target.requestFocus()
        dispatchMotionEvent(x, y, MotionEvent.ACTION_DOWN, downTime, target)
        handler.postDelayed({
            dispatchMotionEvent(x, y, MotionEvent.ACTION_UP, downTime, target)
            handler.postDelayed({ checkForTextField() }, EDIT_CHECK_DELAY_MS)
        }, TAP_UP_DELAY_MS)
    }

    // Over a fullscreen video the player's own view gets the touch (its controls live there)
    private fun dispatchMotionEvent(
        x: Float, y: Float, action: Int, downTime: Long, target: View = customView ?: webView
    ) {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        }
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, 0, 0
        )
        target.dispatchTouchEvent(event)
        event.recycle()
    }

    /**
     * Tell the page where the pointer is, the way a mouse does: hover effects work, and video players show
     * their controls when the pointer moves. Taps stay touch events; only hovering is a mouse.
     */
    private fun dispatchHover() {
        if (typingMode || !pointerModeEnabled || width == 0) return
        val now = SystemClock.uptimeMillis()
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = cursorPosition.x
            y = cursorPosition.y
        }
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_HOVER_MOVE, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0
        )
        (customView ?: webView).dispatchGenericMotionEvent(event)
        event.recycle()
    }

    private fun checkForTextField() {
        if (typingMode || !pointerModeEnabled || customView != null) return
        webView.evaluateJavascript(FOCUSED_EDITABLE_JS) { result ->
            if (result == "true") {
                Log.d(TAG, "Clicked a text field")
                enterTypingMode()
            } else if (isKeyboardVisible) {
                // Chromium opened the keyboard for something we cannot see into (an iframe)
                enterTypingMode()
            }
        }
    }

    private fun enterTypingMode() {
        if (typingMode) return
        typingMode = true
        keyboardRetried = false
        releaseAllKeys()
        handler.removeCallbacks(hideCursorRunnable)
        handler.removeCallbacks(keyboardClosedRunnable)
        invalidate()   // pointer hides while typing

        if (!webView.hasFocus()) webView.requestFocus()
        if (isKeyboardVisible) {
            scrollFocusedFieldIntoView()
        } else {
            showKeyboard()
            handler.removeCallbacks(keyboardTimeoutRunnable)
            handler.postDelayed(keyboardTimeoutRunnable, KEYBOARD_WAIT_MS)
        }
    }

    private fun exitTypingMode(blur: Boolean) {
        if (!typingMode) return
        typingMode = false
        handler.removeCallbacks(keyboardTimeoutRunnable)
        handler.removeCallbacks(keyboardClosedRunnable)
        if (isKeyboardVisible) imm.hideSoftInputFromWindow(webView.windowToken, 0)
        // Blurring keeps what was typed; it stops a later Center press from typing into the field
        if (blur) webView.evaluateJavascript(BLUR_FOCUSED_EDITABLE_JS, null)
        showCursor()
        invalidate()   // the pointer comes back right away, where it was
    }

    private fun showKeyboard() {
        if (!webView.hasFocus()) webView.requestFocus()
        // Post it: the WebView's input connection must exist before the keyboard is asked for.
        webView.post {
            val shown = imm.showSoftInput(webView, 0)
            Log.d(TAG, "showSoftInput: $shown")
        }
    }

    private fun scrollFocusedFieldIntoView() {
        // After the window has shrunk for the keyboard
        handler.postDelayed({ if (typingMode) webView.evaluateJavascript(SCROLL_FOCUSED_INTO_VIEW_JS, null) }, 120)
    }

    // ---------------------------------------------------------------------------------------------
    // Keyboard detection
    // ---------------------------------------------------------------------------------------------

    private fun setupKeyboardDetection() {
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime()) &&
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0
            onKeyboardVisibilityChanged(visible)
            insets
        }

        // Fallback for when the window is resized for the keyboard but no IME insets arrive
        globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val r = Rect()
            getWindowVisibleDisplayFrame(r)
            val screenHeight = rootView.height
            if (screenHeight > 0) onKeyboardVisibilityChanged(screenHeight - r.bottom > screenHeight * 0.10)
        }
        viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
    }

    private fun onKeyboardVisibilityChanged(visible: Boolean) {
        if (visible == isKeyboardVisible) return
        isKeyboardVisible = visible
        Log.d(TAG, "Keyboard ${if (visible) "shown" else "hidden"}")
        if (visible) {
            handler.removeCallbacks(keyboardClosedRunnable)
            handler.removeCallbacks(keyboardTimeoutRunnable)
            if (typingMode) scrollFocusedFieldIntoView() else if (pointerModeEnabled && customView == null) enterTypingMode()
        } else if (typingMode) {
            // Wait a moment: moving to the next field hides the keyboard and shows it again.
            handler.removeCallbacks(keyboardClosedRunnable)
            handler.postDelayed(keyboardClosedRunnable, KEYBOARD_CLOSE_DEBOUNCE_MS)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------------------------

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (isInEditMode || !pointerModeEnabled || typingMode || !cursorVisible) return

        val cx = cursorPosition.x
        val cy = cursorPosition.y
        val radius = if (centerDown) cursorRadiusPressed else cursorRadius

        // Soft fill, a dark ring and a light outer ring: visible on white and on black pages
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(110, 255, 255, 255)
        canvas.drawCircle(cx, cy, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = cursorStrokeWidth
        paint.color = Color.argb(220, 40, 40, 40)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.color = Color.argb(200, 255, 255, 255)
        canvas.drawCircle(cx, cy, radius + cursorStrokeWidth, paint)
    }

    // ---------------------------------------------------------------------------------------------
    // Page hooks
    // ---------------------------------------------------------------------------------------------

    private fun enableScrollableFocusNavigation() {
        webView.evaluateJavascript(
            """
            (function() {
                try {
                    document.body.style.cursor = 'none';
                    const els = document.querySelectorAll('a, button, [tabindex]:not([tabindex="-1"])');
                    els.forEach(el => {
                        if (!['input', 'textarea'].includes(el.tagName.toLowerCase())) {
                            el.setAttribute('data-original-tabindex', el.getAttribute('tabindex') || '0');
                            el.setAttribute('tabindex', '-1');
                        }
                    });
                    // Chromium gives a freshly focused WebView keyboard-style focus on the first link, which
                    // shows "Skip to main content" links and focus rings. Nobody tabbed here: drop it.
                    const a = document.activeElement;
                    const tag = a ? a.tagName.toLowerCase() : '';
                    if (a && a !== document.body && tag !== 'input' && tag !== 'textarea' && tag !== 'select' &&
                            !a.isContentEditable && a.matches(':focus-visible')) {
                        a.blur();
                    }
                } catch (e) {
                }
            })()
            """.trimIndent(), null
        )
    }

    /** [notifyPage]: we are ending fullscreen (Back), so the page has to be told; when the page ended it, not. */
    private fun leaveFullscreen(notifyPage: Boolean) {
        val view = customView ?: return
        val callback = customViewCallback
        // Clear first: the callback below can call onHideCustomView straight back
        customView = null
        customViewCallback = null
        releaseAllKeys()
        removeView(view)
        webView.visibility = View.VISIBLE
        systemUiVisibility = originalSystemUiVisibility
        updateKeepScreenOn()
        webView.requestFocus()
        showCursor()
        if (notifyPage) callback?.onCustomViewHidden()
    }

    // ---------------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------------

    fun canGoBack(): Boolean = if (typingMode) false else webView.canGoBack()

    fun goBack() {
        if (typingMode) {
            exitTypingMode(blur = true)
        } else if (webView.canGoBack()) {
            webView.goBack()
        }
    }

    fun loadUrl(url: String) { webView.loadUrl(url) }
    fun getUrl(): String? = webView.url
    fun setWebViewClient(client: WebViewClient) { webView.webViewClient = client }

    // Note: setWebChromeClient is handled internally for fullscreen support
    // Use this only if you need to extend the internal client's behavior
    fun setWebChromeClient(client: WebChromeClient) {
        Log.w(TAG, "Custom WebChromeClient set - fullscreen video handling may be affected")
        webView.webChromeClient = client
    }

    fun updateLastActivityTime() {
        if (!typingMode) showCursor()
    }

    fun isPointerModeEnabled(): Boolean = pointerModeEnabled

    fun restoreCursorFocus() {
        handler.post {
            // A new page: leave typing mode from the old one behind
            if (typingMode && !isKeyboardVisible) exitTypingMode(blur = false)
            if (pointerModeEnabled && !typingMode) {
                if (!webView.hasFocus()) webView.requestFocus()
                disableFocusNavigation()
                showCursor()
            }
        }
    }

    /** The app left the screen: stop playback, scripts and timers so nothing runs in the background. */
    fun onPause() {
        releaseAllKeys()
        handler.removeCallbacksAndMessages(null)
        webView.onPause()
        webView.pauseTimers()
        mediaPlaying = false
        updateKeepScreenOn()
    }

    fun onResume() {
        webView.resumeTimers()
        webView.onResume()
        handler.removeCallbacks(playbackCheckRunnable)
        handler.postDelayed(playbackCheckRunnable, PLAYBACK_CHECK_MS)
        if (!typingMode) showCursor()
    }

    fun disableFocusNavigation() {
        enableScrollableFocusNavigation()
    }

    fun onDestroy() {
        globalLayoutListener?.let { viewTreeObserver.removeOnGlobalLayoutListener(it) }
        ViewCompat.setOnApplyWindowInsetsListener(this, null)
        stopFrameLoop()
        handler.removeCallbacksAndMessages(null)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    /**
     * Clear WebView cache when device is low on memory.
     * Call this from Activity's onLowMemory() or onTrimMemory().
     */
    fun clearCacheOnLowMemory() {
        webView.clearCache(true)
        Log.d(TAG, "WebView cache cleared due to low memory")
    }

    /**
     * Check if currently in fullscreen video mode.
     */
    fun isInFullscreenMode(): Boolean = customView != null

    /**
     * Exit fullscreen video mode if active.
     * Returns true if was in fullscreen and exited, false otherwise.
     */
    fun exitFullscreen(): Boolean {
        if (customView == null) return false
        leaveFullscreen(notifyPage = true)
        Log.d(TAG, "Force exited fullscreen video mode")
        return true
    }
}
