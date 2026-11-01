/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 *
 * Copyright (c) 2023-2025 Yan-Bin Diau, Johnson Sun
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.kairosvector.flowchat.keyboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.kairosvector.flowchat.R
import kotlin.random.Random

// Fork change (fork6): small custom views for the redesigned keyboard.
// They only draw; all state comes from WhisperKeyboard.

private fun View.dp(value: Float): Float = value * resources.displayMetrics.density

/**
 * 12 vertical bars (4dp wide, 3dp apart, 8-52dp tall) that follow the
 * microphone level. Newest level enters on the side next to the mic and
 * moves outwards, so a left and a right instance mirror each other.
 */
class WaveformView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    /** true: newest bar is the right-most one (the view sits left of the mic). */
    var newestOnRight: Boolean = true

    private val levels = FloatArray(BAR_COUNT)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rect = RectF()

    fun pushLevel(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        if (newestOnRight) {
            System.arraycopy(levels, 1, levels, 0, BAR_COUNT - 1)
            levels[BAR_COUNT - 1] = clamped
        } else {
            System.arraycopy(levels, 0, levels, 1, BAR_COUNT - 1)
            levels[0] = clamped
        }
        invalidate()
    }

    fun clear() {
        levels.fill(0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = ContextCompat.getColor(context, R.color.kb_recording)
        val barW = dp(BAR_WIDTH_DP)
        val gap = dp(BAR_GAP_DP)
        val minH = dp(MIN_HEIGHT_DP)
        val maxH = dp(MAX_HEIGHT_DP)
        val total = BAR_COUNT * barW + (BAR_COUNT - 1) * gap
        var x = (width - total) / 2f
        val cy = height / 2f
        for (i in 0 until BAR_COUNT) {
            val h = minH + (maxH - minH) * levels[i]
            rect.set(x, cy - h / 2f, x + barW, cy + h / 2f)
            // Older bars fade a little so the motion reads as a trail.
            val age = if (newestOnRight) BAR_COUNT - 1 - i else i
            paint.alpha = (255 * (1f - age * 0.045f)).toInt().coerceIn(90, 255)
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
            x += barW + gap
        }
    }

    private companion object {
        const val BAR_COUNT = 12
        const val BAR_WIDTH_DP = 4f
        const val BAR_GAP_DP = 3f
        const val MIN_HEIGHT_DP = 8f
        const val MAX_HEIGHT_DP = 52f
    }
}

/**
 * Two concentric rings around the stop key (+9dp / +18dp from the key
 * edge, 3dp / 2dp stroke, 35% / 15% alpha) that expand and fade every
 * 1.2s while recording.
 */
class PulseRingView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    /** Radius of the key the rings surround, in dp. */
    var keyRadiusDp: Float = 42f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private var fraction = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = PULSE_DURATION_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            fraction = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimation()
    }

    // isShown also covers a hidden ancestor and a hidden window, which
    // the view's own visibility flag does not.
    private fun updateAnimation() {
        if (isShown) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val color = ContextCompat.getColor(context, R.color.kb_recording)
        val grow = 1f + GROW * fraction
        val fade = 1f - fraction
        drawRing(canvas, cx, cy, dp(keyRadiusDp + 9f) * grow, dp(3f), color, 0.35f * fade)
        drawRing(canvas, cx, cy, dp(keyRadiusDp + 18f) * grow, dp(2f), color, 0.15f * fade)
    }

    private fun drawRing(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        stroke: Float, color: Int, alpha: Float
    ) {
        paint.strokeWidth = stroke
        paint.color = color
        paint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, radius, paint)
    }

    private companion object {
        const val PULSE_DURATION_MS = 1200L
        // Rings scale 1.0 -> 1.25 while fading out (DESIGN_NOTES.md).
        const val GROW = 0.25f
    }
}

/**
 * Indeterminate ring drawn outside the processing key (6dp out, 4dp
 * stroke): a faint full track plus a rotating arc.
 */
class SpinnerRingView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    /** Radius of the key the ring surrounds, in dp. */
    var keyRadiusDp: Float = 48f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()
    private var angle = 0f
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = SPIN_DURATION_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimation()
    }

    // isShown also covers a hidden ancestor and a hidden window, which
    // the view's own visibility flag does not.
    private fun updateAnimation() {
        if (isShown) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = dp(keyRadiusDp + 6f)
        oval.set(cx - r, cy - r, cx + r, cy + r)
        paint.strokeWidth = dp(4f)
        paint.color = ContextCompat.getColor(context, R.color.kb_primary)
        paint.alpha = 46
        canvas.drawArc(oval, 0f, 360f, false, paint)
        paint.alpha = 255
        canvas.drawArc(oval, angle, SWEEP_DEGREES, false, paint)
    }

    private companion object {
        const val SPIN_DURATION_MS = 1000L
        const val SWEEP_DEGREES = 100f
    }
}

/** Jitter used so the two waveform sides do not move in lockstep. */
internal fun jitter(level: Float): Float = level * (0.75f + 0.25f * Random.nextFloat())
