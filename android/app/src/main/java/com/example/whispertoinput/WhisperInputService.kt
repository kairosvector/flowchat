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

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.content.Intent
import android.os.IBinder
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
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

private const val RECORDED_AUDIO_FILENAME_M4A = "recorded.m4a"
private const val RECORDED_AUDIO_FILENAME_OGG = "recorded.ogg"
private const val AUDIO_MEDIA_TYPE_M4A = "audio/mp4"
private const val AUDIO_MEDIA_TYPE_OGG = "audio/ogg"
private const val IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL = 28

class WhisperInputService : InputMethodService() {
    private val whisperKeyboard: WhisperKeyboard = WhisperKeyboard()
    private val whisperTranscriber: WhisperTranscriber = WhisperTranscriber()
    private var recorderManager: RecorderManager? = null
    private var recordedAudioFilename: String = ""
    private var audioMediaType: String = AUDIO_MEDIA_TYPE_M4A
    private var useOggFormat: Boolean = false
    private var isFirstTime: Boolean = true

    private fun transcriptionCallback(text: String?) {
        if (!text.isNullOrEmpty()) {
            currentInputConnection?.commitText(text, 1)
            // Fork change: show this text as the last result on the
            // keyboard panel (the transcriber already saved it to
            // local storage); reset() below must not clear it.
            whisperKeyboard.setLastResult(text)
            // Check if auto-switch-back is enabled and switch if so
            CoroutineScope(Dispatchers.Main).launch {
                val autoSwitchBack = dataStore.data.map { preferences: Preferences ->
                    preferences[AUTO_SWITCH_BACK] ?: false
                }.first()
                if (autoSwitchBack) {
                    onSwitchIme()
                }
            }
        }
        whisperKeyboard.reset()
    }

    private fun transcriptionExceptionCallback(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        whisperKeyboard.reset()
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
        return whisperKeyboard.setup(layoutInflater,
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
        )
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
            { transcriptionCallback(it) },
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
            inputConnection.deleteSurroundingText(1, 0)
        } else {
            inputConnection.commitText("", 1)
        }
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
        whisperKeyboard.reset()
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
        recorderManager?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        recorderManager?.stop()
    }
}
