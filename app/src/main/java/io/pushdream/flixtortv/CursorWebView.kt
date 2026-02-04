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
import android.util.Log
import android.view.KeyEvent as AndroidKeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class CursorWebView @SuppressLint("SetJavaScriptEnabled")
constructor(private val ctx: Context) : FrameLayout(ctx) {

    companion object {
        private const val UNCHANGED = Int.MIN_VALUE
        private const val CURSOR_DISAPPEAR_TIMEOUT = 5000L
        private const val CURSOR_HIDE_IDLE_TIMEOUT = 3000L
        private const val KEYBOARD_SHOW_DELAY_MS = 120L  // Small delay for DOM to settle
    }

    // Cursor physics & state (TV Bro style)
    private var cursorRadius: Int = 0
    private var cursorRadiusPressed: Int = 0
    private var maxCursorSpeed: Float = 0f
    private var scrollStartPadding: Int = 100
    private var cursorStrokeWidth: Float = 0f
    
    private val cursorDirection = android.graphics.Point(0, 0)
    private val cursorPosition = PointF(0f, 0f)
    private val cursorSpeed = PointF(0f, 0f)
    private val paint = Paint()
    
    private var lastCursorUpdate = System.currentTimeMillis() - CURSOR_DISAPPEAR_TIMEOUT
    private var dpadCenterPressed = false
    private var pointerModeEnabled = true
    
    // Robust keyboard state tracking
    private var isKeyboardVisible = false
    
    private var lastCenterPressTime = 0L
    private var lastActivityTime = System.currentTimeMillis()

    private val handler = Handler(Looper.getMainLooper())
    private val webView: WebView = WebView(ctx)

    var isErrorScreenVisible = false

    // Global layout listener for keyboard detection
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    // Member variable for the focus and idle handler Runnable
    private lateinit var focusAndIdleHandlerRunnable: Runnable
    
    // Runnable for cursor hide after inactivity
    private val cursorHideRunnable = Runnable { invalidate() }

    // Check if cursor should be hidden due to timeout
    private val isCursorDisappeared: Boolean
        get() {
            val now = System.currentTimeMillis()
            return now - lastCursorUpdate > CURSOR_DISAPPEAR_TIMEOUT
        }

    init {
        initCursorParams()
        setupWebView()
        setupJsInterfaces()
        setupKeyboardDetection()
        setupFocusAndIdleHandler()
    }

    private fun initCursorParams() {
        paint.isAntiAlias = true
        setWillNotDraw(false)
        
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val displaySize = android.graphics.Point()
        display.getSize(displaySize)
        
        // TV Bro style sizing based on screen dimensions
        cursorStrokeWidth = (displaySize.x / 400f)
        cursorRadius = displaySize.x / 110
        cursorRadiusPressed = cursorRadius + dpToPx(5f).toInt()
        maxCursorSpeed = displaySize.x / 25f
        scrollStartPadding = displaySize.x / 15
    }
    
    private fun dpToPx(dp: Float): Float {
        return dp * ctx.resources.displayMetrics.density
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isInEditMode) return
        
        cursorPosition.set(w / 2f, h / 2f)
        postDelayed(cursorHideRunnable, CURSOR_DISAPPEAR_TIMEOUT)
    }

    private fun setupKeyboardDetection() {
        // Try WindowInsetsCompat first (more reliable on modern devices)
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            
            if (imeVisible && imeHeight > 0) {
                if (!isKeyboardVisible) {
                    Log.d("FlixtorTV", "Keyboard OPEN detected via WindowInsets (height: $imeHeight)")
                    onKeyboardVisibilityChanged(true)
                }
            } else {
                if (isKeyboardVisible) {
                    Log.d("FlixtorTV", "Keyboard CLOSED detected via WindowInsets")
                    onKeyboardVisibilityChanged(false)
                }
            }
            insets
        }
        
        // Fallback: GlobalLayoutListener for older devices or when WindowInsets doesn't work
        globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val r = Rect()
            this.getWindowVisibleDisplayFrame(r)
            val screenHeight = this.rootView.height
            val keypadHeight = screenHeight - r.bottom

            // Use adaptive threshold: 0.10 for more sensitivity on TV devices (was 0.12/0.15)
            val threshold = screenHeight * 0.10
            if (keypadHeight > threshold) {
                if (!isKeyboardVisible) {
                    Log.d("FlixtorTV", "Keyboard OPEN detected via LayoutListener (height: $keypadHeight, threshold: $threshold)")
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
            handler.removeCallbacks(cursorUpdateRunnable)
            // Ensure WebView has focus so typing works
            webView.requestFocus()
        } else {
            // Keyboard closed: Restore pointer mode
            pointerModeEnabled = true
            
            // Explicitly hide keyboard to ensure it's truly closed
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(webView.windowToken, 0)
            
            // Fix Ghost Keyboard: Explicitly blur the active element in JS/DOM
            webView.evaluateJavascript("""
                (function() {
                    try {
                        if (document.activeElement) {
                            var active = document.activeElement;
                            var tag = active.tagName.toLowerCase();
                            if (tag === 'input' || tag === 'textarea' || active.isContentEditable) {
                                active.blur();
                            }
                        }
                    } catch(e) {}
                })()
            """.trimIndent(), null)
            
            this.requestFocus() // Steal focus back for D-pad
            updateLastActivityTime()
        }
        invalidate()
    }

    private fun setupJsInterfaces() {
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onTextFieldBlurred() {
                Log.d("FlixtorTV", "JS: TextField blurred")
            }

            @JavascriptInterface
            fun onTextFieldFocused() {
                Log.d("FlixtorTV", "JS: TextField focused")
                handler.postDelayed({ showKeyboard() }, KEYBOARD_SHOW_DELAY_MS)
            }
        }, "Android")
        
        // Inject JS to notify us - includes contenteditable support
        webView.evaluateJavascript(
            """
            (function() {
                document.addEventListener('focusin', function(e) {
                    const tag = e.target.tagName.toLowerCase();
                    const isInput = ['input', 'textarea'].includes(tag);
                    const isContentEditable = e.target.isContentEditable || e.target.contentEditable === 'true';
                    
                    if (isInput || isContentEditable) {
                        if (typeof Android !== 'undefined' && Android.onTextFieldFocused) {
                            Android.onTextFieldFocused();
                        }
                    }
                });
                
                document.addEventListener('focusout', function(e) {
                    const tag = e.target.tagName.toLowerCase();
                    const isInput = ['input', 'textarea'].includes(tag);
                    const isContentEditable = e.target.isContentEditable || e.target.contentEditable === 'true';
                    
                    if (isInput || isContentEditable) {
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

        enableScrollableFocusNavigation()

        focusAndIdleHandlerRunnable = object : Runnable {
            override fun run() {
                // If keyboard is visible, DO NOTHING. Let the user type.
                if (isKeyboardVisible) {
                     handler.postDelayed(this, 1000)
                     return
                }

                if (pointerModeEnabled && !this@CursorWebView.hasFocus() && !isErrorScreenVisible && !webView.hasFocus()) {
                    Log.d("FlixtorTV", "IdleHandler: Reclaiming focus for CWV")
                    val reclaimed = this@CursorWebView.requestFocus()
                    if (reclaimed) {
                        invalidate()
                    }
                }

                val idleTime = System.currentTimeMillis() - lastActivityTime
                if (idleTime >= CURSOR_HIDE_IDLE_TIMEOUT &&
                    pointerModeEnabled &&
                    !isKeyboardVisible &&
                    this@CursorWebView.hasFocus() &&
                    !isCursorDisappeared
                ) {
                    // Force cursor disappear by updating lastCursorUpdate to old time
                    lastCursorUpdate = System.currentTimeMillis() - CURSOR_DISAPPEAR_TIMEOUT - 1
                    invalidate()
                    Log.d("FlixtorTV", "Cursor hidden due to inactivity")
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.postDelayed(focusAndIdleHandlerRunnable, 500)

        webView.setOnFocusChangeListener { _, webViewHasFocus ->
            Log.d("FlixtorTV", "WebView focus changed. Has Focus: $webViewHasFocus, Keyboard: $isKeyboardVisible")
        }
    }

    override fun dispatchKeyEvent(event: AndroidKeyEvent): Boolean {
        if (isInEditMode || !pointerModeEnabled) return super.dispatchKeyEvent(event)
        
        val keyCode = event.keyCode
        val action = event.action
        
        when (keyCode) {
            // Diagonal D-pad support
            AndroidKeyEvent.KEYCODE_DPAD_UP_LEFT -> {
                if (action == AndroidKeyEvent.ACTION_DOWN) {
                    handleDirectionKeyEvent(event, -1, -1, true)
                } else if (action == AndroidKeyEvent.ACTION_UP) {
                    handleDirectionKeyEvent(event, 0, 0, false)
                }
                return true
            }
            AndroidKeyEvent.KEYCODE_DPAD_UP_RIGHT -> {
                if (action == AndroidKeyEvent.ACTION_DOWN) {
                    handleDirectionKeyEvent(event, 1, -1, true)
                } else if (action == AndroidKeyEvent.ACTION_UP) {
                    handleDirectionKeyEvent(event, 0, 0, false)
                }
                return true
            }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN_LEFT -> {
                if (action == AndroidKeyEvent.ACTION_DOWN) {
                    handleDirectionKeyEvent(event, -1, 1, true)
                } else if (action == AndroidKeyEvent.ACTION_UP) {
                    handleDirectionKeyEvent(event, 0, 0, false)
                }
                return true
            }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN_RIGHT -> {
                if (action == AndroidKeyEvent.ACTION_DOWN) {
                    handleDirectionKeyEvent(event, 1, 1, true)
                } else if (action == AndroidKeyEvent.ACTION_UP) {
                    handleDirectionKeyEvent(event, 0, 0, false)
                }
                return true
            }
        }
        
        // Fall through to onKeyDown/onKeyUp for other keys
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: AndroidKeyEvent?): Boolean {
        if (isErrorScreenVisible) return false

        // If keyboard is visible, pass everything to WebView (except maybe Back)
        if (isKeyboardVisible) {
             if (keyCode == AndroidKeyEvent.KEYCODE_BACK) {
                 return super.onKeyDown(keyCode, event)
             }
             return super.onKeyDown(keyCode, event)
        }

        val now = System.currentTimeMillis()
        lastActivityTime = now

        // Show cursor on D-pad activity
        if (pointerModeEnabled && isCursorDisappeared &&
            (keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP || keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN ||
                    keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT || keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT ||
                    keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER)) {
            if (this.hasFocus()) {
                lastCursorUpdate = System.currentTimeMillis()
                invalidate()
            } else {
                this.requestFocus()
            }
        }

        when (keyCode) {
            AndroidKeyEvent.KEYCODE_BACK -> {
                if (pointerModeEnabled && webView.canGoBack()) {
                    webView.goBack()
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }
            AndroidKeyEvent.KEYCODE_DPAD_CENTER,
            AndroidKeyEvent.KEYCODE_ENTER,
            AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
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
                        webView.requestFocus()
                        invalidate()
                        return true
                    }
                    
                    if (!isCursorDisappeared) {
                        Log.d("FlixtorTV", "Single press: Simulating click")
                        dpadCenterPressed = true
                        dispatchMotionEvent(cursorPosition.x, cursorPosition.y, MotionEvent.ACTION_DOWN)
                        invalidate()
                    }
                    return true
                } else {
                    Log.d("FlixtorTV", "Enabling pointer mode")
                    pointerModeEnabled = true
                    this.requestFocus()
                    cursorPosition.set(width / 2f, height / 2f)
                    lastCursorUpdate = System.currentTimeMillis()
                    enableScrollableFocusNavigation()
                    invalidate()
                    return true
                }
            }
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                if (pointerModeEnabled) {
                    handleDirectionKeyEvent(event, -1, UNCHANGED, true)
                    return true
                }
                return handlePageScrolling(keyCode) || super.onKeyDown(keyCode, event)
            }
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (pointerModeEnabled) {
                    handleDirectionKeyEvent(event, 1, UNCHANGED, true)
                    return true
                }
                return handlePageScrolling(keyCode) || super.onKeyDown(keyCode, event)
            }
            AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                if (pointerModeEnabled) {
                    handleDirectionKeyEvent(event, UNCHANGED, -1, true)
                    return true
                }
                return handlePageScrolling(keyCode) || super.onKeyDown(keyCode, event)
            }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                if (pointerModeEnabled) {
                    handleDirectionKeyEvent(event, UNCHANGED, 1, true)
                    return true
                }
                return handlePageScrolling(keyCode) || super.onKeyDown(keyCode, event)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: AndroidKeyEvent?): Boolean {
        if (isKeyboardVisible) {
            return super.onKeyUp(keyCode, event)
        }
        
        when (keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_CENTER,
            AndroidKeyEvent.KEYCODE_ENTER,
            AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (pointerModeEnabled && !isCursorDisappeared) {
                    dispatchMotionEvent(cursorPosition.x, cursorPosition.y, MotionEvent.ACTION_UP)
                    dpadCenterPressed = false
                    invalidate()
                    
                    // Check if we hit an input field
                    checkClickTarget(cursorPosition.x.toInt(), cursorPosition.y.toInt())
                }
                return true
            }
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                if (pointerModeEnabled) {
                    handleDirectionKeyEvent(event, 0, UNCHANGED, false)
                    return true
                }
            }
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (pointerModeEnabled) {
                    handleDirectionKeyEvent(event, 0, UNCHANGED, false)
                    return true
                }
            }
            AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                if (pointerModeEnabled) {
                    handleDirectionKeyEvent(event, UNCHANGED, 0, false)
                    return true
                }
            }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                if (pointerModeEnabled) {
                    handleDirectionKeyEvent(event, UNCHANGED, 0, false)
                    return true
                }
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun handleDirectionKeyEvent(event: AndroidKeyEvent?, x: Int, y: Int, keyDown: Boolean) {
        lastCursorUpdate = System.currentTimeMillis()
        lastActivityTime = System.currentTimeMillis()
        
        if (keyDown) {
            if (event != null && keyDispatcherState.isTracking(event)) {
                return
            }
            handler.removeCallbacks(cursorUpdateRunnable)
            handler.post(cursorUpdateRunnable)
            if (event != null) {
                keyDispatcherState.startTracking(event, this)
            }
        } else {
            if (event != null) {
                keyDispatcherState.handleUpEvent(event)
            }
            cursorSpeed.set(0f, 0f)
        }
        
        cursorDirection.set(
            if (x == UNCHANGED) cursorDirection.x else x,
            if (y == UNCHANGED) cursorDirection.y else y
        )
    }

    private fun handlePageScrolling(keyCode: Int): Boolean {
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

    private fun dispatchMotionEvent(x: Float, y: Float, action: Int) {
        val downTime = android.os.SystemClock.uptimeMillis()
        val eventTime = android.os.SystemClock.uptimeMillis()
        
        val properties = arrayOfNulls<MotionEvent.PointerProperties>(1)
        val pp1 = MotionEvent.PointerProperties()
        pp1.id = 0
        pp1.toolType = MotionEvent.TOOL_TYPE_FINGER
        properties[0] = pp1
        
        val pointerCoords = arrayOfNulls<MotionEvent.PointerCoords>(1)
        val pc1 = MotionEvent.PointerCoords()
        pc1.x = x
        pc1.y = y
        pc1.pressure = 1f
        pc1.size = 1f
        pointerCoords[0] = pc1
        
        val motionEvent = MotionEvent.obtain(
            downTime, eventTime, action, 1, properties,
            pointerCoords, 0, 0, 1f, 1f, 0, 0, 0, 0
        )
        webView.dispatchTouchEvent(motionEvent)
        motionEvent.recycle()
    }

    private fun checkClickTarget(x: Int, y: Int) {
        webView.evaluateJavascript(
            """
            (function() {
                var el = document.elementFromPoint($x, $y);
                
                // Helper to check if element is text-editable
                function isTextEditable(elem) {
                    if (!elem) return false;
                    var tag = elem.tagName.toLowerCase();
                    
                    // Check for input types that accept text
                    if (tag === 'input') {
                        var inputType = (elem.type || 'text').toLowerCase();
                        var textTypes = ['text', 'password', 'email', 'tel', 'url', 'search', 'number'];
                        return textTypes.includes(inputType);
                    }
                    
                    // Textarea is always text-editable
                    if (tag === 'textarea') return true;
                    
                    // Check for contenteditable (both property and attribute)
                    if (elem.isContentEditable) return true;
                    if (elem.getAttribute && elem.getAttribute('contenteditable') === 'true') return true;
                    
                    return false;
                }
                
                // Walk up DOM tree to find focusable input
                var temp = el;
                var depth = 0;
                var maxDepth = 20;
                
                while (temp && temp !== document.body && temp !== document.documentElement && depth < maxDepth) {
                    if (isTextEditable(temp)) {
                        // Found a text field - focus it and click
                        try {
                            temp.focus();
                            temp.click();
                            if (temp.select) temp.select();
                        } catch(e) {}
                        return JSON.stringify({type: 'text_field', tag: temp.tagName.toLowerCase()});
                    }
                    
                    var tag = temp.tagName.toLowerCase();
                    if (tag === 'select') {
                        return JSON.stringify({type: 'select', tag: 'select'});
                    }
                    
                    temp = temp.parentElement;
                    depth++;
                }
                
                // Fallback: Check if document.activeElement is a text field after click propagates
                setTimeout(function() {
                    var active = document.activeElement;
                    if (active && isTextEditable(active)) {
                        if (typeof Android !== 'undefined' && Android.onTextFieldFocused) {
                            Android.onTextFieldFocused();
                        }
                    }
                }, 50);
                
                // Not a text field - blur any currently focused input to prevent ghost keyboard
                try {
                    var active = document.activeElement;
                    if (active) {
                        var activeTag = active.tagName.toLowerCase();
                        if (activeTag === 'input' || activeTag === 'textarea' || active.isContentEditable) {
                            active.blur();
                        }
                    }
                } catch(e) {}
                
                return JSON.stringify({type: 'other', tag: el ? el.tagName.toLowerCase() : 'none'});
            })()
            """.trimIndent()
        ) { result ->
            if (result != null) {
                try {
                    val cleanResult = result.trim().removeSurrounding("\"").replace("\\\"", "\"")
                    Log.d("FlixtorTV", "Click target result: $cleanResult")
                    
                    when {
                        cleanResult.contains("\"type\":\"text_field\"") -> {
                            Log.d("FlixtorTV", "Hit text field, scheduling keyboard show")
                            handler.postDelayed({ showKeyboard() }, KEYBOARD_SHOW_DELAY_MS)
                        }
                        cleanResult.contains("\"type\":\"select\"") -> {
                            Log.d("FlixtorTV", "Hit SELECT tag, pausing auto-focus for dropdown")
                        }
                        else -> {
                            // Non-text click - reclaim focus
                            if (pointerModeEnabled) {
                                val cwvFocus = this@CursorWebView.requestFocus()
                                if (cwvFocus) {
                                    invalidate()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("FlixtorTV", "Error parsing click target: ${e.message}")
                }
            }
        }
        lastActivityTime = System.currentTimeMillis()
    }

    // Physics-based cursor update runnable (TV Bro style)
    private val cursorUpdateRunnable = object : Runnable {
        override fun run() {
            if (!pointerModeEnabled || isKeyboardVisible || !this@CursorWebView.hasFocus()) {
                handler.removeCallbacks(this)
                return
            }
            
            handler.removeCallbacks(cursorHideRunnable)
            
            val newTime = System.currentTimeMillis()
            val dTime = newTime - lastCursorUpdate
            lastCursorUpdate = newTime
            
            // Physics-based acceleration: accelerationFactor = 0.05f * deltaTime
            val accelerationFactor = 0.05f * dTime
            
            // Accumulate speed based on direction with bounds
            cursorSpeed.set(
                bound(cursorSpeed.x + bound(cursorDirection.x.toFloat(), 1f) * accelerationFactor, maxCursorSpeed),
                bound(cursorSpeed.y + bound(cursorDirection.y.toFloat(), 1f) * accelerationFactor, maxCursorSpeed)
            )
            
            // Dead zone: stop micro-movements
            if (kotlin.math.abs(cursorSpeed.x) < 0.1f) cursorSpeed.x = 0f
            if (kotlin.math.abs(cursorSpeed.y) < 0.1f) cursorSpeed.y = 0f
            
            // If no direction and no speed, schedule hide and stop
            if (cursorDirection.x == 0 && cursorDirection.y == 0 && cursorSpeed.x == 0f && cursorSpeed.y == 0f) {
                postDelayed(cursorHideRunnable, CURSOR_DISAPPEAR_TIMEOUT)
                return
            }
            
            val prevPosition = PointF(cursorPosition.x, cursorPosition.y)
            
            // Apply speed to position
            cursorPosition.offset(cursorSpeed.x, cursorSpeed.y)
            
            // Bounds checking
            if (cursorPosition.x < 0) cursorPosition.x = 0f
            else if (cursorPosition.x > width - 1) cursorPosition.x = (width - 1).toFloat()
            
            if (cursorPosition.y < 0) cursorPosition.y = 0f
            else if (cursorPosition.y > height - 1) cursorPosition.y = (height - 1).toFloat()
            
            // Dispatch move event if pressed and position changed
            if (prevPosition.x != cursorPosition.x || prevPosition.y != cursorPosition.y) {
                if (dpadCenterPressed) {
                    dispatchMotionEvent(cursorPosition.x, cursorPosition.y, MotionEvent.ACTION_MOVE)
                }
            }
            
            // Edge scroll zones: scroll when cursor enters padding zone from edge
            var dx = 0
            var dy = 0
            
            if (cursorPosition.y > height - scrollStartPadding) {
                if (cursorSpeed.y > 0) dy = cursorSpeed.y.toInt()
            } else if (cursorPosition.y < scrollStartPadding) {
                if (cursorSpeed.y < 0) dy = cursorSpeed.y.toInt()
            }
            
            if (cursorPosition.x > width - scrollStartPadding) {
                if (cursorSpeed.x > 0) dx = cursorSpeed.x.toInt()
            } else if (cursorPosition.x < scrollStartPadding) {
                if (cursorSpeed.x < 0) dx = cursorSpeed.x.toInt()
            }
            
            if (dx != 0 || dy != 0) {
                webView.scrollBy(dx, dy)
            }
            
            lastActivityTime = System.currentTimeMillis()
            invalidate()
            handler.post(this)
        }
    }

    // Bound value to [-max, max]
    private fun bound(value: Float, max: Float): Float {
        return when {
            value > max -> max
            value < -max -> -max
            else -> value
        }
    }

    // Canvas-drawn cursor (TV Bro style)
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (isInEditMode || !pointerModeEnabled) return
        
        if (!isCursorDisappeared) {
            val cx = cursorPosition.x
            val cy = cursorPosition.y
            val radius = if (dpadCenterPressed) cursorRadiusPressed else cursorRadius
            
            // Draw filled circle with semi-transparent white
            paint.color = Color.argb(128, 255, 255, 255)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, radius.toFloat(), paint)
            
            // Draw stroke with gray
            paint.color = Color.GRAY
            paint.strokeWidth = cursorStrokeWidth
            paint.style = Paint.Style.STROKE
            canvas.drawCircle(cx, cy, radius.toFloat(), paint)
        }
    }

    private fun showKeyboard() {
        webView.requestFocus()
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val shown = imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
        Log.d("FlixtorTV", "showKeyboard (SHOW_IMPLICIT) result: $shown")
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
    fun updateLastActivityTime() { 
        lastActivityTime = System.currentTimeMillis()
        lastCursorUpdate = System.currentTimeMillis()
    }
    fun isPointerModeEnabled(): Boolean = pointerModeEnabled

    fun restoreCursorFocus() {
        handler.post {
            if (isPointerModeEnabled() && !isKeyboardVisible) {
                requestFocus()
                disableFocusNavigation()
                updateLastActivityTime()
                invalidate()
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
        ViewCompat.setOnApplyWindowInsetsListener(this, null)
        handler.removeCallbacksAndMessages(null)
        if (webView.parent is ViewGroup) {
            (webView.parent as ViewGroup).removeView(webView)
        }
        webView.destroy()
    }
}
