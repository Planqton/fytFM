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
        FM, AM, DAB, DAB_DEV
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

    /** Region-abhängige Bandgrenzen. Defaults entsprechen Western Europe;
     *  MainActivity überschreibt sie via [setBands] aus der aktiven WorldArea. */
    private var fmMin: Float = FM_MIN_FREQUENCY
    private var fmMax: Float = FM_MAX_FREQUENCY
    private var amMin: Float = AM_MIN_FREQUENCY
    private var amMax: Float = AM_MAX_FREQUENCY

    private val minFrequency: Float
        get() = if (radioMode == RadioMode.FM) fmMin else amMin

    private val maxFrequency: Float
        get() = if (radioMode == RadioMode.FM) fmMax else amMax

    /**
     * Setzt die regionsabhängigen Bandgrenzen. Bewirkt redraw + (falls die
     * aktuelle Frequenz außerhalb des neuen Bandes liegt) ein Coerce auf den
     * neuen Min-Wert.
     */
    fun setBands(fmMinMHz: Float, fmMaxMHz: Float, amMinKHz: Float, amMaxKHz: Float) {
        fmMin = fmMinMHz
        fmMax = fmMaxMHz
        amMin = amMinKHz
        amMax = amMaxKHz
        currentFrequency = currentFrequency.coerceIn(minFrequency, maxFrequency)
        invalidate()
    }

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

    /**
     * Override the indicator color (red bar over the frequency scale).
     * Wird von MainActivity aus aufgerufen, wenn der User im Accent-Color-
     * Editor eine andere Farbe wählt — sonst bleibt der Resource-Default.
     */
    fun setAccentColor(color: Int) {
        indicatorPaint.color = color
        invalidate()
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
        // Region-aware: Start/Ende werden aus der aktuellen Bandgrenze
        // abgeleitet (z.B. 76.0–95.0 MHz für Japan). Integer-Arithmetik um
        // Float-Präzisionsfehler zu vermeiden — freqInt = freq × 10.
        val startInt = Math.round(fmMin * 10f)
        val endInt = Math.round(fmMax * 10f)
        var freqInt = startInt
        while (freqInt <= endInt) {
            val freq = freqInt / 10.0f
            val x = paddingHorizontal + (freq - fmMin) / frequencyRange * availableWidth

            // Major tick: every 1.0 MHz
            val isMajorTick = freqInt % 10 == 0
            // Medium tick: every 0.5 MHz but not whole MHz and not first tick
            val isMediumTick = freqInt % 5 == 0 && !isMajorTick && freqInt != startInt

            val tickHeight = when {
                isMajorTick -> majorTickHeight
                isMediumTick -> mediumTickHeight
                else -> minorTickHeight
            }

            canvas.drawLine(x, scaleY - tickHeight, x, scaleY, scalePaint)

            // Labels every 2.0 MHz an ganzen MHz, plus letzter Tick.
            val isFullEven2Mhz = freqInt % 20 == 0
            val isLast = freqInt == endInt
            if (isFullEven2Mhz || isLast) {
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
        // Region-aware: Start/Ende aus aktuellem AM-Band. Ticks alle 10 kHz.
        val startInt = (amMin / 10f).toInt() * 10  // auf nächst niedrigere 10 abrunden
        val endInt = ((amMax / 10f).toInt() + 1) * 10  // auf nächst höhere 10 aufrunden
        var freq = startInt.toFloat()
        while (freq <= endInt) {
            if (freq < amMin) {
                freq += 10f
                continue
            }

            val x = paddingHorizontal + (freq - amMin) / frequencyRange * availableWidth

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

            // Labels every 100 kHz innerhalb des Bandes
            if (freqInt % 100 == 0 && freqInt >= amMin && freqInt <= amMax) {
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
                    FrequencySnap.snapFm(rawFrequency)
                } else {
                    FrequencySnap.snapAm(rawFrequency)
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
            // Default-Frequenz nur visuell setzen — der Caller (MainActivity
            // .setRadioMode → initializeNewRadioMode) setzt gleich danach via
            // setFrequency die echte zuletzt gespeicherte Frequenz. Würde
            // hier der Listener mit 98.4/522 feuern, würde die Default-Freq
            // sofort als „last freq" persistiert und der Sender-Restore wäre
            // nutzlos.
            currentFrequency = when (mode) {
                RadioMode.FM -> 98.4f
                RadioMode.AM -> 522f
                RadioMode.DAB -> 0f  // DAB hat keine klassische Frequenz
                RadioMode.DAB_DEV -> 0f
            }
            onModeChangeListener?.invoke(mode)
            invalidate()
        }
    }

    fun toggleMode() {
        val nextMode = when (radioMode) {
            RadioMode.FM -> RadioMode.AM
            RadioMode.AM -> RadioMode.DAB
            RadioMode.DAB -> RadioMode.FM
            RadioMode.DAB_DEV -> RadioMode.FM
        }
        setMode(nextMode)
    }

    fun setOnFrequencyChangeListener(listener: (Float) -> Unit) {
        onFrequencyChangeListener = listener
    }

    fun setOnModeChangeListener(listener: (RadioMode) -> Unit) {
        onModeChangeListener = listener
    }
}
