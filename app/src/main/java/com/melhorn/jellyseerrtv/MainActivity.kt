package com.melhorn.jellyseerrtv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import kotlin.math.abs

/**
 * Full-screen Jellyseerr client for Android TV.
 *
 * The site is shown in a WebView; every remote / gamepad button is intercepted
 * here and translated into calls on the injected navigation layer
 * (assets/tvnav.js), which moves a highlight ring between controls.
 */
class MainActivity : Activity() {

    private enum class Dir(val js: String) { UP("up"), DOWN("down"), LEFT("left"), RIGHT("right") }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private var popup: WebView? = null

    private lateinit var overlay: LinearLayout
    private lateinit var overlayTitle: TextView
    private lateinit var overlayMessage: TextView
    private lateinit var overlayButtons: LinearLayout
    private lateinit var retryButton: Button

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private val navScript by lazy { assets.open("tvnav.js").bufferedReader().use { it.readText() } }
    private val scriptHandlers = HashMap<WebView, ScriptHandler>()
    private val bridges = HashMap<WebView, Bridge>()

    private var mainFrameFailed = false
    private var loadedOnce = false

    // Back / B: act on release so a long hold can open settings instead.
    private var backDown = false
    private var backLongHandled = false

    // Analog sticks.
    private var stickDir: Dir? = null
    private var scrollVelocity = 0f

    private val serverUrl: String
        get() = prefs.getString(PREF_URL, null) ?: BuildConfig.DEFAULT_SERVER_URL

    private val viewportWidth: Int
        get() = prefs.getInt(PREF_VIEWPORT, DEFAULT_VIEWPORT)

    // ------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this).apply { setBackgroundColor(BG) }
        webView = WebView(this)
        configureWebView(webView, isPopup = false)
        root.addView(webView, matchParent())

