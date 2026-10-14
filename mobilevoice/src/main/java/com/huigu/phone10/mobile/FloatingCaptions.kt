package com.huigu.phone10.mobile

import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.doOnLayout
import kotlin.math.roundToInt

/** Optional renderer owned by VoiceService. No microphone, network or history storage. */
internal class FloatingCaptions(context: Context, onVisible: (Boolean) -> Unit,
    private val onEdit: (String, String) -> Boolean,
    private val onSend: (String) -> Boolean,
    private val onCancel: (String) -> Boolean) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var panel: LinearLayout? = null
    private var header: TextView? = null
    private var body: TextView? = null
    private var followButton: TextView? = null
    private val follow = CaptionFollow()
    private var playingRange: IntRange? = null
    private var paintedRange: IntRange? = null
    private var paintRevision = 0L
    private var playbackTracking = false
    private var scroll: ScrollView? = null
    private var contentArea: FrameLayout? = null
    private var reviewArea: LinearLayout? = null
    private var reviewLabel: TextView? = null
    private var editor: EditText? = null
    private var sendButton: Button? = null
    private var resizeHandle: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var expanded = true
    private var movieView: MovieCaptionText? = null
    private val appearanceStore = CaptionAppearanceStore(app)
    private var appearance = appearanceStore.load()
    private var movieMode = appearance.transparent
    private val appearanceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post {
            val next=appearanceStore.load()
            if (next.transparent != appearance.transparent) { movieMode=next.transparent;expanded=true }
            appearance = next
            movieView?.appearance = appearance
            (body as? MovieCaptionText)?.appearance = appearance
            resize(); paint(); scrollToPlaying()
        }
    }
    private var movieX = preferencesPosition("movie-x")
    private var movieY = preferencesPosition("movie-y")
    private fun preferencesPosition(key: String): Float = runCatching {
        app.getSharedPreferences("caption-window", Context.MODE_PRIVATE).getFloat(key, Float.NaN)
    }.getOrDefault(Float.NaN)
    private fun showingMovie() = movieMode && review.draftId == null
    private val inputFocus = CaptionInputFocus()
    private val editing get() = inputFocus.active
    private var applyingText = false
    private var imeBottomPixels = 0
    private val binding = CaptionReviewBinding()
    private val review get() = binding.review
    private val preferences = app.getSharedPreferences("caption-window", Context.MODE_PRIVATE)
    private var geometry = runCatching {
        CaptionLayout(preferences.getFloat("x", 12f), preferences.getFloat("y", 250f),
            preferences.getFloat("width", 286f), preferences.getFloat("height", 198f)).also {
            require(listOf(it.x, it.y, it.width, it.height).all { value -> value.isFinite() })
        }
    }.getOrDefault(CaptionLayout())
    private var text = ""
    private var status = "等待 AI 回复…"
    private var pending = false
    private val repaint = Runnable { pending = false; paint() }
    private val lifecycle = Phone10OverlayLifecycle { visible ->
        if (!visible) {
            handler.removeCallbacks(repaint); pending = false
            inputFocus.release(); imeBottomPixels = 0
            binding.detach()
            appearanceStore.preferences.unregisterOnSharedPreferenceChangeListener(appearanceListener)
            movieView = null
            panel = null; header = null; body = null; scroll = null; contentArea = null
            followButton = null; paintedRange = null; paintRevision++
            reviewArea = null; reviewLabel = null; editor = null; sendButton = null
            resizeHandle = null; params = null
        }
        onVisible(visible)
    }
    private fun dp(value: Int) = (value * app.resources.displayMetrics.density).roundToInt()
    private fun pixels(value: Float) = (value * app.resources.displayMetrics.density).roundToInt()
    private fun units(value: Float) = value / app.resources.displayMetrics.density

    private fun screen(): CaptionScreen {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return CaptionScreen(units(insets.left.toFloat()), units(insets.top.toFloat()),
                units((metrics.bounds.width() - insets.right).toFloat()),
                units((metrics.bounds.height() - maxOf(insets.bottom, if (editing) imeBottomPixels else 0)).toFloat()))
        }
        val metrics = app.resources.displayMetrics
        return CaptionScreen(0f, 24f, units(metrics.widthPixels.toFloat()), units(metrics.heightPixels.toFloat()) - 24f)
    }

    private fun rememberGeometry() {
        preferences.edit().putFloat("x", geometry.x).putFloat("y", geometry.y)
            .putFloat("width", geometry.width).putFloat("height", geometry.height).apply()
    }

    fun show(): Boolean {
        if (!Settings.canDrawOverlays(app)) { hide(); return false }
        if (lifecycle.visible) return true
        appearance = appearanceStore.load()
        movieMode = appearance.transparent
        val root = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 35, 32, 37)); cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.rgb(156, 111, 131))
            }
        }
        val title = TextView(app).apply {
            textSize = 13f; setTextColor(Color.rgb(244, 191, 211))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(12), 0)
            setOnClickListener {
                if (expanded) releaseInputFocus()
                if (review.draftId == null && appearance.transparent) { movieMode = true; expanded = true }
                else expanded = !expanded
                resize(); paint()
                if (expanded) {
                    if (review.draftId != null) binding.reveal() else scrollToPlaying()
                }
            }
        }
        val draftArea = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.rgb(62, 42, 54)); cornerRadius = dp(9).toFloat()
            }
            visibility = View.GONE
        }
        val draftLabel = TextView(app).apply {
            text = "待发送 · 尚未提交给 Operit"
            textSize = 12f; setTextColor(Color.rgb(255, 205, 222))
        }
        draftArea.addView(draftLabel, LinearLayout.LayoutParams(-1, dp(26)))
        val draftEditor = object : EditText(app) {
            override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP)
                    post { releaseInputFocus() }
                return super.onKeyPreIme(keyCode, event)
            }
        }.apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(202, 178, 190))
            hint = "点这里改字"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            filters = arrayOf(InputFilter.LengthFilter(4000))
            minLines = 2; maxLines = 4
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.rgb(44, 38, 46)); cornerRadius = dp(7).toFloat()
                setStroke(dp(1), Color.rgb(174, 124, 147))
            }
            setOnClickListener { beginEditing() }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (applyingText) return
                    val id = review.draftId ?: return
                    val value = s?.toString().orEmpty()
                    review.userEdited(value)
                    sendButton?.isEnabled = review.canSend
                    onEdit(id, value)
                }
            })
        }
        draftArea.addView(draftEditor, LinearLayout.LayoutParams(-1, -2))
        val actions = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val cancel = Button(app).apply {
            text = "取消"; isAllCaps = false
            setOnClickListener {
                val id = review.draftId ?: return@setOnClickListener
                releaseInputFocus(); onCancel(id)
            }
        }
        val send = Button(app).apply {
            text = "发送"; isAllCaps = false; isEnabled = false
            setOnClickListener {
                val id = review.draftId ?: return@setOnClickListener
                if (!review.canSend) return@setOnClickListener
                val value = draftEditor.text.toString()
                releaseInputFocus()
                onSend(id)
            }
        }
        actions.addView(cancel, LinearLayout.LayoutParams(0, dp(43), 1f))
        actions.addView(send, LinearLayout.LayoutParams(0, dp(43), 1f))
        draftArea.addView(actions, LinearLayout.LayoutParams(-1, dp(46)))
        val content = MovieCaptionText(app).apply {
            appearance = this@FloatingCaptions.appearance
            maxLines = Int.MAX_VALUE; ellipsize = null; gravity = Gravity.LEFT
            setPadding(dp(14), dp(3), dp(14), dp(50))
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        val stack = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
        stack.addView(draftArea, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(7), dp(4), dp(7), dp(3))
        })
        stack.addView(content, LinearLayout.LayoutParams(-1, -2))
        val scroller = object : ScrollView(app) {
            private var downY = 0f
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) downY = event.y
                if (review.draftId == null && event.actionMasked == MotionEvent.ACTION_MOVE &&
                    kotlin.math.abs(event.y - downY) > ViewConfiguration.get(app).scaledTouchSlop) {
                    follow.userScrolled(); paintRevision++; updateFollowButton()
                }
                // Observe only; native ScrollView keeps touch, fling and click ownership.
                return super.dispatchTouchEvent(event)
            }
            override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                    follow.userScrolled(); paintRevision++; updateFollowButton()
                }
                return super.performAccessibilityAction(action, arguments)
            }
        }.apply {
            isFillViewport = false
            addView(stack, FrameLayout.LayoutParams(-1, -2))
        }
        val area = FrameLayout(app).apply { addView(scroller, FrameLayout.LayoutParams(-1, -1)) }
        val resume = TextView(app).apply {
            text = "回到朗读"; textSize = 12f
            setTextColor(Color.rgb(255, 220, 233)); gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.rgb(77, 47, 64)); cornerRadius = dp(12).toFloat()
            }
            visibility = View.GONE
            setOnClickListener {
                follow.resume(); updateFollowButton()
                if (playbackTracking) scrollToPlaying() else binding.followCaptionAtBottom()
            }
        }
        area.addView(resume, FrameLayout.LayoutParams(-2, dp(38), Gravity.BOTTOM or Gravity.LEFT).apply {
            setMargins(dp(10), 0, 0, dp(6))
        })
        val grip = object : View(app) {
            private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(244, 191, 211); strokeWidth = dp(2).toFloat(); strokeCap = Paint.Cap.ROUND
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val right = width - dp(12).toFloat(); val bottom = height - dp(12).toFloat()
                for (length in listOf(6, 13, 20)) canvas.drawLine(right - dp(length), bottom, right, bottom - dp(length), ink)
            }
            override fun performClick(): Boolean { super.performClick(); return true }
        }.apply {
            contentDescription = "拖动调整字幕大小"
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        area.addView(grip, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.RIGHT))
        var resizeStart = geometry
        var resizeDownX = 0f; var resizeDownY = 0f
        val resizeGesture = Phone10OverlayGesture(ViewConfiguration.get(app).scaledTouchSlop.toFloat())
        grip.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resizeStart = geometry.visible(screen(), true, review.draftId != null)
                    resizeDownX = event.rawX; resizeDownY = event.rawY
                    resizeGesture.down(event.rawX, event.rawY); true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (resizeGesture.move(event.rawX, event.rawY)) {
                        geometry = resizeStart.resizeBy(units(event.rawX - resizeDownX), units(event.rawY - resizeDownY),
                            screen(), review.draftId != null)
                        resize()
                    }; true
                }
                MotionEvent.ACTION_UP -> {
                    if (resizeGesture.up(event.rawX, event.rawY)) grip.performClick()
                    rememberGeometry(); true
                }
                MotionEvent.ACTION_CANCEL -> { resizeGesture.cancel(); rememberGeometry(); true }
                else -> false
            }
        }
        grip.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "增大字幕窗口"))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, "缩小字幕窗口"))
            }
            override fun performAccessibilityAction(host: View, action: Int, arguments: Bundle?): Boolean {
                val delta = when (action) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 32f
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -32f
                    else -> return super.performAccessibilityAction(host, action, arguments)
                }
                geometry = geometry.resizeBy(delta, delta, screen(), review.draftId != null)
                resize(); rememberGeometry(); return true
            }
        }
        val movie = MovieCaptionText(app).apply {
            appearance = this@FloatingCaptions.appearance
            contentDescription = "字幕；点开查看全文，拖动移动位置"
            setOnClickListener {
                movieMode = false; expanded = true; paintedRange = null
                resize(); paint(); scrollToPlaying()
            }
        }
        root.addView(movie, LinearLayout.LayoutParams(-1, -1))
        movieView = movie
        root.addView(title, LinearLayout.LayoutParams(-1, dp(44)))
        root.addView(area, LinearLayout.LayoutParams(-1, dp(154)))
        val layout = WindowManager.LayoutParams(dp(286), -2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = dp(12); y = dp(250)
        }
        var startX = 0; var startY = 0; var downX = 0f; var downY = 0f
        val gesture = Phone10OverlayGesture(ViewConfiguration.get(app).scaledTouchSlop.toFloat())
        title.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layout.x; startY = layout.y; downX = event.rawX; downY = event.rawY
                    gesture.down(downX, downY); true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (gesture.move(event.rawX, event.rawY)) {
                        geometry = geometry.moveTo(units(startX + event.rawX - downX),
                            units(startY + event.rawY - downY), screen(), expanded, review.draftId != null)
                        reposition()
                    }; true
                }
                MotionEvent.ACTION_UP -> {
                    if (gesture.up(event.rawX, event.rawY)) title.performClick()
                    rememberGeometry(); true
                }
                MotionEvent.ACTION_CANCEL -> { gesture.cancel(); rememberGeometry(); true }
                else -> false
            }
        }
        val movieGesture = Phone10OverlayGesture(ViewConfiguration.get(app).scaledTouchSlop.toFloat())
        var movieStartX = 0; var movieStartY = 0; var movieDownX = 0f; var movieDownY = 0f
        movie.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    movieStartX = layout.x; movieStartY = layout.y
                    movieDownX = event.rawX; movieDownY = event.rawY
                    movieGesture.down(event.rawX, event.rawY); true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (movieGesture.move(event.rawX, event.rawY)) {
                        movieX = units(movieStartX + event.rawX - movieDownX)
                        movieY = units(movieStartY + event.rawY - movieDownY)
                        reposition()
                    }; true
                }
                MotionEvent.ACTION_UP -> {
                    if (movieGesture.up(event.rawX, event.rawY)) movie.performClick()
                    preferences.edit().putFloat("movie-x", movieX).putFloat("movie-y", movieY).apply(); true
                }
                MotionEvent.ACTION_CANCEL -> { movieGesture.cancel(); true }
                else -> false
            }
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { if (panel === v) lifecycle.detached() }
        })
        if (Build.VERSION.SDK_INT >= 30) root.setOnApplyWindowInsetsListener { _, insets ->
            val shown = insets.isVisible(WindowInsets.Type.ime())
            val bottom = if (editing && shown) insets.getInsets(WindowInsets.Type.ime()).bottom else 0
            if (bottom != imeBottomPixels) {
                imeBottomPixels = bottom
                root.post { if (panel === root) reposition() }
            }
            if (inputFocus.keyboardVisible(shown)) root.post { if (panel === root) releaseInputFocus() }
            insets
        }
        panel = root; header = title; body = content; scroll = scroller; contentArea = area
        followButton = resume
        reviewArea = draftArea; reviewLabel = draftLabel; editor = draftEditor
        sendButton = send; resizeHandle = grip; params = layout
        val surface = object : CaptionReviewBinding.Surface {
            override fun editorText() = draftEditor.text.toString()
            override fun showEditorText(value: String) {
                applyingText = true
                val filters = draftEditor.filters
                try {
                    draftEditor.filters = emptyArray()
                    draftEditor.setText(value)
                    draftEditor.setSelection(value.length)
                } finally { draftEditor.filters = filters; applyingText = false }
            }
            override fun afterLayout(action: () -> Unit) {
                scroller.post { scroller.doOnLayout { action() } }
            }
            override fun post(action: () -> Unit) { scroller.post(action) }
            override fun scrollToReview() { scroller.scrollTo(0, 0) }
            override fun scrollToCaptionEnd() {
                if (follow.enabled && !playbackTracking && playingRange == null) scroller.fullScroll(View.FOCUS_DOWN)
            }
        }
        sizeAndClamp()
        try { manager.addView(root, layout) }
        catch (_: RuntimeException) { lifecycle.detached(); return false }
        lifecycle.attached { manager.removeView(root) }
        appearanceStore.preferences.registerOnSharedPreferenceChangeListener(appearanceListener)
        paint()
        binding.attach(surface)
        return true
    }

    fun render(reply: String, message: String, draft: PendingVoiceDraft? = null) {
        text = reply; status = message
        val wasReviewing = review.draftId != null
        val changed = binding.render(draft, editing)
        if (changed.newDraft) expanded = true
        if (draft == null && editing) releaseInputFocus()
        if (!lifecycle.visible) return
        if (!Settings.canDrawOverlays(app)) { hide(); return }
        if (changed.newDraft || draft == null) { resize(); paint() }
        else if (!pending) { pending = true; handler.postDelayed(repaint, 80) }
        if (wasReviewing && draft == null) scrollToPlaying()
    }

    private fun paint() {
        val movie = showingMovie()
        movieView?.visibility = if (movie) View.VISIBLE else View.GONE
        header?.visibility = if (movie) View.GONE else View.VISIBLE
        panel?.background = if (movie) null else GradientDrawable().apply {
            setColor(Color.argb(240, 35, 32, 37)); cornerRadius = dp(14).toFloat()
            setStroke(dp(1),Color.rgb(156,111,131))
        }
        movieView?.let { view ->
            val nextMovie = movieCaption(text, status, playingRange, playbackTracking)
            if (view.text.toString() != nextMovie) view.text = nextMovie
        }
        val reviewing = review.draftId != null
        header?.text = when {
            reviewing && expanded -> "待发送 · 点此收起"
            reviewing -> "待发送 · 点开确认"
            expanded && appearance.transparent -> "完整回复 · 点此返回字幕"
            expanded -> "AI 字幕 · 点此收起"
            else -> "字幕"
        }
        header?.contentDescription = if (expanded) "收起窗口；拖动可移动位置" else "展开窗口；拖动可移动位置"
        contentArea?.visibility = if (expanded && !showingMovie()) View.VISIBLE else View.GONE
        reviewArea?.visibility = if (reviewing) View.VISIBLE else View.GONE
        reviewLabel?.text = if (review.currentText.length > 4000)
            "待发送 · 请缩短到 4000 字以内" else "待发送 · 尚未提交给 Operit"
        sendButton?.isEnabled = review.canSend
        updateFollowButton()
        val next = text.ifBlank { status }
        if (movie) return
        val range = playingRange?.takeIf { it.first >= 0 && it.last < next.length }
        if (body?.text?.toString() == next && paintedRange == range) return
        val moved = paintedRange != range
        val followEnd = !playbackTracking && follow.enabled && !reviewing && scroll?.canScrollVertically(1) != true
        paintedRange = range
        body?.text = if (range == null) next else SpannableString(next).apply {
            setSpan(BackgroundColorSpan(Color.rgb(100, 62, 83)), range.first, range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (range != null && moved) scrollToPlaying()
        else if (followEnd) binding.followCaptionAtBottom()
    }

    fun setPlayback(range: IntRange?, tracking: Boolean = playbackTracking) {
        if (playingRange == range && playbackTracking == tracking) return
        playingRange = range; playbackTracking = tracking; paintRevision++; paint()
    }

    fun resetPlayback() {
        playingRange = null; paintedRange = null; playbackTracking = true; paintRevision++
        follow.reset(); scroll?.scrollTo(0, 0); paint()
    }

    private fun updateFollowButton() {
        followButton?.text = if (playbackTracking) "回到朗读" else "回到最新"
        followButton?.visibility = if (!follow.enabled && review.draftId == null &&
            (playingRange != null || (!playbackTracking && text.isNotEmpty())))
            View.VISIBLE else View.GONE
    }

    private fun scrollToPlaying() {
        if (showingMovie() || !expanded || !follow.enabled || review.draftId != null) return
        val range = playingRange ?: return
        val target = body ?: return
        val scroller = scroll ?: return
        val revision = paintRevision
        scroller.post { target.doOnLayout {
            if (body !== target || scroll !== scroller || revision != paintRevision ||
                !follow.enabled || !expanded || review.draftId != null || playingRange != range) return@doOnLayout
            val layout = target.layout ?: return@doOnLayout
            if (range.first >= target.text.length) return@doOnLayout
            val y = target.top + target.totalPaddingTop + layout.getLineTop(layout.getLineForOffset(range.first))
            scroller.smoothScrollTo(0, (y - dp(12)).coerceAtLeast(0))
        } }
    }

    private fun sizeAndClamp() {
        val layout = params ?: return
        if (showingMovie()) {
            val bounds = screen()
            val width = ((bounds.right - bounds.left) - 24f).coerceAtLeast(1f)
            // Include Android font scaling, three lines and outline padding in touch bounds.
            val scaled = appearance.sizeSp * app.resources.displayMetrics.scaledDensity / app.resources.displayMetrics.density
            val height = (scaled * 1.4f * 3 + 24f).coerceAtMost(bounds.bottom - bounds.top).coerceAtLeast(1f)
            val x = if (movieX.isFinite()) movieX else bounds.left + 12f
            val y = if (movieY.isFinite()) movieY else bounds.top + (bounds.bottom - bounds.top) * 0.73f
            layout.width = pixels(width); layout.height = pixels(height)
            layout.x = pixels(x.coerceIn(bounds.left, (bounds.right - width).coerceAtLeast(bounds.left)))
            layout.y = pixels(y.coerceIn(bounds.top, (bounds.bottom - height).coerceAtLeast(bounds.top)))
            movieView?.visibility = View.VISIBLE; header?.visibility = View.GONE
            contentArea?.visibility = View.GONE
            return
        }
        movieView?.visibility = View.GONE; header?.visibility = View.VISIBLE
        val visible = geometry.visible(screen(), expanded, review.draftId != null)
        layout.width = pixels(visible.width); layout.height = pixels(visible.height)
        contentArea?.layoutParams?.height = (layout.height - dp(44)).coerceAtLeast(1)
        contentArea?.visibility = if (expanded && !showingMovie()) View.VISIBLE else View.GONE
        layout.x = pixels(visible.x); layout.y = pixels(visible.y)
        contentArea?.requestLayout()
    }

    private fun resize() { reposition(); scroll?.requestLayout() }

    fun reposition() {
        sizeAndClamp()
        val root = panel ?: return
        val layout = params ?: return
        lifecycle.move { manager.updateViewLayout(root, layout) }
    }

    fun revealDraft(): Boolean {
        if (review.draftId == null || !show()) return false
        expanded = true; resize(); paint()
        binding.reveal()
        return true
    }

    private fun beginEditing() {
        if (review.draftId == null || editing) return
        val root = panel ?: return
        val view = editor ?: return
        val layout = params ?: return
        inputFocus.begin()
        resizeHandle?.visibility = View.GONE
        layout.flags = layout.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() and
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN.inv()
        layout.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        lifecycle.move { manager.updateViewLayout(root, layout) }
        view.requestFocus()
        view.post {
            if (editing && editor === view) {
                (app.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                binding.reveal()
            }
        }
    }

    private fun releaseInputFocus() {
        if (!inputFocus.release()) return
        imeBottomPixels = 0
        resizeHandle?.visibility = View.VISIBLE
        val view = editor
        if (view != null) {
            (app.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
        val layout = params ?: return
        layout.flags = layout.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        layout.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        reposition()
    }

    fun hide() { releaseInputFocus(); lifecycle.hide() }
}
