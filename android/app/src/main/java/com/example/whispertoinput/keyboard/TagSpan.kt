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

package com.example.whispertoinput.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan

/**
 * A small rounded "tag" drawn at the start of a result-card line (✦ 專業,
 * 原文). Sizes are in px and already scaled by the caller, because the
 * panel caps the system font scale itself.
 */
class TagSpan(
    private val label: String,
    private val background: Int,
    private val foreground: Int,
    private val textSizePx: Float,
    private val hPaddingPx: Float,
    private val vPaddingPx: Float,
    private val radiusPx: Float,
    private val gapPx: Float
) : ReplacementSpan() {

    private fun tagPaint(base: Paint): Paint = Paint(base).apply {
        textSize = textSizePx
        isFakeBoldText = true
    }

    override fun getSize(
        paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?
    ): Int {
        return (tagPaint(paint).measureText(label) + 2 * hPaddingPx + gapPx).toInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        val tag = tagPaint(paint)
        val width = tag.measureText(label) + 2 * hPaddingPx
        val metrics = tag.fontMetrics
        val height = metrics.descent - metrics.ascent + 2 * vPaddingPx
        // Vertically centred on the line's x-height area, not on its full box.
        val lineCenter = y + (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        val rect = RectF(x, lineCenter - height / 2f, x + width, lineCenter + height / 2f)
        tag.color = background
        tag.style = Paint.Style.FILL
        canvas.drawRoundRect(rect, radiusPx, radiusPx, tag)
        tag.color = foreground
        val baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, x + hPaddingPx, baseline, tag)
    }
}
