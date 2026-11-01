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

package com.example.whispertoinput

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.content.Intent
import android.os.IBinder
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.preferences.core.Preferences
import com.example.whispertoinput.keyboard.WhisperKeyboard
import com.example.whispertoinput.recorder.RecorderManager
import com.github.liuyueyi.quick.transfer.ChineseUtils
import com.github.liuyueyi.quick.transfer.constants.TransType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.BreakIterator
import java.util.Locale

private const val RECORDED_AUDIO_FILENAME_M4A = "recorded.m4a"
private const val RECORDED_AUDIO_FILENAME_OGG = "recorded.ogg"
private const val AUDIO_MEDIA_TYPE_M4A = "audio/mp4"
private const val AUDIO_MEDIA_TYPE_OGG = "audio/ogg"
private const val IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL = 28
private const val WORD_DELETE_LOOKBEHIND = 64

class WhisperInputService : InputMethodService() {
    private val whisperKeyboard: WhisperKeyboard = WhisperKeyboard()
    private val whisperTranscriber: WhisperTranscriber = WhisperTranscriber()
    private var recorderManager: RecorderManager? = null
    private var recordedAudioFilename: String = ""
    private var audioMediaType: String = AUDIO_MEDIA_TYPE_M4A
    private var useOggFormat: Boolean = false
    private var isFirstTime: Boolean = true

    private fun transcriptionCallback(
        text: String?, raw: String?, polished: String?, note: PolishNote, polishError: ErrorDisplay?,
        toneId: String?, usedFallbackModel: Boolean = false
    ) {
        if (!text.isNullOrEmpty()) {
            val inserted = polished ?: raw ?: text
            whisperKeyboard.setLastTranscription(
                currentInputEditorInfo?.packageName,
                currentInputEditorInfo?.fieldId ?: 0,
                inserted,
                System.currentTimeMillis()
            )
            val mode = ResultFlow.forNote(note)
            when (mode) {
                ResultMode.INSERTED_POLISHED -> {
                    // Polished OK: text inserted directly, card shown with tone & actions
                    currentInputConnection?.commitText(text, 1)
                    whisperKeyboard.showInsertedResult(raw ?: text.trim(), polished ?: text, text, toneId, usedFallbackModel)
                    switchBackIfWanted()
                }
                ResultMode.INSERTED_FAILED_RETRY -> {
                    // Polishing failed or timed out: raw text inserted directly, card offers retry
                    currentInputConnection?.commitText(text, 1)
                    whisperKeyboard.showInsertedAfterFailure(raw ?: text.trim(), text, polishError, toneId)
                    switchBackIfWanted()
                }
                ResultMode.DIRECT_INSERT -> {
                    // AI off or short sentence: raw text inserted directly, no card
                    currentInputConnection?.commitText(text, 1)
                    whisperKeyboard.showDirectInserted()
                    switchBackIfWanted()
                }
            }
        } else {
            whisperKeyboard.reset()
        }
    }

    // Check if auto-switch-back is enabled and switch if so
    private fun switchBackIfWanted() {
        CoroutineScope(Dispatchers.Main).launch {
            val autoSwitchBack = dataStore.data.map { preferences: Preferences ->
                preferences[AUTO_SWITCH_BACK] ?: false
            }.first()
            if (autoSwitchBack) {
                onSwitchIme()
            }
        }
    }

    private fun transcriptionExceptionCallback(error: ErrorDisplay) {
        whisperKeyboard.showErrorStatus(error)
    }

    private suspend fun updateAudioFormat() {
        val backend = dataStore.data.map { preferences: Preferences ->
            preferences[SPEECH_TO_TEXT_BACKEND] ?: getString(R.string.settings_option_openai_api)
        }.first()
        
        useOggFormat = backend == getString(R.string.settings_option_nvidia_nim)
        if (useOggFormat) {
            recordedAudioFilename = "${externalCacheDir?.absolutePath}/${RECORDED_AUDIO_FILENAME_OGG}"
            audioMediaType = AUDIO_MEDIA_TYPE_OGG
        } else {
            recordedAudioFilename = "${externalCacheDir?.absolutePath}/${RECORDED_AUDIO_FILENAME_M4A}"
            audioMediaType = AUDIO_MEDIA_TYPE_M4A
        }
    }

