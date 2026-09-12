package com.huigu.phone10.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.accessibility.AccessibilityEvent

/** Native drawing inside an exact 48dp window; shape follows confirmed capture state only. */
class Phone10MicView(context: Context) : View(context) {
    var avatar: Bitmap? = null
        set(value) { field = value; invalidate() }
    private var microphone = Phone10MicrophoneState()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val defaultAvatar = context.getDrawable(R.drawable.ic_mobile_voice)

    init { isClickable = true; isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    fun render(next: Phone10MicrophoneState) {
        microphone = next
        // Keep the drag listener available during a switch; performClick prevents duplicate toggles.
        contentDescription = when {
            next.changing -> "麦克风切换中，当前${if (next.enabled) "已开启" else "已关闭"}"
            next.error != null -> "切换失败，麦克风仍${if (next.enabled) "开启，点一下关闭" else "关闭，点一下开启"}"
            next.enabled -> "麦克风已开启，点一下关闭，可拖动"
            else -> "麦克风已关闭，点一下开启，可拖动"
        }
        invalidate()
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name

    override fun performClick(): Boolean {
        if (microphone.changing) return false
        return super.performClick()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val unit = width / 48f
        val cx = width / 2f
        val cy = height / 2f
        paint.style = Paint.Style.FILL
        if (!microphone.enabled) {
            paint.color = Color.argb(125, 65, 70, 78)
            canvas.drawRoundRect(cx - 14 * unit, cy - 3 * unit, cx + 14 * unit, cy + 3 * unit, 3 * unit, 3 * unit, paint)
            paint.color = Color.argb(95, 255, 255, 255)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = unit
            canvas.drawRoundRect(cx - 14 * unit, cy - 3 * unit, cx + 14 * unit, cy + 3 * unit, 3 * unit, 3 * unit, paint)
        } else {
            val radius = 21 * unit
            paint.color = Color.rgb(224, 228, 232)
            canvas.drawCircle(cx, cy, radius, paint)
            val picture = avatar
            if (picture != null) {
                canvas.save()
                canvas.clipPath(Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) })
                val scale = radius * 2 / minOf(picture.width, picture.height)
                val w = picture.width * scale; val h = picture.height * scale
                canvas.drawBitmap(picture, null, RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), paint)
                canvas.restore()
            } else {
                canvas.save()
                canvas.clipPath(Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) })
                defaultAvatar?.setBounds((cx - radius).toInt(), (cy - radius).toInt(),
                    (cx + radius).toInt(), (cy + radius).toInt())
                defaultAvatar?.draw(canvas)
                canvas.restore()
            }
            paint.color = Color.argb(235, 255, 255, 255)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * unit
            canvas.drawCircle(cx, cy, radius, paint)
        }
        if (microphone.changing) {
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(117, 131, 148)
            canvas.drawCircle(cx, cy + 15 * unit, 2 * unit, paint)
        }
        if (microphone.error != null) {
            paint.style = Paint.Style.FILL; paint.color = Color.rgb(190, 75, 64)
            canvas.drawCircle(cx + 16 * unit, cy - 15 * unit, 3 * unit, paint)
        }
    }
}
