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

    // State machine for proper lifecycle management
    private enum class InputState {
        IDLE,                    // No active input, cursor hidden
        CURSOR_ACTIVE,           // Cursor visible, navigating
        TEXT_INPUT_STARTING,     // Text field clicked, waiting for keyboard
        TEXT_INPUT_ACTIVE,       // Keyboard open, text editing
        TEXT_INPUT_ENDING        // Keyboard closing, transitioning back
    }

    private var currentState = InputState.IDLE
    private val stateDebounceMs = 150L
    private var lastStateChangeTime = 0L

    // Cursor & state
    private var cursorX = 0f
    private var cursorY = 0f
    private val baseCursorStep = 20f
    private var pointerModeEnabled = true
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

    // Keyboard state tracking
    private var isKeyboardVisible = false
    private val imm: InputMethodManager by lazy {
        ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }

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
                    Log.d("FlixtorTV", "JS onTextFieldBlurred. State: $currentState")
                    if (currentState == InputState.TEXT_INPUT_ACTIVE) {
                        transitionToState(InputState.TEXT_INPUT_ENDING)
                        // Wait for keyboard to actually close
                        waitForKeyboardClose()
                    }
                }
            }

            @JavascriptInterface
            fun onTextFieldFocused() {
                handler.post {
                    Log.d("FlixtorTV", "JS onTextFieldFocused. State: $currentState")
                    if (currentState == InputState.TEXT_INPUT_STARTING) {
                        // Expected transition - show keyboard
                        showKeyboard()
                    }
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

    // State machine transition logic with debouncing
    private fun transitionToState(newState: InputState) {
        val now = System.currentTimeMillis()

        // Prevent rapid state changes
        if (now - lastStateChangeTime < stateDebounceMs && newState == currentState) {
            Log.d("FlixtorTV", "State change debounced: $currentState -> $newState")
            return
        }

        val oldState = currentState
        currentState = newState
        lastStateChangeTime = now

        Log.d("FlixtorTV", "State transition: $oldState -> $newState")

        when (newState) {
            InputState.IDLE -> {
                cursorView.visibility = View.GONE
                handler.removeCallbacks(cursorUpdateRunnable)
                handler.removeCallbacks(focusAndIdleHandlerRunnable)
            }
            InputState.CURSOR_ACTIVE -> {
                cursorView.visibility = View.VISIBLE
                handler.removeCallbacks(focusAndIdleHandlerRunnable)
                handler.postDelayed(focusAndIdleHandlerRunnable, 500)
                if (!this.hasFocus()) {
                    this.requestFocus()
                }
            }
            InputState.TEXT_INPUT_STARTING -> {
                cursorView.visibility = View.GONE
                handler.removeCallbacks(cursorUpdateRunnable)
                handler.removeCallbacks(focusAndIdleHandlerRunnable)
            }
            InputState.TEXT_INPUT_ACTIVE -> {
                cursorView.visibility = View.GONE
                isKeyboardVisible = true
            }
            InputState.TEXT_INPUT_ENDING -> {
                // Keep cursor hidden until keyboard is fully closed
                cursorView.visibility = View.GONE
            }
        }
    }

    private fun setupFocusAndIdleHandler() {
        this.isFocusable = true
        this.isFocusableInTouchMode = true
        if (pointerModeEnabled) {
            this.requestFocus()
            transitionToState(InputState.CURSOR_ACTIVE)
        }

        // Enable modified focus navigation that preserves scrolling
        enableScrollableFocusNavigation()

        focusAndIdleHandlerRunnable = object : Runnable {
            override fun run() {
                // Only reclaim focus in CURSOR_ACTIVE state
                if (currentState == InputState.CURSOR_ACTIVE &&
                    pointerModeEnabled &&
                    !this@CursorWebView.hasFocus() &&
                    !isErrorScreenVisible) {
                    Log.d("FlixtorTV", "IdleHandler: Attempting to reclaim focus for CWV")
                    val reclaimed = this@CursorWebView.requestFocus()
                    if (reclaimed && cursorView.visibility != View.VISIBLE) {
                        cursorView.visibility = View.VISIBLE
                    }
                }

                // Hide cursor after inactivity (only in CURSOR_ACTIVE state)
                if (currentState == InputState.CURSOR_ACTIVE) {
                    val idleTime = System.currentTimeMillis() - lastActivityTime
                    if (idleTime >= cursorHideTimeout && cursorView.visibility == View.VISIBLE) {
                        transitionToState(InputState.IDLE)
                        Log.d("FlixtorTV", "Cursor hidden due to inactivity")
                    }
                }

                handler.postDelayed(this, 500)
            }
        }
        handler.postDelayed(focusAndIdleHandlerRunnable, 500)

        webView.setOnFocusChangeListener { _, webViewHasFocus ->
            Log.d("FlixtorTV", "WebView focus changed. Has Focus: $webViewHasFocus, State: $currentState")
            if (!webViewHasFocus && currentState == InputState.TEXT_INPUT_ACTIVE) {
                Log.d("FlixtorTV", "WebView lost focus while in text input")
                // Let the JS blur handler manage the transition
            } else if (webViewHasFocus &&
                       pointerModeEnabled &&
                       currentState != InputState.TEXT_INPUT_STARTING &&
                       currentState != InputState.TEXT_INPUT_ACTIVE) {
                Log.d("FlixtorTV", "WebView gained focus inappropriately, redirecting to CWV")
                handler.postDelayed({
                    if (currentState != InputState.TEXT_INPUT_ACTIVE) {
                        this@CursorWebView.requestFocus()
                    }
                }, 50)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: AndroidKeyEvent?): Boolean {
        if (isErrorScreenVisible) return false

        // Handle text input state
        if (currentState == InputState.TEXT_INPUT_ACTIVE) {
            when (keyCode) {
                AndroidKeyEvent.KEYCODE_BACK -> {
                    Log.d("FlixtorTV", "BACK pressed while text field active")
                    webView.evaluateJavascript(
                        """
                        (function() {
                            const el = document.activeElement;
                            if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
                                el.blur();
                                return 'blurred';
                            }
                            return 'not_input';
                        })()
                        """.trimIndent()
                    ) { result ->
                        Log.d("FlixtorTV", "Blur result: $result")
                    }
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

        // Ignore keys during transitions
        if (currentState == InputState.TEXT_INPUT_STARTING || currentState == InputState.TEXT_INPUT_ENDING) {
            return true
        }

        val now = System.currentTimeMillis()
        if (now - lastKeyTime < 100 && !(keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER && (now - lastCenterPressTime > 300))) {
            return true
        }
        lastKeyTime = now
        lastActivityTime = now

        // Activate cursor on movement if idle
        if (pointerModeEnabled &&
            currentState == InputState.IDLE &&
            (keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP || keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN ||
                    keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT || keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT ||
                    keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER)) {
            transitionToState(InputState.CURSOR_ACTIVE)
            Log.d("FlixtorTV", "Cursor activated via key press")
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
                    if (currentState != InputState.CURSOR_ACTIVE) {
                        transitionToState(InputState.CURSOR_ACTIVE)
                        return true
                    }
                    val doublePress = now - lastCenterPressTime < 300
                    lastCenterPressTime = now
                    if (doublePress) {
                        Log.d("FlixtorTV", "Double press: Disabling pointer mode")
                        pointerModeEnabled = false
                        transitionToState(InputState.IDLE)
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
                    transitionToState(InputState.CURSOR_ACTIVE)
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
                    if (currentState != InputState.CURSOR_ACTIVE) {
                        transitionToState(InputState.CURSOR_ACTIVE)
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
        if (currentState == InputState.TEXT_INPUT_ACTIVE) {
            return super.onKeyUp(keyCode, event)
        }
        if (pressedKeys.remove(keyCode) && pressedKeys.isEmpty()) {
            handler.removeCallbacks(cursorUpdateRunnable)
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun simulateClick(x: Int, y: Int) {
        if (currentState != InputState.CURSOR_ACTIVE) {
            Log.w("FlixtorTV", "Click ignored - not in CURSOR_ACTIVE state: $currentState")
            return
        }

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

        // Check what element was clicked SYNCHRONOUSLY
        webView.evaluateJavascript(
            """
            (function() {
                const el = document.elementFromPoint($x, $y);
                if (!el) return 'none';
                const tag = el.tagName.toLowerCase();
                if (tag === 'input' || tag === 'textarea') {
                    // Don't focus yet - wait for state to be ready
                    return 'text_field_' + tag;
                }
                return 'clicked_tag_' + tag;
            })()
            """.trimIndent()
        ) { result ->
            Log.d("FlixtorTV", "JS Click Result: $result, State: $currentState")
            if (result != null && result.contains("text_field_")) {
                // Transition to text input starting
                transitionToState(InputState.TEXT_INPUT_STARTING)

                // Now focus the element - this will trigger JS focusin
                webView.evaluateJavascript(
                    """
                    (function() {
                        const el = document.elementFromPoint($x, $y);
                        if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
                            el.focus();
                            return 'focused';
                        }
                        return 'not_found';
                    })()
                    """.trimIndent()
                ) { focusResult ->
                    Log.d("FlixtorTV", "Focus result: $focusResult")
                    // JS focusin event will trigger onTextFieldFocused callback
                }
            } else {
                // Non-text click - maintain cursor state
                if (currentState == InputState.CURSOR_ACTIVE && pointerModeEnabled) {
                    this@CursorWebView.requestFocus()
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
            if (currentState != InputState.CURSOR_ACTIVE || !pointerModeEnabled) {
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

    // Keyboard visibility monitoring with polling fallback
    private fun waitForKeyboardClose() {
        Log.d("FlixtorTV", "Waiting for keyboard to close")
        hideKeyboard()

        // Poll for keyboard state
        val startTime = System.currentTimeMillis()
        val maxWaitTime = 1000L // 1 second max wait

        val keyboardCheckRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime

                // Check if view height has changed (keyboard closed)
                val heightDiff = rootView.height - height
                val keyboardProbablyClosed = heightDiff < 100 // Less than 100px difference

                if (keyboardProbablyClosed || elapsed >= maxWaitTime) {
                    Log.d("FlixtorTV", "Keyboard closed detected (elapsed: ${elapsed}ms)")
                    isKeyboardVisible = false
                    onKeyboardClosed()
                } else {
                    // Check again in 50ms
                    handler.postDelayed(this, 50)
                }
            }
        }
        handler.postDelayed(keyboardCheckRunnable, 50)
    }

    private fun onKeyboardClosed() {
        Log.d("FlixtorTV", "onKeyboardClosed - State: $currentState")
        if (currentState != InputState.TEXT_INPUT_ENDING) {
            Log.w("FlixtorTV", "Unexpected state in onKeyboardClosed: $currentState")
            return
        }

        webView.clearFocus()

        if (pointerModeEnabled) {
            this.isFocusable = true
            this.isFocusableInTouchMode = true
            val focusRequested = this.requestFocus()
            Log.d("FlixtorTV", "Focus restored to CWV: $focusRequested")

            transitionToState(InputState.CURSOR_ACTIVE)
        } else {
            transitionToState(InputState.IDLE)
            webView.requestFocus()
        }

        lastActivityTime = System.currentTimeMillis()
    }

    private fun showKeyboard() {
        Log.d("FlixtorTV", "Showing keyboard")
        if (currentState != InputState.TEXT_INPUT_STARTING) {
            Log.w("FlixtorTV", "Unexpected showKeyboard call in state: $currentState")
            return
        }

        imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
        isKeyboardVisible = true
        transitionToState(InputState.TEXT_INPUT_ACTIVE)
    }

    private fun hideKeyboard() {
        Log.d("FlixtorTV", "Hiding keyboard")
        imm.hideSoftInputFromWindow(this.windowToken, 0)
    }

    private fun resetFocus() {
        Log.d("FlixtorTV", "resetFocus called")
        if (currentState == InputState.TEXT_INPUT_ACTIVE) {
            transitionToState(InputState.TEXT_INPUT_ENDING)
            waitForKeyboardClose()
        }
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
    fun canGoBack(): Boolean = if (currentState == InputState.TEXT_INPUT_ACTIVE) false else webView.canGoBack()

    fun goBack() {
        if (currentState == InputState.TEXT_INPUT_ACTIVE) {
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

    fun onPause() {
        Log.d("FlixtorTV", "onPause called")
        webView.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    fun onResume() {
        Log.d("FlixtorTV", "onResume called, State: $currentState")
        webView.onResume()

        // Restart focus handler if in cursor mode
        if (pointerModeEnabled && currentState != InputState.TEXT_INPUT_ACTIVE && currentState != InputState.TEXT_INPUT_ENDING) {
            handler.removeCallbacks(focusAndIdleHandlerRunnable)
            handler.postDelayed(focusAndIdleHandlerRunnable, 500)

            if (currentState == InputState.CURSOR_ACTIVE || currentState == InputState.IDLE) {
                transitionToState(InputState.CURSOR_ACTIVE)
                this.requestFocus()
            }
        } else if (!pointerModeEnabled && currentState != InputState.TEXT_INPUT_ACTIVE) {
            transitionToState(InputState.IDLE)
        }

        lastActivityTime = System.currentTimeMillis()
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