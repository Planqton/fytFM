package at.planqton.fytfm.ui.settings

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import at.planqton.fytfm.R

/**
 * Material-ähnlicher HSV-Color-Picker:
 *  - Saturation/Value-Quadrat (2D-Touch)
 *  - Hue-Slider (horizontal)
 *  - Hex-Input-Feld (#RRGGBB)
 *  - Live-Preview
 */
object HsvColorPicker {

    fun show(context: Context, initial: Int, onPicked: (Int) -> Unit) {
        val pad = dp(context, 16)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val preview = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 8).toFloat()
                setColor(initial)
                setStroke(dp(context, 1), 0x40000000)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 48)
            ).apply { bottomMargin = dp(context, 12) }
        }
        container.addView(preview)

        val svSquare = SatValSquareView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 200)
            )
        }
        container.addView(svSquare)

        val hueSlider = HueSliderView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 28)
            ).apply { topMargin = dp(context, 12) }
        }
        container.addView(hueSlider)

        val hexRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 12) }
        }
        val hexLabel = TextView(context).apply {
            text = "#"
            textSize = 16f
        }
        val hexInput = EditText(context).apply {
            filters = arrayOf(InputFilter.LengthFilter(6), InputFilter.AllCaps())
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        hexRow.addView(hexLabel)
        hexRow.addView(hexInput)
        container.addView(hexRow)

        // Init from initial color
        val hsv = FloatArray(3)
        Color.colorToHSV(initial, hsv)
        var currentHue = hsv[0]
        var currentSat = hsv[1]
        var currentVal = hsv[2]
        var currentColor = initial
        hueSlider.hue = currentHue
        svSquare.hue = currentHue
        svSquare.saturation = currentSat
        svSquare.value = currentVal
        hexInput.setText(String.format("%06X", currentColor and 0xFFFFFF))

        var ignoreHexUpdate = false

        fun applyChange() {
            currentColor = Color.HSVToColor(floatArrayOf(currentHue, currentSat, currentVal))
            (preview.background as GradientDrawable).setColor(currentColor)
            ignoreHexUpdate = true
            hexInput.setText(String.format("%06X", currentColor and 0xFFFFFF))
            ignoreHexUpdate = false
        }

        hueSlider.onHueChanged = { h ->
            currentHue = h
            svSquare.hue = h
            applyChange()
        }
        svSquare.onChanged = { s, v ->
            currentSat = s
            currentVal = v
            applyChange()
        }
        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (ignoreHexUpdate) return
                val txt = s?.toString() ?: return
                if (txt.length != 6) return
                val parsed = try { Integer.parseInt(txt, 16) } catch (e: Exception) { return }
                val color = 0xFF000000.toInt() or (parsed and 0xFFFFFF)
                Color.colorToHSV(color, hsv)
                currentHue = hsv[0]
                currentSat = hsv[1]
                currentVal = hsv[2]
                hueSlider.hue = currentHue
                svSquare.hue = currentHue
                svSquare.saturation = currentSat
                svSquare.value = currentVal
                currentColor = color
                (preview.background as GradientDrawable).setColor(currentColor)
            }
        })

        AlertDialog.Builder(context)
            .setTitle(R.string.accent_color_custom_picker_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ -> onPicked(currentColor) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dp(ctx: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            ctx.resources.displayMetrics
        ).toInt()

    /** Saturation/Value-Quadrat (Hue fix). Touch updates s/v. */
    class SatValSquareView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null
    ) : View(context, attrs) {

        var hue: Float = 0f
            set(value) {
                field = value
                rebuildShaders()
                invalidate()
            }
        var saturation: Float = 1f
        var value: Float = 1f
        var onChanged: ((sat: Float, value: Float) -> Unit)? = null

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = dp(context, 2).toFloat()
        }
        private val markerOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x80000000.toInt()
            strokeWidth = dp(context, 1).toFloat()
        }
        private var hueShader: Shader? = null
        private var darkShader: Shader? = null

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            rebuildShaders()
        }

        private fun rebuildShaders() {
            if (width == 0 || height == 0) return
            val pureHue = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
            hueShader = LinearGradient(
                0f, 0f, width.toFloat(), 0f,
                Color.WHITE, pureHue, Shader.TileMode.CLAMP
            )
            darkShader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP
            )
        }

        override fun onDraw(canvas: Canvas) {
            paint.shader = hueShader
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = darkShader
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            val mx = saturation * width
            val my = (1f - value) * height
            val r = dp(context, 7).toFloat()
            canvas.drawCircle(mx, my, r, markerOuterPaint)
            canvas.drawCircle(mx, my, r, markerPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    saturation = (event.x / width).coerceIn(0f, 1f)
                    value = 1f - (event.y / height).coerceIn(0f, 1f)
                    onChanged?.invoke(saturation, value)
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun dp(ctx: Context, v: Int): Int =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                v.toFloat(),
                ctx.resources.displayMetrics
            ).toInt()
    }

    /** Horizontaler Hue-Slider (0..360°). */
    class HueSliderView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null
    ) : View(context, attrs) {

        var hue: Float = 0f
            set(value) { field = value; invalidate() }
        var onHueChanged: ((Float) -> Unit)? = null

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = dp(context, 2).toFloat()
        }
        private val markerOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x80000000.toInt()
            strokeWidth = dp(context, 1).toFloat()
        }
        private var shader: Shader? = null

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val colors = intArrayOf(
                Color.RED, Color.YELLOW, Color.GREEN,
                Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
            )
            shader = LinearGradient(0f, 0f, w.toFloat(), 0f, colors, null, Shader.TileMode.CLAMP)
        }

        override fun onDraw(canvas: Canvas) {
            paint.shader = shader
            val r = dp(context, 6).toFloat()
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
            val x = (hue / 360f) * width
            val cy = height / 2f
            val mr = (height / 2f) - dp(context, 2).toFloat()
            canvas.drawCircle(x, cy, mr, markerOuterPaint)
            canvas.drawCircle(x, cy, mr, markerPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    hue = ((event.x / width).coerceIn(0f, 1f)) * 360f
                    onHueChanged?.invoke(hue)
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun dp(ctx: Context, v: Int): Int =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                v.toFloat(),
                ctx.resources.displayMetrics
            ).toInt()
    }
}