    // Fork change (fork5): create the recorder as soon as the service
    // itself is created, not only when the input view is created.
    // On an IME switch the system can drive lifecycle callbacks
    // (onWindowShown / onWindowHidden / onDestroy) in an order that
    // the old code did not expect, and every one of those callbacks
    // used recorderManager!! — a null there crashes the service and
    // the keyboard silently disappears, exactly the reported symptom.
    // With creation moved here, the recorder exists before any of
    // those callbacks can run; the safe calls below are a second
    // layer, so a missing recorder can never crash the service again.
    // The conversion tables are also warmed up here on a background
    // thread, so the switch-time view creation below is less likely
    // to be stalled by a synchronous preload. The synchronous preload
    // in onCreateInputView is deliberately kept: it is the
    // correctness guarantee for conversion, and after this warm-up
    // it is normally a cache hit rather than a first load.
    override fun onCreate() {
        super.onCreate()
        val rm = RecorderManager(this)
        rm.setOnUpdateMicrophoneAmplitude { amplitude ->
            onUpdateMicrophoneAmplitude(amplitude)
        }
        recorderManager = rm
        // Fork6: one-time move of an untouched fork5 default prompt to
        // the new default (see PolishPromptMigration); runs once.
        CoroutineScope(Dispatchers.IO).launch {
            PolishPromptMigration.runIfNeeded(this@WhisperInputService)
        }
        CoroutineScope(Dispatchers.Default).launch {
            try {
                ChineseUtils.preLoad(true, TransType.SIMPLE_TO_TAIWAN)
                ChineseUtils.preLoad(true, TransType.TAIWAN_TO_SIMPLE)
            } catch (_: Exception) {
                // Warm-up only; onCreateInputView still preloads
                // synchronously before any conversion is used.
            }
        }
    }

    override fun onCreateInputView(): View {
        // The recorder is normally already created in onCreate(); this
        // is only a fallback for a path that somehow reaches view
        // creation without service creation (defensive, not expected).
        val rm = recorderManager ?: RecorderManager(this).also { recorderManager = it }

        // Preload conversion table (correctness guarantee; see onCreate
        // for the background warm-up that usually makes this a hit).
        ChineseUtils.preLoad(true, TransType.SIMPLE_TO_TAIWAN)
        ChineseUtils.preLoad(true, TransType.TAIWAN_TO_SIMPLE)

        // Initialize audio format based on backend setting
        CoroutineScope(Dispatchers.Main).launch {
            updateAudioFormat()
        }

        // Should offer ime switch?
        val shouldOfferImeSwitch: Boolean =
            if (Build.VERSION.SDK_INT >= IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL) {
                shouldOfferSwitchingToNextInputMethod()
            } else {
                val inputMethodManager =
                    getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                val token: IBinder? = window?.window?.attributes?.token
                inputMethodManager.shouldOfferSwitchingToNextInputMethod(token)
            }

        // Sets up recorder manager (same callback as onCreate; setting
        // it again here is harmless and covers the fallback path).
        rm.setOnUpdateMicrophoneAmplitude { amplitude ->
            onUpdateMicrophoneAmplitude(amplitude)
        }

        // Returns the keyboard after setting it up and inflating its layout
        val keyboardView = whisperKeyboard.setup(layoutInflater,
            shouldOfferImeSwitch,
            { onStartRecording() },
            { onCancelRecording() },
            { attachToEnd -> onStartTranscription(attachToEnd) },
            { onCancelTranscription() },
            { onDeleteText() },
            { onEnter() },
            { onSpaceBar() },
            { onSwitchIme() },
            { onOpenSettings() },
            { shouldShowRetry() },
            { onShowInputMethodPicker() },
            { onDeleteWord() },
            { text -> onInsertText(text) },
            { inserted -> onUndoInsert(inserted) },
            { old, replacement -> onReplaceInserted(old, replacement) },
            { raw, toneId, done -> whisperTranscriber.polishAsync(this, raw, toneId, done) },
            { toneId -> onOpenToneEditor(toneId) },
        )
        applyNavigationBarStyle()
        return keyboardView
    }