        buildOverlay()
        root.addView(overlay, matchParent())
        setContentView(root)

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        }
        if (webView.url == null) loadHome()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        popup?.onResume()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        webView.onPause()
        popup?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        popup?.destroy()
        webView.destroy()
        super.onDestroy()
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    // ------------------------------------------------------------------------
    // WebView setup
    // ------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun configureWebView(wv: WebView, isPopup: Boolean) {
        wv.setBackgroundColor(BG)
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(!isPopup)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = "$userAgentString JellyseerrTV/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        val bridge = Bridge(wv)
        bridges[wv] = bridge
        wv.addJavascriptInterface(bridge, "TvBridge")
        installNavScript(wv, if (isPopup) 0 else viewportWidth)

        wv.webViewClient = Client(isPopup)
        wv.webChromeClient = Chrome()
        wv.requestFocus()
    }

    private fun scriptFor(viewport: Int) = "window.__TVNAV_VIEWPORT=$viewport;\n$navScript"

    private fun installNavScript(wv: WebView, viewport: Int) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            scriptHandlers.remove(wv)?.remove()
            scriptHandlers[wv] = WebViewCompat.addDocumentStartJavaScript(wv, scriptFor(viewport), setOf("*"))
        }
    }

    /** Fallback for WebView versions without document-start scripts. */
    private fun injectIfNeeded(wv: WebView) {
        val vp = if (wv == webView) viewportWidth else 0
        wv.evaluateJavascript("if(!window.__tvnav){${scriptFor(vp)}}", null)
    }

    private fun loadHome() {
        mainFrameFailed = false
        showOverlay(getString(R.string.connecting), serverUrl, showButtons = false)
        webView.loadUrl(serverUrl)
    }

    private inner class Client(private val isPopup: Boolean) : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (!isPopup && view == webView) mainFrameFailed = false
        }

        override fun onPageFinished(view: WebView, url: String?) {
            injectIfNeeded(view)
            if (isPopup || view != webView) return
            if (!mainFrameFailed) {
                hideOverlay()
                if (!loadedOnce) {
                    loadedOnce = true
                    if (!prefs.getBoolean(PREF_HELP_SHOWN, false)) {
                        prefs.edit().putBoolean(PREF_HELP_SHOWN, true).apply()
                        handler.postDelayed({ showHelp() }, 800)
                    }
                }
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (Build.VERSION.SDK_INT >= 23 && request.isForMainFrame && !isPopup && view == webView) {
                onMainFrameError(error.description?.toString())
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
            if (Build.VERSION.SDK_INT < 23 && !isPopup && view == webView) {
                onMainFrameError(description)
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            handleExternal(request.url)

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            handleExternal(Uri.parse(url))
    }

    /** Keep http(s) inside the app; hand other schemes (intent:, market:, etc.) to Android. */
    private fun handleExternal(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data" ||
            scheme == "blob" || scheme == "javascript"
        ) return false
        try {
            val intent = if (scheme == "intent") Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, uri)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.cannot_open, uri.toString()), Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun onMainFrameError(description: String?) {
        mainFrameFailed = true
        showOverlay(
            getString(R.string.cannot_reach),
            getString(R.string.cannot_reach_detail, serverUrl, description ?: ""),
            showButtons = true
        )
    }

    private inner class Chrome : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            closePopup()
            val child = WebView(this@MainActivity)
            configureWebView(child, isPopup = true)
            root.addView(child, root.indexOfChild(overlay), matchParent())
            popup = child
            (resultMsg.obj as WebView.WebViewTransport).webView = child
            resultMsg.sendToTarget()
            child.requestFocus()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            if (window == popup) closePopup()
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            customView?.let { hideCustomView() }
            customView = view
            customViewCallback = callback
            root.addView(view, matchParent())
            view.requestFocus()
        }

        override fun onHideCustomView() {
            hideCustomView()
        }

        // Avoid the grey placeholder WebView draws for <video> without a poster.
        override fun getDefaultVideoPoster(): Bitmap =
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    private fun hideCustomView() {
        val v = customView ?: return
        root.removeView(v)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        activeWebView().requestFocus()
    }

    private fun closePopup() {
        val p = popup ?: return
        popup = null
        root.removeView(p)
        scriptHandlers.remove(p)
        bridges.remove(p)
        p.stopLoading()
        p.destroy()
        webView.requestFocus()
    }

    private fun activeWebView(): WebView = popup ?: webView

    // ------------------------------------------------------------------------
    // JavaScript bridge (called from tvnav.js on a background thread)
    // ------------------------------------------------------------------------

    private inner class Bridge(private val wv: WebView) {
        @Volatile
        var typing = false

        @JavascriptInterface
        fun setTextInputActive(active: Boolean) {
            typing = active
            if (!active) handler.post { hideKeyboard(wv) }
        }

        @JavascriptInterface
        fun showKeyboard() {
            handler.postDelayed({
                wv.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(wv, 0)
            }, 80)
        }

        @JavascriptInterface
        fun chooseOption(json: String) {
            handler.post { showSelectDialog(wv, json) }
        }
    }

    private fun hideKeyboard(wv: WebView) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(wv.windowToken, 0)
    }

    private fun showSelectDialog(wv: WebView, json: String) {
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            return
        }
        val arr = obj.optJSONArray("options") ?: return
        val items = Array(arr.length()) { arr.optString(it) }
        val builder = AlertDialog.Builder(this, DIALOG_THEME)
            .setSingleChoiceItems(items, obj.optInt("selected", -1)) { d, which ->
                d.dismiss()
                wv.evaluateJavascript("window.__tvnav&&__tvnav.applySelect($which)", null)
            }
            .setNegativeButton(android.R.string.cancel, null)
        obj.optString("title").takeIf { it.isNotBlank() }?.let { builder.setTitle(it) }
        builder.show()
    }

    private fun js(code: String) {
        activeWebView().evaluateJavascript("window.__tvnav&&__tvnav.$code", null)
    }

    // ------------------------------------------------------------------------
    // Remote / gamepad buttons
    // ------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN

        // Full-screen video: only Back is ours (to exit full screen).
        if (customView != null) {
            if (isBackKey(code)) {
                if (!down) hideCustomView()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        // Connection / error screen uses normal Android focus on its buttons.
        if (overlay.visibility == View.VISIBLE) {
            if (isMenuKey(code)) {
                if (down && event.repeatCount == 0) showSettings()
                return true
            }
            if (isBackKey(code)) {
                if (!down && event.repeatCount == 0) showExitDialog()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        val bridge = bridges[activeWebView()]
        val typing = bridge?.typing == true

        if (isBackKey(code)) {
            handleBackKey(event)
            return true
        }

        // While a text field has focus, left/right move the cursor and a real
        // keyboard's Enter submits, so let the WebView have those.
        if (typing && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_FORWARD_DEL ||
                code == KeyEvent.KEYCODE_SPACE)
        ) {
            return super.dispatchKeyEvent(event)
        }

        val action: (() -> Unit)? = when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> { { move(Dir.UP) } }
            KeyEvent.KEYCODE_DPAD_DOWN -> { { move(Dir.DOWN) } }
            KeyEvent.KEYCODE_DPAD_LEFT -> { { move(Dir.LEFT) } }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { { move(Dir.RIGHT) } }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> once(event) { js("activate()") }
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_SEARCH -> once(event) { js("search()") }
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_MOVE_HOME -> once(event) { js("home()") }
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_CHANNEL_UP -> { { js("page(-1)") } }
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { { js("page(1)") } }
            KeyEvent.KEYCODE_BUTTON_L2 -> once(event) { js("top()") }
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_REFRESH -> once(event) { activeWebView().reload() }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_SETTINGS ->
                once(event) { showSettings() }
            else -> null
        }
        if (action == null) return super.dispatchKeyEvent(event)
        if (down) action()
        return true
    }

    /** Run only on the first key-down, not on auto-repeat. */
    private fun once(event: KeyEvent, block: () -> Unit): () -> Unit =
        { if (event.repeatCount == 0) block() }

    private fun isBackKey(code: Int) = code == KeyEvent.KEYCODE_BACK ||
            code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_ESCAPE

    private fun isMenuKey(code: Int) = code == KeyEvent.KEYCODE_MENU ||
            code == KeyEvent.KEYCODE_BUTTON_START || code == KeyEvent.KEYCODE_SETTINGS

    private fun handleBackKey(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    backDown = true
                    backLongHandled = false
                } else if (backDown && !backLongHandled && event.repeatCount >= LONG_PRESS_REPEATS) {
                    backLongHandled = true
                    showSettings()
                }
            }
            KeyEvent.ACTION_UP -> {
                if (backDown && !backLongHandled && !event.isCanceled) goBack()
                backDown = false
            }
        }
    }

    private fun move(dir: Dir) = js("move('${dir.js}')")

    private fun goBack() {
        val wv = activeWebView()
        wv.evaluateJavascript("window.__tvnav?__tvnav.back():false") { result ->
            if (result == "true") return@evaluateJavascript
            when {
                wv == popup -> if (wv.canGoBack()) wv.goBack() else closePopup()
                wv.canGoBack() -> wv.goBack()
                else -> showExitDialog()
            }
        }
    }

    // ------------------------------------------------------------------------
    // Analog sticks / hat
    // ------------------------------------------------------------------------

    private val stickRepeat = object : Runnable {
        override fun run() {
            val d = stickDir ?: return
            move(d)
            handler.postDelayed(this, STICK_REPEAT_MS)
        }
    }

    private val scrollTick = object : Runnable {
        override fun run() {
            if (abs(scrollVelocity) < STICK_DEADZONE) return
            val dy = (scrollVelocity * 38).toInt()
            activeWebView().evaluateJavascript("window.__tvnav&&__tvnav.scroll($dy)", null)
            handler.postDelayed(this, 16)
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (customView != null || overlay.visibility == View.VISIBLE ||
            !event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.action != MotionEvent.ACTION_MOVE
        ) {
            return super.dispatchGenericMotionEvent(event)
        }

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val x = if (abs(hatX) > 0.5f) hatX else event.getAxisValue(MotionEvent.AXIS_X)
        val y = if (abs(hatY) > 0.5f) hatY else event.getAxisValue(MotionEvent.AXIS_Y)

        val newDir = stickDirection(x, y)
        if (newDir != stickDir) {
            handler.removeCallbacks(stickRepeat)
            stickDir = newDir
            if (newDir != null) {
                move(newDir)
                handler.postDelayed(stickRepeat, STICK_FIRST_REPEAT_MS)
            }
        }

        // Right stick scrolls the page.
        val rz = event.getAxisValue(MotionEvent.AXIS_RZ)
        val ry = event.getAxisValue(MotionEvent.AXIS_RY)
        val v = if (abs(rz) >= abs(ry)) rz else ry
        val wasScrolling = abs(scrollVelocity) >= STICK_DEADZONE
        scrollVelocity = if (abs(v) >= STICK_DEADZONE) v else 0f
        if (!wasScrolling && scrollVelocity != 0f) handler.post(scrollTick)

        return true
    }

    private fun stickDirection(x: Float, y: Float): Dir? {
        val held = stickDir
        // Hysteresis: easy to keep holding, deliberate to start.
        if (held != null) {
            val keep = when (held) {
                Dir.LEFT -> x < -STICK_RELEASE && abs(x) >= abs(y)
                Dir.RIGHT -> x > STICK_RELEASE && abs(x) >= abs(y)
                Dir.UP -> y < -STICK_RELEASE && abs(y) >= abs(x)
                Dir.DOWN -> y > STICK_RELEASE && abs(y) >= abs(x)
            }
            if (keep) return held
        }
        return when {
            abs(x) >= STICK_PRESS && abs(x) >= abs(y) -> if (x > 0) Dir.RIGHT else Dir.LEFT
            abs(y) >= STICK_PRESS -> if (y > 0) Dir.DOWN else Dir.UP
            else -> null
        }
    }

    // ------------------------------------------------------------------------
    // Connection overlay
    // ------------------------------------------------------------------------

    private fun buildOverlay() {
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG)
            setPadding(dp(48), dp(48), dp(48), dp(48))
        }
        overlayTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        overlayMessage = TextView(this).apply {
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(24))
        }
        overlayButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        retryButton = Button(this).apply {
            text = getString(R.string.retry)
            setOnClickListener { loadHome() }
        }
        val settingsButton = Button(this).apply {
            text = getString(R.string.settings)
            setOnClickListener { showSettings() }
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(8), 0, dp(8), 0) }
        overlayButtons.addView(retryButton, lp)
        overlayButtons.addView(settingsButton, lp)
        overlay.addView(overlayTitle)
        overlay.addView(overlayMessage)
        overlay.addView(overlayButtons)
    }

    private fun showOverlay(title: String, message: String, showButtons: Boolean) {
        overlayTitle.text = title
        overlayMessage.text = message
        overlayButtons.visibility = if (showButtons) View.VISIBLE else View.GONE
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        if (showButtons) retryButton.requestFocus()
    }

    private fun hideOverlay() {
        if (overlay.visibility != View.VISIBLE) return
        overlay.visibility = View.GONE
        activeWebView().requestFocus()
    }

    // ------------------------------------------------------------------------
    // Dialogs
    // ------------------------------------------------------------------------

    private fun showExitDialog() {
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.exit_title)
            .setPositiveButton(R.string.exit) { _, _ -> finish() }
            .setNeutralButton(R.string.settings) { _, _ -> showSettings() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun viewportLabel(width: Int): String = when (width) {
        1024 -> getString(R.string.size_large)
        1280 -> getString(R.string.size_normal)
        1600 -> getString(R.string.size_small)
        1920 -> getString(R.string.size_smallest)
        else -> getString(R.string.size_native)
    }

    private fun showSettings() {
        val items = arrayOf(
            getString(R.string.setting_server, serverUrl),
            getString(R.string.setting_size, viewportLabel(viewportWidth)),
            getString(R.string.setting_reload),
            getString(R.string.setting_home),
            getString(R.string.setting_help),
            getString(R.string.setting_signout),
            getString(R.string.exit),
        )
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(getString(R.string.settings_title, BuildConfig.VERSION_NAME))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showServerDialog()
                    1 -> showSizeDialog()
                    2 -> activeWebView().reload()
                    3 -> { closePopup(); loadHome() }
                    4 -> showHelp()
                    5 -> confirmSignOut()
                    6 -> finish()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showServerDialog() {
        val input = EditText(this).apply {
            setText(serverUrl)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.server_title)
            .setMessage(R.string.server_message)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val url = normalizeUrl(input.text.toString())
                if (url != null) {
                    prefs.edit().putString(PREF_URL, url).apply()
                    closePopup()
                    loadHome()
                } else {
                    Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(R.string.reset_default) { _, _ ->
                prefs.edit().remove(PREF_URL).apply()
                closePopup()
                loadHome()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    private fun normalizeUrl(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "http://$s"
        val uri = Uri.parse(s)
        if (uri.host.isNullOrBlank() || (uri.scheme != "http" && uri.scheme != "https")) return null
        if (uri.path.isNullOrEmpty()) s += "/"
        return s
    }

    private fun showSizeDialog() {
        val widths = intArrayOf(1024, 1280, 1600, 1920, 0)
        val labels = widths.map { viewportLabel(it) }.toTypedArray()
        val selected = widths.indexOf(viewportWidth)
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.size_title)
            .setSingleChoiceItems(labels, selected) { d, which ->
                d.dismiss()
                prefs.edit().putInt(PREF_VIEWPORT, widths[which]).apply()
                installNavScript(webView, widths[which])
                webView.reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.signout_title)
            .setMessage(R.string.signout_message)
            .setPositiveButton(R.string.signout_confirm) { _, _ ->
                CookieManager.getInstance().removeAllCookies {
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                    webView.clearCache(true)
                    webView.clearHistory()
                    closePopup()
                    loadHome()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.help_title)
            .setMessage(R.string.help_text)
            .setPositiveButton(R.string.got_it, null)
            .show()
    }

    companion object {
        private const val PREF_URL = "server_url"
        private const val PREF_VIEWPORT = "viewport_width"
        private const val PREF_HELP_SHOWN = "help_shown"
        private const val DEFAULT_VIEWPORT = 1280

        private val BG = Color.parseColor("#111827")
        private val DIALOG_THEME = android.R.style.Theme_Material_Dialog_Alert

        private const val LONG_PRESS_REPEATS = 12
        private const val STICK_PRESS = 0.6f
        private const val STICK_RELEASE = 0.35f
        private const val STICK_DEADZONE = 0.2f
        private const val STICK_FIRST_REPEAT_MS = 400L
        private const val STICK_REPEAT_MS = 140L
    }
}
