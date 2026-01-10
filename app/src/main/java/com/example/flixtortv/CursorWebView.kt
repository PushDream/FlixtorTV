package com.example.flixtortv

import android.annotation.SuppressLint
import android.content.Context
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
    private var isTextFieldClicked = false
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

    // Member variable for the focus and idle handler Runnable
    private lateinit var focusAndIdleHandlerRunnable: Runnable

    init {
        setupWebView()
        setupCursor()
        setupJsInterfaces()
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

    private fun setupJsInterfaces() {
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onTextFieldBlurred() {
                handler.post {
                    Log.d("FlixtorTV", "JS onTextFieldBlurred. Was isTextFieldClicked: $isTextFieldClicked")
                    if (isTextFieldClicked) {
                        // Hide keyboard immediately, then wait for it to actually close
                        hideKeyboard()
                        // Shorter delay - keyboard usually closes within 150ms
                        handler.postDelayed({
                            if (isTextFieldClicked) {
                                resetFocusAfterTextInput()
                            }
                        }, 150)
                    }
                }
            }

            @JavascriptInterface
            fun onTextFieldFocused() {
                handler.post {
                    Log.d("FlixtorTV", "JS onTextFieldFocused")
                    isTextFieldClicked = true
                    cursorView.visibility = View.GONE
                    handler.removeCallbacks(cursorUpdateRunnable)
                    handler.removeCallbacks(focusAndIdleHandlerRunnable)
                }
            }
        }, "Android")

        webView.evaluateJavascript(
            """
            (function() {
                // Handle text field focus
                document.addEventListener('focusin', function(e) {
                    if (['input','textarea'].includes(e.target.tagName.toLowerCase())) {
                        if (typeof Android !== 'undefined' && Android.onTextFieldFocused) {
                            Android.onTextFieldFocused();
                        }
                    }
                });
                
                // Handle text field blur
                document.addEventListener('focusout', function(e) {
                    if (['input','textarea'].includes(e.target.tagName.toLowerCase())) {
                        if (typeof Android !== 'undefined' && Android.onTextFieldBlurred) {
                            Android.onTextFieldBlurred();
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

        // Enable modified focus navigation that preserves scrolling
        enableScrollableFocusNavigation()

        focusAndIdleHandlerRunnable = object : Runnable {
            override fun run() {
                if (pointerModeEnabled && !this@CursorWebView.hasFocus() && !isErrorScreenVisible && !isTextFieldClicked && !webView.hasFocus()) {
                    Log.d("FlixtorTV", "IdleHandler: Attempting to reclaim focus for CWV")
                    val reclaimed = this@CursorWebView.requestFocus()
                    if (reclaimed && cursorView.visibility != View.VISIBLE) {
                        cursorView.visibility = View.VISIBLE
                    }
                }

                val idleTime = System.currentTimeMillis() - lastActivityTime
                if (idleTime >= cursorHideTimeout &&
                    cursorView.visibility == View.VISIBLE &&
                    pointerModeEnabled &&
                    !isTextFieldClicked &&
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
            Log.d("FlixtorTV", "WebView focus changed. Has Focus: $webViewHasFocus")
            if (!webViewHasFocus && isTextFieldClicked) {
                Log.d("FlixtorTV", "WebView lost focus while editing text")
                // Let the JS blur handler take care of this
            } else if (webViewHasFocus && pointerModeEnabled && !isTextFieldClicked) {
                Log.d("FlixtorTV", "WebView gained focus inappropriately, redirecting to CWV")
                handler.postDelayed({
                    this@CursorWebView.requestFocus()
                }, 50)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: AndroidKeyEvent?): Boolean {
        if (isErrorScreenVisible) return false

        if (isTextFieldClicked) {
            when (keyCode) {
                AndroidKeyEvent.KEYCODE_BACK -> {
                    Log.d("FlixtorTV", "BACK pressed while text field active")
                    webView.evaluateJavascript(
                        """
                        (function() {
                            const el = document.activeElement;
                            if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
                                el.blur();
                            }
                        })()
                        """.trimIndent(), null
                    )
                    return true
                }
                AndroidKeyEvent.KEYCODE_DPAD_UP,
                AndroidKeyEvent.KEYCODE_DPAD_DOWN,
                AndroidKeyEvent.KEYCODE_DPAD_LEFT,
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
                AndroidKeyEvent.KEYCODE_DPAD_CENTER -> {
                    return super.onKeyDown(keyCode, event)
                }
                else -> return super.onKeyDown(keyCode, event)
            }
        }

        val now = System.currentTimeMillis()
        if (now - lastKeyTime < 100 && !(keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER && (now - lastCenterPressTime > 300))) {
            return true
        }
        lastKeyTime = now
        lastActivityTime = now

        // Show cursor on movement if in pointer mode
        if (pointerModeEnabled && cursorView.visibility != View.VISIBLE &&
            (keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP || keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN ||
                    keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT || keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT ||
                    keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER)) {
            if (this.hasFocus()) {
                cursorView.visibility = View.VISIBLE
                Log.d("FlixtorTV", "Cursor restored via key press")
            } else {
                this.requestFocus()
            }
        } else if (!pointerModeEnabled && keyCode != AndroidKeyEvent.KEYCODE_DPAD_CENTER && keyCode != AndroidKeyEvent.KEYCODE_BACK) {
            // Allow page scrolling when not in pointer mode
            return handlePageScrolling(keyCode) || super.onKeyDown(keyCode, event)
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
        if (!pointerModeEnabled) {
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
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, event: AndroidKeyEvent?): Boolean {
        if (isTextFieldClicked) {
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
        webView.requestFocusFromTouch()

        val dispatchedDown = webView.dispatchTouchEvent(eventDown)
        val dispatchedUp = webView.dispatchTouchEvent(eventUp)
        Log.d("FlixtorTV", "Touch dispatched: down=$dispatchedDown, up=$dispatchedUp")

        eventDown.recycle()
        eventUp.recycle()

        // Check synchronously what was clicked, then handle focus asynchronously
        webView.evaluateJavascript(
            """
            (function() {
                const el = document.elementFromPoint($x, $y);
                if (!el) return 'none';
                const tag = el.tagName.toLowerCase();
                if (tag === 'input' || tag === 'textarea') {
                    // Focus immediately - don't wait
                    el.focus();
                    return 'text_field_clicked';
                }
                return 'clicked_tag_' + tag;
            })()
            """.trimIndent()
        ) { result ->
            Log.d("FlixtorTV", "JS Click Result: $result")
            if (result != null && result.contains("text_field_clicked")) {
                // JS focus event will trigger onTextFieldFocused callback immediately
                // Show keyboard with minimal delay to ensure focus is processed
                handler.postDelayed({
                    if (isTextFieldClicked) {
                        showKeyboard()
                    }
                }, 100)
            } else {
                // Non-text click - ensure cursor stays visible if in pointer mode
                if (pointerModeEnabled) {
                    val cwvFocus = this@CursorWebView.requestFocus()
                    if (cwvFocus && cursorView.visibility != View.VISIBLE) {
                        cursorView.visibility = View.VISIBLE
                    }
                }
            }
        }
        lastActivityTime = System.currentTimeMillis()
    }

    private fun triggerRippleEffect() {
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
            if (!pointerModeEnabled || isTextFieldClicked || !this@CursorWebView.hasFocus()) {
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
            cursorX = (cursorX + dx).coerceIn(halfCursorWidth, (width - halfCursorWidth).coerceAtLeast(halfCursorWidth))
            cursorY = (cursorY + dy).coerceIn(halfCursorHeight, (height - halfCursorHeight).coerceAtLeast(halfCursorHeight))
            cursorView.x = cursorX
            cursorView.y = cursorY
            lastActivityTime = System.currentTimeMillis()
            handler.postDelayed(this, 16)
        }
    }

    private fun resetFocusAfterTextInput() {
        Log.d("FlixtorTV", "resetFocusAfterTextInput START")
        webView.clearFocus()
        isTextFieldClicked = false

        // Keyboard is already being hidden by the blur handler
        // Restore focus immediately
        if (pointerModeEnabled) {
            this.isFocusable = true
            this.isFocusableInTouchMode = true
            this.requestFocus()
            cursorView.visibility = View.VISIBLE
            Log.d("FlixtorTV", "Focus restored to CWV, hasFocus: ${this.hasFocus()}")
        } else {
            cursorView.visibility = View.GONE
            webView.requestFocus()
        }

        lastActivityTime = System.currentTimeMillis()
        // Restart the focus/idle handler
        handler.removeCallbacks(focusAndIdleHandlerRunnable)
        handler.postDelayed(focusAndIdleHandlerRunnable, 500)
    }

    private fun resetFocus() {
        resetFocusAfterTextInput()
    }

    private fun showKeyboard() {
        Log.d("FlixtorTV", "Attempting to show keyboard")
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(webView, InputMethodManager.SHOW_FORCED)
    }

    private fun hideKeyboard() {
        Log.d("FlixtorTV", "Hiding keyboard")
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(this.windowToken, 0)
    }

    private fun enableScrollableFocusNavigation() {
        Log.d("FlixtorTV", "Enabling scrollable focus navigation")
        webView.evaluateJavascript(
            """
            (function() {
                try {
                    // Hide cursor but preserve scrolling functionality
                    document.body.style.cursor = 'none';
                    
                    // Only disable tabindex for non-input elements to prevent focus conflicts
                    // but preserve scrolling by not interfering with focus entirely
                    const els = document.querySelectorAll('a, button, [tabindex]:not([tabindex="-1"])');
                    els.forEach(el => {
                        if (!['input', 'textarea'].includes(el.tagName.toLowerCase())) {
                            el.setAttribute('data-original-tabindex', el.getAttribute('tabindex') || '0');
                            el.setAttribute('tabindex', '-1');
                        }
                    });
                    
                    // Ensure input/textarea elements remain focusable
                    const inputEls = document.querySelectorAll('input, textarea');
                    inputEls.forEach(el => {
                        if (el.getAttribute('tabindex') === '-1') {
                            el.removeAttribute('tabindex');
                        }
                    });
                } catch (e) {
                    console.error('FlixtorTV: Error in enableScrollableFocusNavigation:', e);
                }
            })()
            """.trimIndent(), null
        )
    }

    // Public API methods
    fun canGoBack(): Boolean = if (isTextFieldClicked) false else webView.canGoBack()

    fun goBack() {
        if (isTextFieldClicked) {
            Log.d("FlixtorTV", "goBack called while text field active")
            resetFocus()
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
    fun getCursorVisibility(): Int = cursorView.visibility

    // Public method for MainActivity to restore focus after page loads
    fun restoreCursorFocus() {
        handler.post {
            if (isPointerModeEnabled()) {
                requestFocus()
                Log.d("FlixtorTV", "Restoring cursor visibility on page finish")
                disableFocusNavigation()
                updateLastActivityTime()
            }
        }
    }

    fun onPause() {
        Log.d("FlixtorTV", "onPause called")
        webView.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    fun onResume() {
        Log.d("FlixtorTV", "onResume called")
        webView.onResume()
        handler.removeCallbacks(focusAndIdleHandlerRunnable)
        handler.postDelayed(focusAndIdleHandlerRunnable, 500)

        if (pointerModeEnabled && !isTextFieldClicked) {
            if (this.hasFocus()) {
                lastActivityTime = System.currentTimeMillis()
                if (cursorView.visibility != View.VISIBLE) {
                    cursorView.visibility = View.VISIBLE
                }
            } else {
                this.requestFocus()
            }
        } else if (!pointerModeEnabled && !isTextFieldClicked && webView.hasFocus()) {
            cursorView.visibility = View.GONE
        }
    }

    // Backward compatibility method - can be called from MainActivity
    fun disableFocusNavigation() {
        enableScrollableFocusNavigation()
    }

    fun onDestroy() {
        Log.d("FlixtorTV", "onDestroy called")
        handler.removeCallbacksAndMessages(null)
        if (webView.parent is ViewGroup) {
            (webView.parent as ViewGroup).removeView(webView)
        }
        webView.destroy()
    }
}