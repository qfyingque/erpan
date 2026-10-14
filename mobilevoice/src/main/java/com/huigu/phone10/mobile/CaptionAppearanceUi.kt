package com.huigu.phone10.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.widget.*
import kotlin.math.roundToInt

internal class CaptionAppearanceStore(context: Context) {
    val preferences = context.getSharedPreferences("caption-appearance", Context.MODE_PRIVATE)
    fun load(): CaptionAppearance = runCatching {
        CaptionAppearance.normalized(preferences.getFloat("size", 22f), preferences.getInt("color", -1),
            preferences.getBoolean("transparent", false))
    }.getOrDefault(CaptionAppearance())
    fun save(value: CaptionAppearance) {
        val safe = CaptionAppearance.normalized(value.sizeSp, value.color, value.transparent)
        preferences.edit().putFloat("size", safe.sizeSp).putInt("color", safe.color)
            .putBoolean("transparent", safe.transparent).apply()
    }
}

/** Draw the same shaped text layout twice; only the glyphs have a stroke/shadow. */
internal class MovieCaptionText(context: Context) : TextView(context) {
    override fun performClick(): Boolean { super.performClick(); return true }
    var appearance = CaptionAppearance()
        set(value) {
            field = value; textSize = value.sizeSp; setTextColor(value.color)
            requestLayout(); invalidate()
        }
    init {
        gravity = Gravity.CENTER_HORIZONTAL
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 3; ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = true
        val d = resources.displayMetrics.density
        setPadding((10*d).roundToInt(), (6*d).roundToInt(), (10*d).roundToInt(), (6*d).roundToInt())
        setLineSpacing(3*d, 1f)
        appearance = CaptionAppearance()
        setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
    }
    override fun onDraw(canvas: Canvas) {
        val shaped = layout ?: return
        val d = resources.displayMetrics.density
        val save = canvas.save()
        canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
        val oldColor = paint.color
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.25f*d
        paint.strokeJoin = Paint.Join.ROUND; paint.color = appearance.outline
        paint.setShadowLayer(1.8f*d, 0f, 0.7f*d, 0x70000000)
        shaped.draw(canvas)
        paint.clearShadowLayer(); paint.style = Paint.Style.FILL; paint.color = appearance.color
        shaped.draw(canvas)
        paint.color = oldColor
        canvas.restoreToCount(save)
    }
}

internal fun showCaptionAppearance(activity: Activity) {
    val store = CaptionAppearanceStore(activity)
    var value = store.load()
    fun dp(v: Int) = (v * activity.resources.displayMetrics.density).roundToInt()
    val column = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12))
    }
    val styles=RadioGroup(activity).apply { orientation=RadioGroup.HORIZONTAL }
    val boxed=RadioButton(activity).apply { id=android.view.View.generateViewId();text="原版带框" }
    val transparent=RadioButton(activity).apply { id=android.view.View.generateViewId();text="透明字幕" }
    styles.addView(boxed,LinearLayout.LayoutParams(0,dp(48),1f))
    styles.addView(transparent,LinearLayout.LayoutParams(0,dp(48),1f))
    styles.check(if(value.transparent) transparent.id else boxed.id)
    column.addView(styles)
    val sizeLabel = TextView(activity)
    column.addView(sizeLabel)
    val seek = SeekBar(activity).apply { max = 22; progress = (value.sizeSp - 14).roundToInt() }
    column.addView(seek)
    val previews = listOf(0xFFF5F2EE.toInt(), 0xFF24272D.toInt()).map { background ->
        MovieCaptionText(activity).apply {
            text = "先打开连接配置，\n我们一步一步来。"
            val sample=this
            column.addView(FrameLayout(activity).apply {
                setPadding(dp(8),dp(8),dp(8),dp(8));setBackgroundColor(background)
                addView(sample,FrameLayout.LayoutParams(-1,-2))
            }, LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(8)})
        }
    }
    val colorField = EditText(activity).apply {
        setSingleLine(true); hint = "自定义颜色，例如 #FFB7CF"
        setText("#%06X".format(value.color and 0xFFFFFF))
    }
    fun render() {
        sizeLabel.text = "字号 · ${value.sizeSp.roundToInt()}"
        previews.forEach {
            it.appearance = value
            it.background=if(value.transparent) null else android.graphics.drawable.GradientDrawable().apply {
                setColor(0xEF232025.toInt());cornerRadius=dp(12).toFloat();setStroke(dp(1),0xFF9C6F83.toInt())
            }
        }
        store.save(value)
    }
    val colors = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    for ((name, color) in listOf("白" to -1, "黑" to 0xFF202024.toInt(), "奶油" to 0xFFFFE6B3.toInt(),
            "粉" to 0xFFFFB7CF.toInt(), "蓝" to 0xFFB7DDFF.toInt())) {
        colors.addView(Button(activity).apply {
            text = name; contentDescription = "字幕颜色：$name"; minWidth = 0; minimumWidth = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                value = value.copy(color = color)
                colorField.setText("#%06X".format(color and 0xFFFFFF)); colorField.error = null; render()
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
    }
    column.addView(colors); column.addView(colorField)
    column.addView(Button(activity).apply {
        text = "应用自定义颜色"
        setOnClickListener {
            val color = CaptionAppearance.parseColor(colorField.text.toString())
            if (color == null) colorField.error = "请输入六位颜色值，例如 #FFB7CF"
            else { value = value.copy(color = color); colorField.error = null; render() }
        }
    })
    column.addView(TextView(activity).apply {
        text = "带框：保留全文、朗读跟随、收起与拖动缩放。透明：最多三行，点开看全文，拖动可移动。字号与颜色适用于两种样式，设置自动保存。"
        textSize = 12f
    })
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) { value = value.copy(sizeSp = (progress + 14).toFloat()); render() }
        }
        override fun onStartTrackingTouch(bar: SeekBar?) = Unit
        override fun onStopTrackingTouch(bar: SeekBar?) = Unit
    })
    styles.setOnCheckedChangeListener { _,checked -> value=value.copy(transparent=checked==transparent.id);render() }
    render()
    AlertDialog.Builder(activity).setTitle("字幕设置")
        .setView(ScrollView(activity).apply { addView(column) })
        .setPositiveButton("完成", null)
        .setNeutralButton("恢复默认") { _, _ -> store.save(CaptionAppearance()) }
        .show()
}
