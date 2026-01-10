package com.example.flixtortv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent as AndroidKeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.FrameLayout
import androidx.core.content.ContextCompat

class CursorWebView @SuppressLint("SetJavaScriptEnabled")
constructor(private val ctx: Context) : FrameLayout(ctx) {

    // Cursor & state
    private var cursorX = 0f
    private var cursorY = 0f
    private val baseCursorStep = 20f
    private var pointerModeEnabled = true
    
    // Robust keyboard state tracking
    private var isKeyboardVisible = false
    
    private var lastKeyTime = 0L
    private var lastCenterPressTime = 0L
    private var keyHoldStartTime = 0L
    private var lastActivityTime = System.currentTimeMillis()

    private val pressedKeys = mutableSetOf<Int>()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var cursorView: View
    private val webView: WebView = WebView(ctx)

    var isErrorScreenVisible = false
    private val cursorHideTimeout = 3000L

    // Global layout listener for keyboard detection
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    // Member variable for the focus and idle handler Runnable
    private lateinit var focusAndIdleHandlerRunnable: Runnable

    init {
        setupWebView()
        setupCursor()
        setupJsInterfaces()
        setupKeyboardDetection()
        setupFocusAndIdleHandler()
    }

    private fun setupWebView() {
        webView.apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119 Safari/537.36"
                setSupportZoom(false)
                builtInZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
                mediaPlaybackRequiresUserGesture = false
            }
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        addView(webView)
    }

    private fun setupCursor() {
        cursorView = View(ctx).apply {
            background = ContextCompat.getDrawable(ctx, R.drawable.tvbro_pointer)
            alpha = 0.7f
            val size = (ctx.resources.displayMetrics.widthPixels / 55).coerceAtLeast(30)
            layoutParams = LayoutParams(size, size).apply {
                leftMargin = -size / 2
                topMargin = -size / 2
            }
            visibility = if (pointerModeEnabled) View.VISIBLE else View.GONE
        }
        addView(cursorView)

        post {
            cursorX = width / 2f
            cursorY = height / 2f
            cursorView.x = cursorX
            cursorView.y = cursorY
        }
    }

    private fun setupKeyboardDetection() {
        globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val r = Rect()
            this.getWindowVisibleDisplayFrame(r)
            val screenHeight = this.rootView.height
            val keypadHeight = screenHeight - r.bottom

            // 0.15 ratio is enough to suspect keyboard usage
            if (keypadHeight > screenHeight * 0.15) {
                if (!isKeyboardVisible) {
                    Log.d("FlixtorTV", "Keyboard OPEN detected via LayoutListener")
                    onKeyboardVisibilityChanged(true)
                }
            } else {
                if (isKeyboardVisible) {
                    Log.d("FlixtorTV", "Keyboard CLOSED detected via LayoutListener")
                    onKeyboardVisibilityChanged(false)
                }
            }
        }
        this.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
    }

    private fun onKeyboardVisibilityChanged(visible: Boolean) {
        isKeyboardVisible = visible
        if (visible) {
            // Keyboard is open: Disable pointer, let WebView handle everything
            pointerModeEnabled = false
            cursorView.visibility = View.GONE
            handler.removeCallbacks(cursorUpdateRunnable)
            // Ensure WebView has focus so typing works
            webView.requestFocus()
        } else {
            // Keyboard closed: Restore pointer mode
            pointerModeEnabled = true
            this.requestFocus() // Steal focus back for D-pad
            cursorView.visibility = View.VISIBLE
            updateLastActivityTime()
        }
    }

    private fun setupJsInterfaces() {
        // No longer strictly needed for state, but useful for debugging or quick reactions
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onTextFieldBlurred() {
                Log.d("FlixtorTV", "JS: TextField blurred")
            }

            @JavascriptInterface
            fun onTextFieldFocused() {
                Log.d("FlixtorTV", "JS: TextField focused")
                // We rely on GlobalLayoutListener for state, but we can help trigger the keyboard here
                 handler.post {
                     showKeyboard()
                 }
            }
        }, "Android")
        
        // Inject JS to notify us (Double tap safety)
        webView.evaluateJavascript(
            """
            (function() {
                document.addEventListener('focusin', function(e) {
                    if (['input','textarea'].includes(e.target.tagName.toLowerCase())) {
                        if (typeof Android !== 'undefined' && Android.onTextFieldFocused) {
                            Android.onTextFieldFocused();
                        }
                    }
                });
            })()
            """.trimIndent(), null
        )
    }

    private fun setupFocusAndIdleHandler() {
        this.isFocusable = true
        this.isFocusableInTouchMode = true
        if (pointerModeEnabled) {
            this.requestFocus()
        }

        enableScrollableFocusNavigation()

        focusAndIdleHandlerRunnable = object : Runnable {
            override fun run() {
                // If keyboard is visible, DO NOTHING. Let the user type.
                if (isKeyboardVisible) {
                     handler.postDelayed(this, 1000)
                     return
                }

                if (pointerModeEnabled && !this@CursorWebView.hasFocus() && !isErrorScreenVisible && !webView.hasFocus()) {
                    // Only reclaim focus if we are supposed to be in pointer mode and keyboard is CLOSED
                    Log.d("FlixtorTV", "IdleHandler: Reclaiming focus for CWV")
                    val reclaimed = this@CursorWebView.requestFocus()
                    if (reclaimed && cursorView.visibility != View.VISIBLE) {
                        cursorView.visibility = View.VISIBLE
                    }
                }

                val idleTime = System.currentTimeMillis() - lastActivityTime
                if (idleTime >= cursorHideTimeout &&
                    cursorView.visibility == View.VISIBLE &&
                    pointerModeEnabled &&
                    !isKeyboardVisible &&
                    this@CursorWebView.hasFocus()
                ) {
                    cursorView.visibility = View.GONE
                    Log.d("FlixtorTV", "Cursor hidden due to inactivity")
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.postDelayed(focusAndIdleHandlerRunnable, 500)

        webView.setOnFocusChangeListener { _, webViewHasFocus ->
            Log.d("FlixtorTV", "WebView focus changed. Has Focus: $webViewHasFocus, Keyboard: $isKeyboardVisible")
            if (webViewHasFocus && pointerModeEnabled && !isKeyboardVisible) {
                 // If the WebView got focus but keyboard is NOT visible (and we want pointer mode),
                 // it might be an accidental focus steal.
                 // HOWEVER, when clicking an input, the WebView gets focus BEFORE the keyboard opens.
                 // So we must be careful not to steal it back immediately.
                 // We will verify this via simulateClick's return value or wait for LayoutListener.
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: AndroidKeyEvent?): Boolean {
        if (isErrorScreenVisible) return false

        // If keyboard is visible, pass everything to WebView (except maybe Back)
        if (isKeyboardVisible) {
             if (keyCode == AndroidKeyEvent.KEYCODE_BACK) {
                 // Let default behavior close keyboard
                 return super.onKeyDown(keyCode, event)
             }
             // For all other keys while typing, let the WebView/System handle it
             return super.onKeyDown(keyCode, event)
        }

        val now = System.currentTimeMillis()
        if (now - lastKeyTime < 100 && !(keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER && (now - lastCenterPressTime > 300))) {
            return true
        }
        lastKeyTime = now
        lastActivityTime = now

        // Show cursor moves
        if (pointerModeEnabled && cursorView.visibility != View.VISIBLE &&
            (keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP || keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN ||
                    keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT || keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT ||
                    keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER)) {
            if (this.hasFocus()) {
                cursorView.visibility = View.VISIBLE
            } else {
                this.requestFocus()
            }
        } else if (!pointerModeEnabled && keyCode != AndroidKeyEvent.KEYCODE_DPAD_CENTER && keyCode != AndroidKeyEvent.KEYCODE_BACK) {
             // Not in pointer mode (and keyboard likely closed?), allow standard dpad nav
             return super.onKeyDown(keyCode, event)
        }

        when (keyCode) {
            AndroidKeyEvent.KEYCODE_BACK -> {
                if (pointerModeEnabled && webView.canGoBack()) {
                    webView.goBack()
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }
            AndroidKeyEvent.KEYCODE_DPAD_CENTER -> {
                if (pointerModeEnabled) {
                    if (!this.hasFocus()) {
                        this.requestFocus()
                        return true
                    }
                    val doublePress = now - lastCenterPressTime < 300
                    lastCenterPressTime = now
                    if (doublePress) {
                        Log.d("FlixtorTV", "Double press: Disabling pointer mode")
                        pointerModeEnabled = false
                        cursorView.visibility = View.GONE
                        webView.requestFocus()
                        return true
                    }
                    Log.d("FlixtorTV", "Single press: Simulating click")
                    simulateClick(cursorX.toInt(), cursorY.toInt())
                    triggerRippleEffect()
                    return true
                } else {
                    Log.d("FlixtorTV", "Enabling pointer mode")
                    pointerModeEnabled = true
                    this.requestFocus()
                    cursorView.visibility = View.VISIBLE
                    cursorX = width / 2f
                    cursorY = height / 2f
                    cursorView.x = cursorX
                    cursorView.y = cursorY
                    enableScrollableFocusNavigation()
                    return true
                }
            }
            AndroidKeyEvent.KEYCODE_DPAD_UP,
            AndroidKeyEvent.KEYCODE_DPAD_DOWN,
            AndroidKeyEvent.KEYCODE_DPAD_LEFT,
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (pointerModeEnabled) {
                    if (!this.hasFocus()) {
                        this.requestFocus()
                        return true
                    }
                    if (pressedKeys.add(keyCode)) {
                        if (pressedKeys.size == 1) {
                            keyHoldStartTime = System.currentTimeMillis()
                            handler.post(cursorUpdateRunnable)
                        }
                    }
                    return true
                }
                return handlePageScrolling(keyCode) || super.onKeyDown(keyCode, event)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handlePageScrolling(keyCode: Int): Boolean {
         // Logic for scrolling when NOT in pointer mode (standard D-pad nav)
         // ... implementation same as before ...
          when (keyCode) {
                AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                    webView.scrollBy(0, -100)
                    return true
                }
                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                    webView.scrollBy(0, 100)
                    return true
                }
                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                    webView.scrollBy(-100, 0)
                    return true
                }
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                    webView.scrollBy(100, 0)
                    return true
                }
            }
        return false
    }

    override fun onKeyUp(keyCode: Int, event: AndroidKeyEvent?): Boolean {
        if (isKeyboardVisible) {
            return super.onKeyUp(keyCode, event)
        }
        if (pressedKeys.remove(keyCode) && pressedKeys.isEmpty()) {
            handler.removeCallbacks(cursorUpdateRunnable)
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun simulateClick(x: Int, y: Int) {
        Log.d("FlixtorTV", "Simulating click at ($x, $y)")
        val downTime = System.currentTimeMillis()
        val eventDown = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
        val eventUp = MotionEvent.obtain(downTime, downTime + 100, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)

        webView.isFocusableInTouchMode = true
        // Note: We do NOT request focus from touch here aggressively,
        // we let the dispatchTouchEvent enforce the click.
        
        webView.dispatchTouchEvent(eventDown)
        webView.dispatchTouchEvent(eventUp)
        
        eventDown.recycle()
        eventUp.recycle()

        // Check if we hit an input
        webView.evaluateJavascript(
            """
            (function() {
                const el = document.elementFromPoint($x, $y);
                if (!el) return 'none';
                const tag = el.tagName.toLowerCase();
                if (tag === 'input' || tag === 'textarea') {
                    el.focus(); // Force focus in JS
                    return 'text_field_clicked';
                }
                return 'clicked_tag_' + tag;
            })()
            """.trimIndent()
        ) { result ->
            if (result != null && result.contains("text_field_clicked")) {
                Log.d("FlixtorTV", "Hit text field, forcing keyboard")
                // Assume keyboard WILL open.
                // The global layout listener will confirm this mechanically,
                // but we can preemptively pause the cursor logic for a split second to avoid fighting.
                handler.postDelayed({ showKeyboard() }, 100)
            }
        }
        lastActivityTime = System.currentTimeMillis()
    }

    private fun triggerRippleEffect() {
        // ... same as before ...
         val ripple = ScaleAnimation(1f, 1.5f, 1f, 1.5f,
            Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 200
            repeatMode = Animation.REVERSE
            repeatCount = 1
        }
        cursorView.startAnimation(ripple)
    }

    private val cursorUpdateRunnable = object : Runnable {
        override fun run() {
            if (!pointerModeEnabled || isKeyboardVisible || !this@CursorWebView.hasFocus()) {
                handler.removeCallbacks(this)
                return
            }
            val holdTime = System.currentTimeMillis() - keyHoldStartTime
            val speedFactor = when {
                holdTime < 200 -> 0.5f
                holdTime < 500 -> 1.0f
                holdTime < 1500 -> 1.5f
                else -> 2.5f
            }
            val step = (baseCursorStep * speedFactor).coerceAtMost(60f)
            var dx = 0f
            var dy = 0f
            if (AndroidKeyEvent.KEYCODE_DPAD_LEFT in pressedKeys) dx -= step
            if (AndroidKeyEvent.KEYCODE_DPAD_RIGHT in pressedKeys) dx += step
            if (AndroidKeyEvent.KEYCODE_DPAD_UP in pressedKeys) dy -= step
            if (AndroidKeyEvent.KEYCODE_DPAD_DOWN in pressedKeys) dy += step

            val halfCursorWidth = cursorView.width / 2f
            val halfCursorHeight = cursorView.height / 2f
             // Calculate new position
            val newX = cursorX + dx
            val newY = cursorY + dy
            
            // Bounds
            val minX = halfCursorWidth
            val maxX = (width - halfCursorWidth).coerceAtLeast(halfCursorWidth)
            val minY = halfCursorHeight
            val maxY = (height - halfCursorHeight).coerceAtLeast(halfCursorHeight)
            
            // Apply bounds to cursor
            cursorX = newX.coerceIn(minX, maxX)
            cursorY = newY.coerceIn(minY, maxY)
            
            // Edge Scrolling
            if (dx < 0 && newX <= minX) {
                webView.scrollBy((-step).toInt(), 0)
            } else if (dx > 0 && newX >= maxX) {
                webView.scrollBy(step.toInt(), 0)
            }
            
            if (dy < 0 && newY <= minY) {
                webView.scrollBy(0, (-step).toInt())
            } else if (dy > 0 && newY >= maxY) {
                webView.scrollBy(0, step.toInt())
            }

            cursorView.x = cursorX
            cursorView.y = cursorY
            lastActivityTime = System.currentTimeMillis()
            handler.postDelayed(this, 16)
        }
    }

    private fun showKeyboard() {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
    }

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
                } catch (e) {
                }
            })()
            """.trimIndent(), null
        )
    }

    // Public API methods
    fun canGoBack(): Boolean = if (isKeyboardVisible) false else webView.canGoBack()

    fun goBack() {
        if (isKeyboardVisible) {
             // Shouldn't really happen if Back closes keyboard by default, but just in case
             val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
             imm.hideSoftInputFromWindow(this.windowToken, 0)
        } else if (webView.canGoBack()) {
            webView.goBack()
        }
    }

    fun loadUrl(url: String) { webView.loadUrl(url) }
    fun getUrl(): String? = webView.url
    fun setWebViewClient(client: WebViewClient) { webView.webViewClient = client }
    fun setWebChromeClient(client: WebChromeClient) { webView.webChromeClient = client }
    fun updateLastActivityTime() { lastActivityTime = System.currentTimeMillis() }
    fun isPointerModeEnabled(): Boolean = pointerModeEnabled

    fun restoreCursorFocus() {
        handler.post {
            if (isPointerModeEnabled() && !isKeyboardVisible) {
                requestFocus()
                disableFocusNavigation()
                updateLastActivityTime()
            }
        }
    }

    fun onPause() {
        webView.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    fun onResume() {
        webView.onResume()
        handler.removeCallbacks(focusAndIdleHandlerRunnable)
        handler.postDelayed(focusAndIdleHandlerRunnable, 500)
    }

    fun disableFocusNavigation() {
        enableScrollableFocusNavigation()
    }

    fun onDestroy() {
        if (globalLayoutListener != null) {
            this.viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
        }
        handler.removeCallbacksAndMessages(null)
        if (webView.parent is ViewGroup) {
            (webView.parent as ViewGroup).removeView(webView)
        }
        webView.destroy()
    }
}