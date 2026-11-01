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

/** The relative time shown on a history row (剛剛 / N 分鐘前 / N 小時前 / 昨天 / date). */
sealed class RelativeTime {
    object JustNow : RelativeTime()
    data class MinutesAgo(val minutes: Int) : RelativeTime()
    data class HoursAgo(val hours: Int) : RelativeTime()
    object Yesterday : RelativeTime()

    /** Older than two days: the caller shows the date. */
    object Older : RelativeTime()

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR

        /** Pure: [nowMs] and [thenMs] are epoch millis; a time in the future counts as just now. */
        fun of(nowMs: Long, thenMs: Long): RelativeTime {
            val diff = nowMs - thenMs
            return when {
                diff < MINUTE -> JustNow
                diff < HOUR -> MinutesAgo((diff / MINUTE).toInt())
                diff < DAY -> HoursAgo((diff / HOUR).toInt())
                diff < 2 * DAY -> Yesterday
                else -> Older
            }
        }
    }
}
