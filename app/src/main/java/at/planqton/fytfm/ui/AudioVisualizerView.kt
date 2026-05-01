package at.planqton.fytfm.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.media.audiofx.Visualizer
import android.util.AttributeSet
import android.util.Log
import android.view.View
import at.planqton.fytfm.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Custom View for audio visualization in DAB List Mode.
 * Supports multiple styles: Bars, Waveform, Circular.
 */
class AudioVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "AudioVisualizerView"
        const val STYLE_BARS = 0
        const val STYLE_WAVEFORM = 1
        const val STYLE_CIRCULAR = 2
        const val STYLE_MIRROR_BARS = 3
        const val STYLE_DOTS = 4
        const val STYLE_SPECTRUM = 5
        const val STYLE_BLOCKS = 6
        const val STYLE_SYMMETRY = 7
        const val STYLE_PULSE = 8
        const val STYLE_DIAMONDS = 9
        const val STYLE_WAVE_BARS = 10
        const val STYLE_FIREFLY = 11
    }

    private var visualizer: Visualizer? = null
    private var currentStyle: Int = STYLE_BARS
    private var isVisualizerEnabled = false

    // FFT data for bars
    private var fftData: ByteArray? = null
    private var smoothedMagnitudes = FloatArray(32)
    private val targetMagnitudes = FloatArray(32)

    // Waveform data
    private var waveformData: ByteArray? = null

    // Paint objects
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.radio_accent)
        style = Paint.Style.FILL
    }

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.radio_accent)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.radio_accent)
        style = Paint.Style.FILL
    }

    /**
     * Override the accent color used by all three paints. Falls der User die
     * App-Akzentfarbe ändert, wird das hier per Live-Refresh übernommen.
     */
    fun setAccentColor(color: Int) {
        barPaint.color = color
        wavePaint.color = color
        circlePaint.color = color
        invalidate()
    }

    private val wavePath = Path()

    // Animation smoothing
    private val smoothingFactor = 0.3f
    private val falloffFactor = 0.85f

    fun setStyle(style: Int) {
        currentStyle = style
        invalidate()
    }

    fun getStyle(): Int = currentStyle

    /**
     * Attach visualizer to an audio session.
     * @param audioSessionId The audio session ID from AudioTrack
     */
    fun setAudioSessionId(audioSessionId: Int) {
        if (audioSessionId <= 0) {
            Log.w(TAG, "Invalid audio session ID: $audioSessionId")
            return
        }

        release()

        try {
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1] // Max capture size
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) {
                        waveformData = waveform
                        if (currentStyle == STYLE_WAVEFORM) {
                            postInvalidate()
                        }
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        fftData = fft
                        processFFTData()
                        // All styles except WAVEFORM use FFT data
                        if (currentStyle != STYLE_WAVEFORM) {
                            postInvalidate()
                        }
                    }
                }, Visualizer.getMaxCaptureRate() / 2, true, true)
                enabled = true
            }
            isVisualizerEnabled = true
            Log.i(TAG, "Visualizer attached to session $audioSessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create Visualizer: ${e.message}", e)
        }
    }

    private fun processFFTData() {
        val fft = fftData ?: return
        val numBands = smoothedMagnitudes.size

        // Convert FFT data to magnitude values
        for (i in 0 until numBands) {
            val realIndex = i * 2
            val imagIndex = realIndex + 1
            if (realIndex < fft.size && imagIndex < fft.size) {
                val real = fft[realIndex].toFloat()
                val imag = fft[imagIndex].toFloat()
                val magnitude = kotlin.math.sqrt(real * real + imag * imag)
                // Normalize and apply some boost for visual effect
                targetMagnitudes[i] = (magnitude / 128f).coerceIn(0f, 1f)
            }
        }

        // Smooth animation - rise quickly, fall slowly
        for (i in smoothedMagnitudes.indices) {
            if (targetMagnitudes[i] > smoothedMagnitudes[i]) {
                smoothedMagnitudes[i] += (targetMagnitudes[i] - smoothedMagnitudes[i]) * smoothingFactor
            } else {
                smoothedMagnitudes[i] *= falloffFactor
            }
        }
    }

    fun release() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
            visualizer = null
            isVisualizerEnabled = false
            Log.i(TAG, "Visualizer released")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing visualizer: ${e.message}")
        }
    }

    fun isActive(): Boolean = isVisualizerEnabled && visualizer != null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!isVisualizerEnabled) {
            // Draw placeholder when not active
            drawPlaceholder(canvas)
            return
        }

        when (currentStyle) {
            STYLE_BARS -> drawBars(canvas)
            STYLE_WAVEFORM -> drawWaveform(canvas)
            STYLE_CIRCULAR -> drawCircular(canvas)
            STYLE_MIRROR_BARS -> drawMirrorBars(canvas)
            STYLE_DOTS -> drawDots(canvas)
            STYLE_SPECTRUM -> drawSpectrum(canvas)
            STYLE_BLOCKS -> drawBlocks(canvas)
            STYLE_SYMMETRY -> drawSymmetry(canvas)
            STYLE_PULSE -> drawPulse(canvas)
            STYLE_DIAMONDS -> drawDiamonds(canvas)
            STYLE_WAVE_BARS -> drawWaveBars(canvas)
            STYLE_FIREFLY -> drawFirefly(canvas)
        }
    }

    private fun drawPlaceholder(canvas: Canvas) {
        // Draw faded bars as placeholder
        val numBars = 16
        val barWidth = width.toFloat() / (numBars * 2)
        val spacing = barWidth
        val maxHeight = height * 0.3f

        barPaint.alpha = 40
        for (i in 0 until numBars) {
            val x = i * (barWidth + spacing) + spacing
            val barHeight = maxHeight * (0.3f + (i % 3) * 0.2f)
            canvas.drawRoundRect(
                x,
                height - barHeight,
                x + barWidth,
                height.toFloat(),
                barWidth / 2,
                barWidth / 2,
                barPaint
            )
        }
        barPaint.alpha = 255
    }

    private fun drawBars(canvas: Canvas) {
        val numBars = smoothedMagnitudes.size
        val totalWidth = width.toFloat()
        val barWidth = totalWidth / (numBars * 1.5f)
        val spacing = barWidth * 0.5f
        val maxHeight = height * 0.9f
        val minHeight = height * 0.05f

        for (i in 0 until numBars) {
            val x = i * (barWidth + spacing) + spacing / 2
            val magnitude = smoothedMagnitudes[i]
            val barHeight = minHeight + magnitude * (maxHeight - minHeight)

            // Gradient effect - stronger bars are brighter
            barPaint.alpha = (150 + magnitude * 105).toInt().coerceIn(150, 255)

            canvas.drawRoundRect(
                x,
                height - barHeight,
                x + barWidth,
                height.toFloat(),
                barWidth / 3,
                barWidth / 3,
                barPaint
            )
        }
    }

    private fun drawWaveform(canvas: Canvas) {
        val waveform = waveformData ?: return
        if (waveform.isEmpty()) return

        wavePath.reset()
        val centerY = height / 2f
        val amplitude = height * 0.4f
        val step = width.toFloat() / waveform.size

        wavePath.moveTo(0f, centerY)
        for (i in waveform.indices) {
            val x = i * step
            // Convert byte (-128 to 127) to normalized value (-1 to 1)
            val normalized = (waveform[i].toInt() and 0xFF) / 128f - 1f
            val y = centerY - normalized * amplitude
            wavePath.lineTo(x, y)
        }

        canvas.drawPath(wavePath, wavePaint)
    }

    private fun drawCircular(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        val baseRadius = min(width, height) * 0.25f
        val maxBarLength = min(width, height) * 0.35f

        val numBars = smoothedMagnitudes.size
        val angleStep = (2 * Math.PI / numBars).toFloat()

        for (i in 0 until numBars) {
            val angle = i * angleStep - Math.PI.toFloat() / 2 // Start from top
            val magnitude = smoothedMagnitudes[i]
            val barLength = magnitude * maxBarLength

            // Inner point (on base circle)
            val innerX = centerX + cos(angle) * baseRadius
            val innerY = centerY + sin(angle) * baseRadius

            // Outer point (extended by magnitude)
            val outerX = centerX + cos(angle) * (baseRadius + barLength)
            val outerY = centerY + sin(angle) * (baseRadius + barLength)

            // Draw line from inner to outer
            circlePaint.alpha = (150 + magnitude * 105).toInt().coerceIn(150, 255)
            circlePaint.strokeWidth = 4f
            circlePaint.style = Paint.Style.STROKE
            canvas.drawLine(innerX, innerY, outerX, outerY, circlePaint)

            // Draw dot at the end
            circlePaint.style = Paint.Style.FILL
            canvas.drawCircle(outerX, outerY, 3f + magnitude * 4f, circlePaint)
        }

        // Draw center circle
        circlePaint.alpha = 80
        circlePaint.style = Paint.Style.FILL
        canvas.drawCircle(centerX, centerY, baseRadius * 0.3f, circlePaint)
    }

    private fun drawMirrorBars(canvas: Canvas) {
        val numBars = smoothedMagnitudes.size
        val totalWidth = width.toFloat()
        val barWidth = totalWidth / (numBars * 1.5f)
        val spacing = barWidth * 0.5f
        val centerY = height / 2f
        val maxHeight = height * 0.45f
        val minHeight = height * 0.02f

        for (i in 0 until numBars) {
            val x = i * (barWidth + spacing) + spacing / 2
            val magnitude = smoothedMagnitudes[i]
            val barHeight = minHeight + magnitude * (maxHeight - minHeight)

            barPaint.alpha = (150 + magnitude * 105).toInt().coerceIn(150, 255)

            // Top bar (mirrored)
            canvas.drawRoundRect(
                x,
                centerY - barHeight,
                x + barWidth,
                centerY - 2f,
                barWidth / 3,
                barWidth / 3,
                barPaint
            )

            // Bottom bar
            canvas.drawRoundRect(
                x,
                centerY + 2f,
                x + barWidth,
                centerY + barHeight,
                barWidth / 3,
                barWidth / 3,
                barPaint
            )
        }
    }

    private fun drawDots(canvas: Canvas) {
        val numDots = smoothedMagnitudes.size
        val totalWidth = width.toFloat()
        val spacing = totalWidth / numDots
        val maxRadius = min(width, height) * 0.08f
        val minRadius = 3f

        for (i in 0 until numDots) {
            val x = i * spacing + spacing / 2
            val magnitude = smoothedMagnitudes[i]
            val radius = minRadius + magnitude * (maxRadius - minRadius)

            // Vertical position based on magnitude
            val y = height - (height * 0.1f) - magnitude * (height * 0.7f)

            circlePaint.alpha = (150 + magnitude * 105).toInt().coerceIn(150, 255)
            circlePaint.style = Paint.Style.FILL
            canvas.drawCircle(x, y, radius, circlePaint)

            // Add glow effect for strong magnitudes
            if (magnitude > 0.5f) {
                circlePaint.alpha = ((magnitude - 0.5f) * 100).toInt().coerceIn(0, 60)
                canvas.drawCircle(x, y, radius * 1.5f, circlePaint)
            }
        }
    }

    private fun drawSpectrum(canvas: Canvas) {
        val numPoints = smoothedMagnitudes.size
        if (numPoints < 2) return

        wavePath.reset()
        val stepX = width.toFloat() / (numPoints - 1)
        val maxY = height * 0.9f
        val minY = height * 0.1f

        // Start from bottom left
        wavePath.moveTo(0f, height.toFloat())

        // Draw spectrum line
        for (i in 0 until numPoints) {
            val x = i * stepX
            val magnitude = smoothedMagnitudes[i]
            val y = height - (minY + magnitude * (maxY - minY))

            if (i == 0) {
                wavePath.lineTo(x, y)
            } else {
                // Smooth curve between points
                val prevX = (i - 1) * stepX
                val prevMagnitude = smoothedMagnitudes[i - 1]
                val prevY = height - (minY + prevMagnitude * (maxY - minY))
                val midX = (prevX + x) / 2
                wavePath.quadTo(prevX, prevY, midX, (prevY + y) / 2)
                if (i == numPoints - 1) {
                    wavePath.lineTo(x, y)
                }
            }
        }

        // Close path to bottom
        wavePath.lineTo(width.toFloat(), height.toFloat())
        wavePath.close()

        // Fill with gradient effect
        barPaint.alpha = 80
        canvas.drawPath(wavePath, barPaint)

        // Draw outline
        wavePaint.strokeWidth = 3f
        wavePaint.alpha = 255

        // Redraw just the top line
        wavePath.reset()
        for (i in 0 until numPoints) {
            val x = i * stepX
            val magnitude = smoothedMagnitudes[i]
            val y = height - (minY + magnitude * (maxY - minY))
            if (i == 0) {
                wavePath.moveTo(x, y)
            } else {
                wavePath.lineTo(x, y)
            }
        }
        canvas.drawPath(wavePath, wavePaint)
    }

    private fun drawBlocks(canvas: Canvas) {
        val cols = 16
        val rows = 8
        val blockWidth = width.toFloat() / cols
        val blockHeight = height.toFloat() / rows
        val gap = 2f

        for (col in 0 until cols) {
            val magIndex = (col * smoothedMagnitudes.size / cols).coerceIn(0, smoothedMagnitudes.size - 1)
            val magnitude = smoothedMagnitudes[magIndex]
            val activeRows = (magnitude * rows).toInt()

            for (row in 0 until rows) {
                val isActive = row < activeRows
                val y = height - (row + 1) * blockHeight

                if (isActive) {
                    // Color gradient from bottom (green) to top (red)
                    val ratio = row.toFloat() / rows
                    barPaint.alpha = (180 + ratio * 75).toInt()
                } else {
                    barPaint.alpha = 30
                }

                canvas.drawRect(
                    col * blockWidth + gap,
                    y + gap,
                    (col + 1) * blockWidth - gap,
                    y + blockHeight - gap,
                    barPaint
                )
            }
        }
    }

    private fun drawSymmetry(canvas: Canvas) {
        val numBars = smoothedMagnitudes.size / 2
        val centerX = width / 2f
        val barWidth = centerX / (numBars * 1.2f)
        val spacing = barWidth * 0.2f
        val maxHeight = height * 0.9f
        val minHeight = height * 0.05f

        for (i in 0 until numBars) {
            val magnitude = smoothedMagnitudes[i]
            val barHeight = minHeight + magnitude * (maxHeight - minHeight)
            barPaint.alpha = (150 + magnitude * 105).toInt().coerceIn(150, 255)

            // Right side
            val rightX = centerX + i * (barWidth + spacing) + spacing
            canvas.drawRoundRect(
                rightX, height - barHeight, rightX + barWidth, height.toFloat(),
                barWidth / 3, barWidth / 3, barPaint
            )

            // Left side (mirrored)
            val leftX = centerX - (i + 1) * (barWidth + spacing)
            canvas.drawRoundRect(
                leftX, height - barHeight, leftX + barWidth, height.toFloat(),
                barWidth / 3, barWidth / 3, barPaint
            )
        }
    }

    private fun drawPulse(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        val maxRadius = min(width, height) * 0.45f

        // Calculate average magnitude for pulse effect
        var avgMagnitude = 0f
        for (mag in smoothedMagnitudes) {
            avgMagnitude += mag
        }
        avgMagnitude /= smoothedMagnitudes.size

        // Draw multiple pulsing rings
        val numRings = 5
        for (ring in 0 until numRings) {
            val ringRatio = ring.toFloat() / numRings
            val baseRadius = maxRadius * ringRatio
            val pulseAmount = avgMagnitude * maxRadius * 0.2f * (1f - ringRatio)
            val radius = baseRadius + pulseAmount

            circlePaint.style = Paint.Style.STROKE
            circlePaint.strokeWidth = 3f + avgMagnitude * 5f
            circlePaint.alpha = ((1f - ringRatio) * 200 * (0.5f + avgMagnitude * 0.5f)).toInt().coerceIn(30, 255)

            canvas.drawCircle(centerX, centerY, radius, circlePaint)
        }

        // Center dot
        circlePaint.style = Paint.Style.FILL
        circlePaint.alpha = 255
        canvas.drawCircle(centerX, centerY, 5f + avgMagnitude * 15f, circlePaint)
    }

    private fun drawDiamonds(canvas: Canvas) {
        val numDiamonds = smoothedMagnitudes.size
        val spacing = width.toFloat() / numDiamonds
        val maxSize = min(spacing * 0.8f, height * 0.4f)

        for (i in 0 until numDiamonds) {
            val x = i * spacing + spacing / 2
            val magnitude = smoothedMagnitudes[i]
            val size = maxSize * 0.2f + magnitude * maxSize * 0.8f
            val y = height - size / 2 - height * 0.05f

            barPaint.alpha = (150 + magnitude * 105).toInt().coerceIn(150, 255)

            // Draw diamond shape
            wavePath.reset()
            wavePath.moveTo(x, y - size / 2)  // Top
            wavePath.lineTo(x + size / 2, y)   // Right
            wavePath.lineTo(x, y + size / 2)   // Bottom
            wavePath.lineTo(x - size / 2, y)   // Left
            wavePath.close()

            canvas.drawPath(wavePath, barPaint)
        }
    }

    private fun drawWaveBars(canvas: Canvas) {
        val numBars = smoothedMagnitudes.size
        val totalWidth = width.toFloat()
        val barWidth = totalWidth / (numBars * 1.3f)
        val spacing = barWidth * 0.3f
        val maxHeight = height * 0.85f
        val minHeight = height * 0.08f

        // Calculate wave offset based on time for animation
        val time = System.currentTimeMillis() / 200f

        for (i in 0 until numBars) {
            val x = i * (barWidth + spacing) + spacing / 2
            val magnitude = smoothedMagnitudes[i]

            // Add wave motion
            val waveOffset = sin(time + i * 0.3f).toFloat() * height * 0.05f * magnitude
            val barHeight = minHeight + magnitude * (maxHeight - minHeight)

            barPaint.alpha = (150 + magnitude * 105).toInt().coerceIn(150, 255)

            canvas.drawRoundRect(
                x,
                height - barHeight + waveOffset,
                x + barWidth,
                height.toFloat() + waveOffset,
                barWidth / 2,
                barWidth / 2,
                barPaint
            )
        }
    }

    private fun drawFirefly(canvas: Canvas) {
        val numParticles = smoothedMagnitudes.size * 2
        val time = System.currentTimeMillis() / 1000f

        for (i in 0 until numParticles) {
            val magIndex = i % smoothedMagnitudes.size
            val magnitude = smoothedMagnitudes[magIndex]

            if (magnitude < 0.1f) continue

            // Pseudo-random position based on index and time
            val seed = i * 1337
            val baseX = ((seed * 7) % width).toFloat()
            val baseY = ((seed * 13) % height).toFloat()

            // Floating motion
            val floatX = sin(time * 2f + seed) * 20f * magnitude
            val floatY = cos(time * 1.5f + seed * 0.7f) * 15f * magnitude

            val x = (baseX + floatX).coerceIn(0f, width.toFloat())
            val y = (baseY + floatY).coerceIn(0f, height.toFloat())

            val radius = 2f + magnitude * 6f

            // Glow effect
            circlePaint.style = Paint.Style.FILL
            circlePaint.alpha = (magnitude * 60).toInt().coerceIn(20, 80)
            canvas.drawCircle(x, y, radius * 3f, circlePaint)

            circlePaint.alpha = (magnitude * 150 + 100).toInt().coerceIn(100, 255)
            canvas.drawCircle(x, y, radius, circlePaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }
}
