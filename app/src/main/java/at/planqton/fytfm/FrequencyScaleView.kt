package at.planqton.fytfm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat

class FrequencyScaleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class RadioMode {
        FM, AM
    }

    companion object {
        // FM: 87.5 - 108.0 MHz, step 0.1 MHz
        const val FM_MIN_FREQUENCY = 87.5f
        const val FM_MAX_FREQUENCY = 108.0f
        const val FM_FREQUENCY_STEP = 0.1f

        // AM: 522 - 1620 kHz, step 9 kHz
        const val AM_MIN_FREQUENCY = 522f
        const val AM_MAX_FREQUENCY = 1620f
        const val AM_FREQUENCY_STEP = 9f
    }

    private var radioMode = RadioMode.FM
    private var currentFrequency = 98.4f
    private var onFrequencyChangeListener: ((Float) -> Unit)? = null
    private var onModeChangeListener: ((RadioMode) -> Unit)? = null
    private val density = context.resources.displayMetrics.density

    private val minFrequency: Float
        get() = if (radioMode == RadioMode.FM) FM_MIN_FREQUENCY else AM_MIN_FREQUENCY

    private val maxFrequency: Float
        get() = if (radioMode == RadioMode.FM) FM_MAX_FREQUENCY else AM_MAX_FREQUENCY

    private val frequencyStep: Float
        get() = if (radioMode == RadioMode.FM) FM_FREQUENCY_STEP else AM_FREQUENCY_STEP

    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.radio_scale_line)
        strokeWidth = 1f * density
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.radio_text_secondary)
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.SANS_SERIF
    }

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.radio_indicator)
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.BUTT
    }

    private val paddingHorizontal get() = 20f * density
    private val majorTickHeight get() = 28f * density
    private val mediumTickHeight get() = 20f * density
    private val minorTickHeight get() = 12f * density
    private val textMarginTop get() = 10f * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val availableWidth = width - 2 * paddingHorizontal
        val scaleY = height * 0.35f

        val frequencyRange = maxFrequency - minFrequency

        if (radioMode == RadioMode.FM) {
            drawFMScale(canvas, availableWidth, scaleY, frequencyRange)
        } else {
            drawAMScale(canvas, availableWidth, scaleY, frequencyRange)
        }

        // Draw the red indicator
        val indicatorX = paddingHorizontal + (currentFrequency - minFrequency) / frequencyRange * availableWidth
        val indicatorTop = scaleY - majorTickHeight - 15f * density
        val indicatorBottom = scaleY + textMarginTop + textPaint.textSize + 10f * density
        canvas.drawLine(indicatorX, indicatorTop, indicatorX, indicatorBottom, indicatorPaint)
    }

    private fun drawFMScale(canvas: Canvas, availableWidth: Float, scaleY: Float, frequencyRange: Float) {
        // Zeichne Ticks mit Integer-Arithmetik um Float-Präzisionsfehler zu vermeiden
        // freqInt ist die Frequenz * 10 (875 = 87.5 MHz, 880 = 88.0 MHz, etc.)
        var freqInt = 875  // Start bei 87.5 MHz
        while (freqInt <= 1080) {  // Ende bei 108.0 MHz
            val freq = freqInt / 10.0f
            val x = paddingHorizontal + (freq - FM_MIN_FREQUENCY) / frequencyRange * availableWidth

            // Major tick: every 1.0 MHz (88.0, 89.0, 90.0...)
            val isMajorTick = freqInt % 10 == 0
            // Medium tick: every 0.5 MHz but not whole MHz and not 87.5 (first tick)
            val isMediumTick = freqInt % 5 == 0 && !isMajorTick && freqInt != 875

            val tickHeight = when {
                isMajorTick -> majorTickHeight
                isMediumTick -> mediumTickHeight
                else -> minorTickHeight
            }

            canvas.drawLine(x, scaleY - tickHeight, x, scaleY, scalePaint)

            // Labels every 2.0 MHz from 88 onwards, plus 108
            if ((freqInt % 20 == 0 && freqInt >= 880) || freqInt == 1080) {
                canvas.drawText(
                    (freqInt / 10).toString(),
                    x,
                    scaleY + textMarginTop + textPaint.textSize,
                    textPaint
                )
            }

            freqInt += 1  // 0.1 MHz Schritte
        }
    }

    private fun drawAMScale(canvas: Canvas, availableWidth: Float, scaleY: Float, frequencyRange: Float) {
        // Draw ticks every 10 kHz for visual density
        var freq = 520f  // Start slightly before min to align with round numbers
        while (freq <= AM_MAX_FREQUENCY + 10) {
            if (freq < AM_MIN_FREQUENCY) {
                freq += 10f
                continue
            }

            val x = paddingHorizontal + (freq - AM_MIN_FREQUENCY) / frequencyRange * availableWidth

            if (x < paddingHorizontal || x > width - paddingHorizontal) {
                freq += 10f
                continue
            }

            val freqInt = freq.toInt()
            // Major tick every 100 kHz
            val isMajorTick = freqInt % 100 == 0
            // Medium tick every 50 kHz
            val isMediumTick = freqInt % 50 == 0

            val tickHeight = when {
                isMajorTick -> majorTickHeight
                isMediumTick -> mediumTickHeight
                else -> minorTickHeight
            }

            canvas.drawLine(x, scaleY - tickHeight, x, scaleY, scalePaint)

            // Labels every 100 kHz starting from 600
            if (freqInt % 100 == 0 && freqInt >= 600 && freqInt <= 1600) {
                canvas.drawText(
                    freqInt.toString(),
                    x,
                    scaleY + textMarginTop + textPaint.textSize,
                    textPaint
                )
            }

            freq += 10f
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val availableWidth = width - 2 * paddingHorizontal
                val touchX = event.x.coerceIn(paddingHorizontal, width - paddingHorizontal)
                val frequencyRange = maxFrequency - minFrequency

                val rawFrequency = minFrequency + (touchX - paddingHorizontal) / availableWidth * frequencyRange

                val roundedFrequency = if (radioMode == RadioMode.FM) {
                    // Round to nearest 0.1 MHz with proper precision
                    val scaled = Math.round(rawFrequency * 10.0)
                    (scaled / 10.0f).coerceIn(minFrequency, maxFrequency)
                } else {
                    // Round to nearest 9 kHz step for AM
                    val steps = Math.round((rawFrequency - AM_MIN_FREQUENCY) / AM_FREQUENCY_STEP)
                    (AM_MIN_FREQUENCY + steps * AM_FREQUENCY_STEP).coerceIn(minFrequency, maxFrequency)
                }

                if (roundedFrequency != currentFrequency) {
                    setFrequency(roundedFrequency)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun setFrequency(frequency: Float) {
        currentFrequency = frequency.coerceIn(minFrequency, maxFrequency)
        onFrequencyChangeListener?.invoke(currentFrequency)
        invalidate()
    }

    /**
     * Setzt die Frequenz nur visuell (für Seek-Animation), ohne Listener zu triggern.
     */
    fun setFrequencyVisualOnly(frequency: Float) {
        currentFrequency = frequency.coerceIn(minFrequency, maxFrequency)
        invalidate()
    }

    fun getFrequency(): Float = currentFrequency

    fun getMode(): RadioMode = radioMode

    fun setMode(mode: RadioMode) {
        if (radioMode != mode) {
            radioMode = mode
            // Set default frequency for new mode
            currentFrequency = if (mode == RadioMode.FM) 98.4f else 522f
            onModeChangeListener?.invoke(mode)
            onFrequencyChangeListener?.invoke(currentFrequency)
            invalidate()
        }
    }

    fun toggleMode() {
        setMode(if (radioMode == RadioMode.FM) RadioMode.AM else RadioMode.FM)
    }

    fun setOnFrequencyChangeListener(listener: (Float) -> Unit) {
        onFrequencyChangeListener = listener
    }

    fun setOnModeChangeListener(listener: (RadioMode) -> Unit) {
        onModeChangeListener = listener
    }
}
