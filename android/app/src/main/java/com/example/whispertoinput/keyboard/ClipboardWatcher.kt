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

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.example.whispertoinput.ClipClassifier
import com.example.whispertoinput.ClipItem
import com.example.whispertoinput.ClipKind
import com.example.whispertoinput.ClipRecord
import com.example.whispertoinput.ClipStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Diagnostic status of the latest clipboard read attempt (ROUND 14 §A).
 * Never contains clipboard contents and is never logged.
 */
enum class ClipReadStatus {
    NONE,
    SUCCESS,    // 有讀到
    EMPTY,      // 讀到空的
    DENIED      // 被系統拒絕
}

/**
 * Reads the system clipboard while the keyboard is shown (SPEC_r8 §13): once when the keyboard
 * appears and again whenever the primary clip changes; the listener is removed when the keyboard
 * closes. Only text clips count. The text lives in [current] (memory); what may be kept goes to
 * [ClipStore]. Nothing is logged.
 */
class ClipboardWatcher(context: Context, private val onChanged: () -> Unit) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    /** The text now on the clipboard, or null when it is empty or not text. */
    var current: ClipItem? = null
        private set

    /** Diagnostic status of the last read attempt. */
    var lastReadStatus: ClipReadStatus = ClipReadStatus.NONE
        private set

    /** Visible for testing: override the read status in tests. */
    fun setDiagnosticStatusForTesting(status: ClipReadStatus) {
        lastReadStatus = status
    }

    /** The kept ordinary history, newest first (loaded when the keyboard shows and after each change). */
    var history: List<ClipRecord> = emptyList()
        private set

    private var started = false
    // Before API 26 a clip has no timestamp: the moment it was first seen stands in for it.
    private var seenText: String? = null
    private var seenAt: Long = 0
    private var watcherScope: CoroutineScope? = null

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { refresh() }

    fun start() {
        val m = manager ?: return
        if (!started) {
            m.addPrimaryClipChangedListener(listener)
            started = true
        }
        watcherScope?.cancel()
        watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        refresh()
    }

    fun stop() {
        if (started) {
            try {
                manager?.removePrimaryClipChangedListener(listener)
            } catch (_: Exception) {
                // Nothing to remove.
            }
            started = false
        }
        watcherScope?.cancel()
        watcherScope = null
        current = null
        ClipStore.keyboardClosed()
    }

    fun refresh() {
        val item = readCurrent()
        current = item
        val scope = watcherScope ?: return
        scope.launch {
            if (item != null) ClipStore.record(appContext, item.text, item.timestampMillis, item.kind)
            history = ClipStore.history(appContext)
            onChanged()
        }
    }

    /** Reloads the kept history (after a clear). */
    fun reloadHistory() {
        val scope = watcherScope ?: return
        scope.launch {
            history = ClipStore.history(appContext)
            onChanged()
        }
    }

    private fun readCurrent(): ClipItem? {
        val m = manager
        if (m == null) {
            lastReadStatus = ClipReadStatus.EMPTY
            return null
        }
        return try {
            if (!m.hasPrimaryClip()) {
                lastReadStatus = ClipReadStatus.EMPTY
                return null
            }
            val description = m.primaryClipDescription
            if (description == null ||
                (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
                 !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML))
            ) {
                lastReadStatus = ClipReadStatus.EMPTY
                return null
            }
            val clip = m.primaryClip
            if (clip == null || clip.itemCount == 0) {
                lastReadStatus = ClipReadStatus.EMPTY
                return null
            }
            val text = clip.getItemAt(0).text?.toString()
            if (text.isNullOrEmpty()) {
                lastReadStatus = ClipReadStatus.EMPTY
                return null
            }
            // The flag lives in the extras of the clip's own description.
            val flagged = clip.description?.extras?.getBoolean(sensitiveKey()) == true ||
                description.extras?.getBoolean(sensitiveKey()) == true
            val now = System.currentTimeMillis()
            val timestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                description.timestamp.takeIf { it > 0 } ?: now
            } else {
                if (text != seenText) {
                    seenText = text
                    seenAt = now
                }
                seenAt
            }
            var kind = ClipClassifier.classify(text, flagged)
            // Some systems put the same clip back a moment later without the flag (the
            // clipboard overlay does on the emulator): a text that was flagged a moment ago
            // stays sensitive while it is still remembered in memory.
            if (!flagged && ClipStore.memory.any { it.text == text && it.kind == ClipKind.SENSITIVE }) {
                kind = ClipKind.SENSITIVE
            }
            lastReadStatus = ClipReadStatus.SUCCESS
            ClipItem(text, timestamp, kind, true)
        } catch (e: SecurityException) {
            // POCO / HyperOS / Android 10+ background restriction before IME window focus: rejected by system.
            lastReadStatus = ClipReadStatus.DENIED
            null
        } catch (e: Exception) {
            // Other system exceptions (RemoteException, DeadObjectException, etc.)
            lastReadStatus = ClipReadStatus.DENIED
            null
        }
    }

    private fun sensitiveKey(): String =
        if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else ClipClassifier.IS_SENSITIVE_KEY
}
