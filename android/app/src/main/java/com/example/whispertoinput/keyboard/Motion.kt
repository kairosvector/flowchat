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
import android.provider.Settings
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator

/**
 * The panel's animations (SPEC_r8 §7). Everything is a plain view property
 * animation; when the system animation scale is 0 ("remove animations")
 * every call just switches the view, so nothing moves.
 */
object Motion {
    val fastOutSlowIn: Interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    val linearOutSlowIn: Interpolator = PathInterpolator(0f, 0f, 0.2f, 1f)
    val fastOutLinearIn: Interpolator = PathInterpolator(0.4f, 0f, 1f, 1f)
    val linear: Interpolator = LinearInterpolator()

    /** False when the user turned animations off (animator duration scale 0). */
    fun enabled(context: Context): Boolean = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    } catch (_: Exception) {
        true
    }

    private fun dp(view: View, value: Float) = value * view.resources.displayMetrics.density

    /** Makes [view] visible with a fade (and, optionally, a slide up or a scale from the top right). */
    fun appear(
        view: View,
        durationMs: Long,
        interpolator: Interpolator,
        slideDp: Float = 0f,
        scaleFrom: Float = 1f
    ) {
        view.animate().cancel()
        view.visibility = View.VISIBLE
        if (!enabled(view.context)) {
            reset(view)
            return
        }
        view.alpha = 0f
        view.translationY = dp(view, slideDp)
        if (scaleFrom != 1f) {
            view.pivotX = view.width.toFloat().takeIf { it > 0f } ?: dp(view, 208f)
            view.pivotY = 0f
            view.scaleX = scaleFrom
            view.scaleY = scaleFrom
        }
        view.animate()
            .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(durationMs).setInterpolator(interpolator)
            .start()
    }

    /** Fades [view] out and then hides it ([slideDp] > 0 slides it down while fading). */
    fun disappear(
        view: View,
        durationMs: Long,
        interpolator: Interpolator,
        slideDp: Float = 0f,
        onHidden: () -> Unit = {}
    ) {
        view.animate().cancel()
        if (!enabled(view.context) || view.visibility != View.VISIBLE) {
            view.visibility = View.GONE
            reset(view)
            onHidden()
            return
        }
        view.animate()
            .alpha(0f).translationY(dp(view, slideDp))
            .setDuration(durationMs).setInterpolator(interpolator)
            .withEndAction {
                view.visibility = View.GONE
                reset(view)
                onHidden()
            }
            .start()
    }

    /** Alpha change of a key that is enabled / disabled (120 ms linear). */
    fun alphaTo(view: View, target: Float) {
        view.animate().cancel()
        if (!enabled(view.context) || !view.isShown || view.alpha == target) {
            view.alpha = target
            return
        }
        view.animate().alpha(target).setDuration(120).setInterpolator(linear).start()
    }

    fun reset(view: View) {
        view.alpha = 1f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
    }
}