    // Fork6 round 4: the strip under the keyboard (system navigation / gesture
    // bar and the IME switcher row) is drawn by the system in the colour of
    // this window's navigation bar. Give it the panel's own background token
    // and the matching light / dark icons, so the panel and the strip are one
    // colour in both themes. Called again on a theme change (see
    // onConfigurationChanged) and whenever the window is shown.
    private fun applyNavigationBarStyle() {
        val imeWindow = window?.window ?: return
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val panelColor = ContextCompat.getColor(this, R.color.kb_background)
        imeWindow.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        imeWindow.navigationBarColor = panelColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            imeWindow.navigationBarDividerColor = panelColor
        }
        WindowInsetsControllerCompat(imeWindow, imeWindow.decorView)
            .isAppearanceLightNavigationBars = !dark
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyNavigationBarStyle()
    }

    private fun onStartRecording() {
        // Fork change (fork5): no forced unwrapping anywhere on this
        // path. A missing recorder, or a recorder that fails to start
        // (e.g. the microphone is still held from the previous IME),
        // used to be able to throw on the main thread and kill the
        // service right at the moment it was switched to. Now it just
        // leaves the keyboard idle, and the user can tap the mic again.
        // This matters for the switch case in particular because the
        // first onWindowShown auto-starts a recording (see below), so
        // a start failure on switch used to look exactly like a
        // keyboard that never appeared.
        val rm = recorderManager ?: run {
            whisperKeyboard.reset()
            return
        }
        // Upon starting recording, check whether audio permission is granted.
        if (!rm.allPermissionsGranted(this)) {
            // If not, launch app MainActivity (for permission setup).
            launchMainActivity()
            whisperKeyboard.reset()
            return
        }

        if (!rm.start(this, recordedAudioFilename, useOggFormat)) {
            whisperKeyboard.reset()
        }
    }

    // when mic amplitude is updated, notify the keyboard
    // this callback is registered to the recorder manager
    private fun onUpdateMicrophoneAmplitude(amplitude: Int) {
        whisperKeyboard.updateMicrophoneAmplitude(amplitude)
    }

    private fun onCancelRecording() {
        recorderManager?.stop()
    }

    // Fork change (fork2): staged polish display. Stage callbacks come
    // from the transcriber's IO thread, so they are posted to the main
    // thread in order: raw text first, then the polishing state. The
    // final insertion still happens exactly once, in
    // transcriptionCallback, after polishing finishes (or is skipped /
    // fails and falls back to the raw text).
    private fun onPolishStage(stage: PolishStage, text: String?) {
        CoroutineScope(Dispatchers.Main).launch {
            when (stage) {
                PolishStage.RAW -> whisperKeyboard.showTranscriptionStage(text)
                PolishStage.POLISHING -> whisperKeyboard.showPolishingStage()
            }
        }
    }

    private fun onStartTranscription(attachToEnd: String) {
        recorderManager?.stop()
        whisperTranscriber.startAsync(this,
            recordedAudioFilename,
            audioMediaType,
            attachToEnd,
            { text, raw, polished, note, polishError, toneId, usedFallback ->
                transcriptionCallback(text, raw, polished, note, polishError, toneId, usedFallback)
            },
            { transcriptionExceptionCallback(it) },
            { stage, text -> onPolishStage(stage, text) })
    }

    private fun onCancelTranscription() {
        whisperTranscriber.stop()
    }

    private fun onDeleteText() {
        val inputConnection = currentInputConnection ?: return
        val selectedText = inputConnection.getSelectedText(0)

        // Deletes cursor pointed text, or all selected texts
        if (TextUtils.isEmpty(selectedText)) {
            val before = inputConnection.getTextBeforeCursor(32, 0)
            val deleteCount = GraphemeClusterDeleter.getDeleteCharCount(before)
            inputConnection.deleteSurroundingText(deleteCount, 0)
        } else {
            inputConnection.commitText("", 1)
        }
    }

    // Fork6: backspace held for 1.5s deletes a whole word per step. Word
    // boundaries come from BreakIterator, which also segments Chinese.
    private fun onDeleteWord() {
        val inputConnection = currentInputConnection ?: return
        val selectedText = inputConnection.getSelectedText(0)
        if (!TextUtils.isEmpty(selectedText)) {
            inputConnection.commitText("", 1)
            return
        }
        val before = inputConnection.getTextBeforeCursor(WORD_DELETE_LOOKBEHIND, 0)?.toString()
        if (before.isNullOrEmpty()) {
            inputConnection.deleteSurroundingText(1, 0)
            return
        }
        val iterator = BreakIterator.getWordInstance(Locale.TAIWAN)
        iterator.setText(before)
        var end = before.length
        var start = iterator.preceding(end)
        // Skip whitespace-only segments so "word  |" deletes the word too.
        while (start != BreakIterator.DONE && before.substring(start, end).isBlank() && start > 0) {
            end = start
            start = iterator.preceding(end)
        }
        val length = if (start == BreakIterator.DONE) before.length else before.length - start
        inputConnection.deleteSurroundingText(maxOf(length, 1), 0)
    }

    // History rows: plain text into the editor.
    private fun onInsertText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    // Only deletes when the text right before the cursor is still exactly
    // what was inserted; otherwise the user has typed or moved on and a
    // blind delete would remove the wrong characters. Returns whether the
    // text was removed.
    private fun onUndoInsert(inserted: String): Boolean {
        if (inserted.isEmpty()) return false
        val inputConnection = currentInputConnection ?: return false
        val selected = inputConnection.getSelectedText(0)
        val before = inputConnection.getTextBeforeCursor(inserted.length, 0)
        if (!InsertionChecker.canReplaceOrUndo(before, selected, inserted)) return false
        return inputConnection.deleteSurroundingText(inserted.length, 0)
    }

    // Swaps `old` (what the card inserted) for `replacement` as one edit, only
    // while the text right before the cursor is still exactly `old` and
    // nothing is selected (same check as undo). Returns whether it changed.
    private fun onReplaceInserted(old: String, replacement: String): Boolean {
        if (old.isEmpty()) return false
        val inputConnection = currentInputConnection ?: return false
        val selected = inputConnection.getSelectedText(0)
        val before = inputConnection.getTextBeforeCursor(old.length, 0)
        if (!InsertionChecker.canReplaceOrUndo(before, selected, old)) return false
        inputConnection.beginBatchEdit()
        try {
            if (!inputConnection.deleteSurroundingText(old.length, 0)) return false
            return inputConnection.commitText(replacement, 1)
        } finally {
            inputConnection.endBatchEdit()
        }
    }

    // The cursor moved or the text changed (the card no longer follows the
    // cursor since r8: undo checks the text before the cursor itself).
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        whisperKeyboard.onSelectionChanged(newSelStart, newSelEnd)
    }

    private fun onSwitchIme() {
        // Before API Level 28, switchToPreviousInputMethod() was not available
        if (Build.VERSION.SDK_INT >= IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL) {
            switchToPreviousInputMethod()
        } else {
            val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val token: IBinder? = window?.window?.attributes?.token
            inputMethodManager.switchToLastInputMethod(token)
        }

    }

    // Fork change: long-press on the IME switch key shows the system
    // input-method picker, so the user can pick any enabled IME
    // (e.g. Gboard Cangjie or another keyboard), not just the previous one.
    private fun onShowInputMethodPicker() {
        val inputMethodManager =
            getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.showInputMethodPicker()
    }

    private fun onOpenSettings() {
        launchMainActivity()
    }

    // "編輯" in the prompt viewer: opens settings, opens that tone's edit screen, and on return scrolls to AI tone section.
    private fun onOpenToneEditor(toneId: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(ToneEditActivity.EXTRA_TONE_ID, toneId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun onEnter() {
        val inputConnection = currentInputConnection ?: return
        inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
    }

    private fun onSpaceBar() {
        val inputConnection = currentInputConnection ?: return
        inputConnection.commitText(" ", 1)
    }

    private fun shouldShowRetry(): Boolean {
        val exists = File(recordedAudioFilename).exists()
        return exists
    }

    // Opens up app MainActivity
    private fun launchMainActivity() {
        val dialogIntent = Intent(this, MainActivity::class.java)
        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(dialogIntent)
    }

    // Fork change (fork5): re-sync the keyboard view every time the
    // input view is shown, not only when the window is shown. On an
    // IME switch the system drives onStartInputView before
    // onWindowShown, and on a re-show after a failed switch this is
    // the callback that runs even if the window-show path was skipped
    // the first time. reset() is idempotent (it re-reads the last
    // result and the polish toggle and goes idle), and it does not
    // stop a recording or a transcription in flight — stopping those
    // stays in onWindowShown / onWindowHidden below, as before. So a
    // second show can never be skipped just because the keyboard
    // "already has a view" or "already showed once".
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyNavigationBarStyle()
        whisperKeyboard.reset()
        whisperKeyboard.onStartInput(info)
        // The clipboard is only read while the keyboard is on screen (SPEC_r8 §13.2).
        whisperKeyboard.onKeyboardShown()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (whisperKeyboard.isProcessing()) {
            onCancelTranscription()
        }
        whisperKeyboard.onKeyboardHidden()
    }

    // Fork change (fork5): tell the system explicitly that this IME
    // wants its input view shown after a switch. Previously the
    // service did not override this at all, so whether the view
    // appeared after Gboard -> Whisper was left entirely to the base
    // class default. The only case where we say no is an editor that
    // explicitly asks for no soft input (inputType TYPE_NULL); when
    // there is no editor info yet, defer to the base class instead of
    // guessing. This does not force the keyboard open when the user
    // deliberately hid it: hiding goes through hideWindow, not
    // through this decision.
    // The system back key first closes an open tone menu / prompt viewer.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && whisperKeyboard.handleBack()) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onEvaluateInputViewShown(): Boolean {
        val info = currentInputEditorInfo
            ?: return super.onEvaluateInputViewShown()
        if (info.inputType == InputType.TYPE_NULL) return false
        return true
    }

    override fun onWindowShown() {
        super.onWindowShown()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        whisperKeyboard.onWindowShown()
        recorderManager?.stop()

        // If this is the first time calling onWindowShown, it means this IME is just being switched to.
        // Automatically starts recording after switching to Whisper Input. (if settings enabled)
        // Dispatch a coroutine to do this task.
        CoroutineScope(Dispatchers.Main).launch {
            // Update audio format based on current backend setting
            updateAudioFormat()
            if (!isFirstTime) return@launch
            isFirstTime = false
            val isAutoStartRecording = dataStore.data.map { preferences: Preferences ->
                preferences[AUTO_RECORDING_START] ?: true
            }.first()
            if (isAutoStartRecording) {
                whisperKeyboard.tryStartRecording()
            }
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        whisperKeyboard.onKeyboardHidden()
        recorderManager?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        recorderManager?.stop()
    }
}
