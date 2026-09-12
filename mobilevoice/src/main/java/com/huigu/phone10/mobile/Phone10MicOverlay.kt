package com.huigu.phone10.mobile

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.roundToInt

/** View only: never owns a Room, audio track, token or microphone operation. */
class Phone10MicOverlay(context: Context, private val onToggle: () -> Unit,
                        private val onVisible: (Boolean) -> Unit) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var button: Phone10MicView? = null
    private val avatars = Phone10AvatarStore(app)
    private val lifecycle = Phone10OverlayLifecycle { visible ->
        if (!visible) button = null
        onVisible(visible)
    }
    private var state = Phone10MicrophoneState()
    private val density = app.resources.displayMetrics.density

    fun show(): Boolean {
        if (!Settings.canDrawOverlays(app)) { hide(); return false }
        if (button != null && lifecycle.visible) { button?.avatar = avatars.load(); return true }
        val size = (48 * density).roundToInt()
        val params = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (app.resources.displayMetrics.widthPixels - size - 12 * density).roundToInt()
            y = (180 * density).roundToInt()
        }
        val view = Phone10MicView(app).apply {
            avatar = avatars.load()
            setOnClickListener { onToggle() }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                if (button === v) lifecycle.detached()
            }
        })
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        val gesture = Phone10OverlayGesture(ViewConfiguration.get(app).scaledTouchSlop.toFloat())
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    gesture.down(event.rawX, event.rawY)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (gesture.move(event.rawX, event.rawY)) {
                        params.x = (startX + dx).roundToInt().coerceIn(0, (app.resources.displayMetrics.widthPixels - size).coerceAtLeast(0))
                        params.y = (startY + dy).roundToInt().coerceIn(0, (app.resources.displayMetrics.heightPixels - size).coerceAtLeast(0))
                        lifecycle.move { manager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (gesture.up(event.rawX, event.rawY)) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { gesture.cancel(); true }
                else -> false
            }
        }
        try {
            manager.addView(view, params)
        } catch (_: SecurityException) {
            return false
        } catch (_: WindowManager.BadTokenException) {
            return false
        }
        button = view
        lifecycle.attached { manager.removeView(view) }
        update(state)
        return true
    }

    fun update(next: Phone10MicrophoneState) {
        val freshError = next.error != null && next.error != state.error
        state = next
        if (!Settings.canDrawOverlays(app)) { hide(); return }
        button?.render(next)
        if (freshError && lifecycle.visible) {
            Toast.makeText(app, "切换失败，麦克风仍${if (next.enabled) "开启" else "关闭"}，可再点一次", Toast.LENGTH_SHORT).show()
        }
    }

    fun refreshAvatar() {
        button?.avatar = avatars.load()
    }

    fun hide() {
        lifecycle.hide()
    }
}
