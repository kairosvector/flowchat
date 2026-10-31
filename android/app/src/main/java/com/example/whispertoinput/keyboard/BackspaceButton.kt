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

import android.content.Context
import android.util.AttributeSet
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatImageButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Fork6 (DESIGN_NOTES): hold 400ms, then repeat every 50ms; after the
// key has been held for 1.5s in total, delete a whole word per step.
private const val QUICK_BACKSPACE_DELAY: Long = 50
private const val DELAY_BEFORE_QUICK_BACKSPACE: Long = 400
private const val WORD_BACKSPACE_AFTER: Long = 1500

class BackspaceButton(context: Context, attrs: AttributeSet) :
    AppCompatImageButton(context, attrs) {

    fun setBackspaceCallback(callback: () -> Unit, wordCallback: () -> Unit = callback) {
        backspaceCallback = callback
        wordBackspaceCallback = wordCallback
        setOnTouchListener { _, event ->
            if (!isEnabled) {
                abortLongPressDetector()
                return@setOnTouchListener false
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    performClick()
                    startLongPressDetector()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> abortLongPressDetector()
            }
            true
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) {
            abortLongPressDetector()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        abortLongPressDetector()
    }

    fun cancelContinuousDelete() {
        abortLongPressDetector()
    }

    // Override this for accessibility.
    // performClick() will be called either in appropriate touch events,
    // or when accessibility tools demand its invocation
    override fun performClick(): Boolean {
        if (!isEnabled) return false
        super.performClick()
        backspaceCallback()
        return true
    }

    // Starts a job that periodically performs backspace
    private fun startLongPressDetector() {
        longPressDetectorJob?.cancel()
        val pressedAt = SystemClock.uptimeMillis()
        longPressDetectorJob = CoroutineScope(Dispatchers.Main).launch {
            // Long Press: delay for a while before actually starting
            //   quick backspace. Any ACTION_UP will terminate either
            //   the waiting or quick backspacing.
            delay(DELAY_BEFORE_QUICK_BACKSPACE)
            // One long-press tick when the quick delete starts, none for each step.
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            while (this.isActive) {
                if (SystemClock.uptimeMillis() - pressedAt >= WORD_BACKSPACE_AFTER) {
                    wordBackspaceCallback()
                } else {
                    backspaceCallback()
                }
                delay(QUICK_BACKSPACE_DELAY)
            }
        }
    }

    // Aborts any currently running quick backspace job.
    private fun abortLongPressDetector() {
        longPressDetectorJob?.cancel()
        longPressDetectorJob = null
    }

    // Stores the callback when a backspace should be performed
    private var backspaceCallback: () -> Unit = { }

    // Stores the callback used once the key has been held long enough
    private var wordBackspaceCallback: () -> Unit = { }

    // Stores the currently running long press detector job
    private var longPressDetectorJob: Job? = null
}