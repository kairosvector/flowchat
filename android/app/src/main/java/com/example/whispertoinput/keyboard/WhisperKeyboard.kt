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

import android.util.Log
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.TextUtils
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.example.whispertoinput.TranscriptionHistory
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.math.MathUtils
import androidx.datastore.preferences.core.edit
import com.example.whispertoinput.POLISH_ENABLED
import com.example.whispertoinput.R
import com.example.whispertoinput.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.pow

private const val AMPLITUDE_CLAMP_MIN: Int = 10
private const val AMPLITUDE_CLAMP_MAX: Int = 25000
private const val LOG_10_10: Float = 1.0F
private const val LOG_10_25000: Float = 4.398F
private const val AMPLITUDE_ANIMATION_DURATION: Long = 500
private val amplitudePowers: Array<Float> = arrayOf(0.5f, 1.0f, 2f, 3f)

class WhisperKeyboard {
    private enum class KeyboardStatus {
        Idle,             // Ready to start recording
        Recording,       // Currently recording
        Transcribing,    // Waiting for transcription results
    }

    // Keyboard event listeners. Assignable custom behaviors upon certain UI events (user-operated).
    private var onStartRecording: () -> Unit = { }
    private var onCancelRecording: () -> Unit = { }
    private var onStartTranscribing: (attachToEnd: String) -> Unit = { }
    private var onCancelTranscribing: () -> Unit = { }
    private var onButtonBackspace: () -> Unit = { }
    private var onSwitchIme: () -> Unit = { }
    // Fork change: long-press on the switch key shows the system IME picker.
    private var onShowInputMethodPicker: () -> Unit = { }
    private var onOpenSettings: () -> Unit = { }
    private var onEnter: () -> Unit = { }
    private var onSpaceBar: () -> Unit = { }
    private var shouldShowRetry: () -> Boolean = { false }

    // Keyboard Status
    private var keyboardStatus: KeyboardStatus = KeyboardStatus.Idle

    // Views & Keyboard Layout
    private var keyboardView: ConstraintLayout? = null
    private var buttonMic: ImageButton? = null
    private var buttonEnter: ImageButton? = null
    private var buttonCancel: ImageButton? = null
    private var buttonRetry: ImageButton? = null
    private var labelStatus: TextView? = null
    // Fork change (fork4): second result line for the polished text.
    // label_status shows the raw text (dimmer, smaller); this view
    // shows the polished text (normal size) only when polishing
    // succeeded, and shows "正在潤飾中…" while an LLM call is in
    // flight. It is gone whenever there is no polished text to show
    // (no record yet, polishing skipped / failed / switched off, or
    // a plain recording / transcribing status is showing).
    private var labelPolished: TextView? = null
    private var rawResultText: String? = null
    private var polishedResultText: String? = null
    private var polishingInFlight: Boolean = false
    private var buttonSpaceBar: ImageButton? = null
    private var waitingIcon: ProgressBar? = null
    private var buttonBackspace: BackspaceButton? = null
    private var buttonPreviousIme: ImageButton? = null
    private var buttonSettings: ImageButton? = null
    // Fork change (fork3): on-keyboard polish toggle. It reads and
    // writes the SAME DataStore key as the settings page
    // (POLISH_ENABLED), so the two can never disagree. The value is
    // only consulted when a new transcription starts (see
    // WhisperTranscriber, which snapshots the config before the STT
    // request), so flipping this mid-recording / mid-polish cannot
    // cancel or change the run already in flight.
    private var buttonPolishToggle: ImageButton? = null
    private var polishEnabled: Boolean = true
    // Fork change: last transcription result (see TranscriptionHistory).
    private var buttonCopyLastResult: ImageButton? = null
    private var lastResultText: String? = null
    // Fork change (fork2): staged polish display. While the
    // transcription->polish pipeline is running, the keyboard stays in
    // Transcribing status, but the top label is overridden by the
    // current stage (raw text, then "正在潤飾中…"). pipelineActive is
    // what lets a label tap copy the staged text during the pipeline;
    // outside the pipeline, copying is idle-only as before, so a stale
    // result can never be copied while recording or transcribing.
    private var pipelineActive: Boolean = false
    private var stagedText: String? = null
    private var micRippleContainer: ConstraintLayout? = null
    private var micRipples: Array<ImageView> = emptyArray()

    fun setup(
        layoutInflater: LayoutInflater,
        shouldOfferImeSwitch: Boolean,
        onStartRecording: () -> Unit,
        onCancelRecording: () -> Unit,
        onStartTranscribing: (attachToEnd: String) -> Unit,
        onCancelTranscribing: () -> Unit,
        onButtonBackspace: () -> Unit,
        onEnter: () -> Unit,
        onSpaceBar: () -> Unit,
        onSwitchIme: () -> Unit,
        onOpenSettings: () -> Unit,
        shouldShowRetry: () -> Boolean,
        onShowInputMethodPicker: () -> Unit = { },
    ): View {
        // Inflate the keyboard layout & assign views
        keyboardView = layoutInflater.inflate(R.layout.keyboard_view, null) as ConstraintLayout
        buttonMic = keyboardView!!.findViewById(R.id.btn_mic) as ImageButton
        buttonEnter = keyboardView!!.findViewById(R.id.btn_enter) as ImageButton
        buttonCancel = keyboardView!!.findViewById(R.id.btn_cancel) as ImageButton
        buttonRetry = keyboardView!!.findViewById(R.id.btn_retry) as ImageButton
        labelStatus = keyboardView!!.findViewById(R.id.label_status) as TextView
        labelPolished = keyboardView!!.findViewById(R.id.label_polished) as TextView
        buttonSpaceBar = keyboardView!!.findViewById(R.id.btn_space_bar) as ImageButton
        waitingIcon = keyboardView!!.findViewById(R.id.pb_waiting_icon) as ProgressBar
        buttonBackspace = keyboardView!!.findViewById(R.id.btn_backspace) as BackspaceButton
        buttonPreviousIme = keyboardView!!.findViewById(R.id.btn_previous_ime) as ImageButton
        buttonSettings = keyboardView!!.findViewById(R.id.btn_settings) as ImageButton
        buttonPolishToggle =
            keyboardView!!.findViewById(R.id.btn_polish_toggle) as ImageButton
        buttonCopyLastResult =
            keyboardView!!.findViewById(R.id.btn_copy_last_result) as ImageButton
        micRippleContainer = keyboardView!!.findViewById(R.id.mic_ripples) as ConstraintLayout
        micRipples = arrayOf(
            keyboardView!!.findViewById(R.id.mic_ripple_0) as ImageView,
            keyboardView!!.findViewById(R.id.mic_ripple_1) as ImageView,
            keyboardView!!.findViewById(R.id.mic_ripple_2) as ImageView,
            keyboardView!!.findViewById(R.id.mic_ripple_3) as ImageView
        )

        // Hide buttonPreviousIme if necessary
        if (!shouldOfferImeSwitch) {
            buttonPreviousIme!!.visibility = View.GONE
        }

        // Set onClick listeners
        buttonMic!!.setOnClickListener { onButtonMicClick() }
        buttonEnter!!.setOnClickListener { onButtonEnterClick() }
        buttonCancel!!.setOnClickListener { onButtonCancelClick() }
        buttonRetry!!.setOnClickListener { onButtonRetryClick() }
        buttonSettings!!.setOnClickListener { onButtonSettingsClick() }
        buttonPolishToggle!!.setOnClickListener { onButtonPolishToggleClick() }
        buttonBackspace!!.setBackspaceCallback { onButtonBackspaceClick() }
        buttonSpaceBar!!.setOnClickListener { onButtonSpaceBarClick() }
        // Fork change: copy-last-result button copies what was
        // actually inserted (polished if present, else raw). Each
        // result line copies its own full text (not the truncated
        // display): the raw line copies the raw text, the polished
        // line copies the polished text.
        buttonCopyLastResult!!.setOnClickListener { copyLastResult() }
        labelStatus!!.setOnClickListener { copyRawResult() }
        labelPolished!!.setOnClickListener { copyPolishedResult() }

        if (shouldOfferImeSwitch) {
            buttonPreviousIme!!.setOnClickListener { onButtonPreviousImeClick() }
            // Fork change: long-press shows the system IME picker so the
            // user can pick any enabled IME, not just the previous one.
            // Returning true consumes the long-press so the short-press
            // switch is NOT also triggered.
            buttonPreviousIme!!.setOnLongClickListener {
                onButtonPreviousImeLongClick()
                true
            }
        }

        // Set event listeners
        this.onStartRecording = onStartRecording
        this.onCancelRecording = onCancelRecording
        this.onStartTranscribing = onStartTranscribing
        this.onCancelTranscribing = onCancelTranscribing
        this.onButtonBackspace = onButtonBackspace
        this.onSwitchIme = onSwitchIme
        this.onShowInputMethodPicker = onShowInputMethodPicker
        this.onOpenSettings = onOpenSettings
        this.onEnter = onEnter
        this.onSpaceBar = onSpaceBar
        this.shouldShowRetry = shouldShowRetry

        // Resets keyboard upon setup
        reset()

        // Fork change (fork3): paint the polish toggle from the saved
        // value (same key as the settings page, default on) as soon as
        // the keyboard view is built, so it is never shown in the
        // wrong state while the user waits.
        refreshPolishToggle()

        // Returns the keyboard view (non-nullable)
        return keyboardView!!
    }

    fun reset() {
        // Fork change: reload the last result before going idle, so a
        // rebuilt keyboard view (switching apps and back) still shows
        // the last result instead of the default title.
        pipelineActive = false
        stagedText = null
        polishingInFlight = false
        refreshLastResult()
        setKeyboardStatus(KeyboardStatus.Idle)
        // Fork change (fork3): re-read the toggle on every reset (the
        // service calls reset() when the keyboard window is shown), so
        // a change made in the settings page is picked up when the
        // user comes back to the keyboard without a view rebuild.
        refreshPolishToggle()
    }

    // Fork change: called by the service right after a transcription is
    // committed, before reset(). The transcriber has already saved
    // the record (raw + polished, if any) by this point, so reload
    // it here instead of guessing the split from the inserted text
    // alone: the inserted text cannot tell raw from polished.
    // reset() right after re-renders the same two lines again, which
    // is harmless.
    fun setLastResult(text: String?) {
        pipelineActive = false
        stagedText = null
        polishingInFlight = false
        val trimmed = text?.trim()
        lastResultText = if (trimmed.isNullOrEmpty()) null else trimmed
        refreshLastResult()
        if (keyboardStatus == KeyboardStatus.Idle) {
            updateStatusLabelForIdle()
        }
        updateCopyButton()
    }

    /**
     * Fork change (fork2), stage 1: STT finished, show the raw text on
     * the top label before polishing starts. The text is not inserted
     * yet; insertion happens exactly once, after polishing finishes
     * (or is skipped / fails). Copying the staged text is allowed
     * during the pipeline, so a tap copies this raw text, never a
     * stale result from an earlier transcription.
     */
    fun showTranscriptionStage(text: String?) {
        val trimmed = text?.trim()
        if (trimmed.isNullOrEmpty()) return
        pipelineActive = true
        polishingInFlight = false
        stagedText = trimmed
        // A new segment supersedes the previous record's polished
        // line: it must not linger beside the new raw text.
        rawResultText = trimmed
        polishedResultText = null
        lastResultText = trimmed
        renderResultLines()
    }

    /**
     * Fork change (fork2), stage 2, revised in fork4: an LLM polish
     * call is in flight. The raw line stays visible, and the polished
     * line shows "正在潤飾中…" instead of the raw line being
     * replaced, so the user can still see what was detected while
     * waiting on the LLM. The waiting icon and cancel button stay
     * exactly as in Transcribing status.
     */
    fun showPolishingStage() {
        pipelineActive = true
        polishingInFlight = true
        renderResultLines()
    }

    private fun refreshLastResult() {
        try {
            val context = keyboardView?.context ?: return
            // Fork change (fork2, fork4): the copy button shows what
            // was actually inserted, so a polished record wins over
            // its raw text; unpolished records fall back to raw. The
            // two result lines keep the split, so both can be shown.
            val record = TranscriptionHistory.loadLast(context)
            rawResultText = record?.rawText
            polishedResultText = record?.polishedText
            lastResultText = record?.polishedText ?: record?.rawText
        } catch (_: Exception) {
            // Best-effort only; a missing record just shows the default.
        }
    }

    /**
     * Fork change (fork4): renders the two result lines. Only call
     * this in Idle or while the pipeline is active; plain Recording /
     * Transcribing statuses overwrite the top label with their own
     * status text in setKeyboardStatus and hide the polished line.
     * Prefixes are literal labels, not part of the copied text:
     * copying always uses the full raw / polished string.
     */
    private fun renderResultLines() {
        val status = labelStatus ?: return
        val polished = labelPolished ?: return
        val context = keyboardView?.context
        if (rawResultText.isNullOrEmpty()) {
            status.setText(R.string.whisper_to_input)
            polished.visibility = View.GONE
            return
        }
        val rawPrefix = context?.getString(R.string.result_raw_prefix) ?: "原文："
        val polishedPrefix = context?.getString(R.string.result_polished_prefix) ?: "潤飾後："
        status.text = rawPrefix + rawResultText!!
        when {
            polishingInFlight -> {
                polished.visibility = View.VISIBLE
                polished.text = polishedPrefix + (context?.getString(R.string.polishing) ?: "正在潤飾中…")
            }
            !polishedResultText.isNullOrEmpty() -> {
                polished.visibility = View.VISIBLE
                polished.text = polishedPrefix + polishedResultText!!
            }
            else -> {
                polished.visibility = View.GONE
            }
        }
    }

    private fun updateStatusLabelForIdle() {
        renderResultLines()
    }

    private fun copyLastResult() {
        // Copy button: what was actually inserted (polished if
        // present, else raw).
        copyText(lastResultText)
    }

    private fun copyRawResult() {
        // Raw line tap: the raw text, without its "原文：" prefix.
        copyText(rawResultText)
    }

    private fun copyPolishedResult() {
        // Polished line tap: the polished text, without its "潤飾後："
        // prefix. While polishing is in flight there is no polished
        // text yet, so the tap is a no-op rather than copying the
        // "正在潤飾中…" status string.
        if (polishingInFlight) return
        copyText(polishedResultText)
    }

    private fun copyText(text: String?) {
        // Only in idle, or during the staged pipeline (where the staged
        // text is the current result, not a stale one): during plain
        // recording / transcribing the label shows a status, and
        // tapping must not copy a stale result.
        if (keyboardStatus != KeyboardStatus.Idle && !pipelineActive) return
        if (text.isNullOrEmpty()) return
        val context = keyboardView?.context ?: return
        try {
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("transcription", text))
            Toast.makeText(context, R.string.last_result_copied, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            // Copy failure must not crash the keyboard.
        }
    }

    private fun updateCopyButton() {
        val button = buttonCopyLastResult ?: return
        // The left slot is shared with cancel / retry (see layout):
        // recording / transcribing -> cancel owns the slot, hide copy;
        // idle + retry visible -> retry owns the slot, hide copy
        // (the label tap still copies);
        // idle + no retry -> show copy, faded + disabled when empty.
        when (keyboardStatus) {
            KeyboardStatus.Recording, KeyboardStatus.Transcribing -> {
                button.visibility = View.INVISIBLE
            }
            KeyboardStatus.Idle -> {
                if (shouldShowRetry()) {
                    button.visibility = View.INVISIBLE
                } else {
                    button.visibility = View.VISIBLE
                    val hasResult = !lastResultText.isNullOrEmpty()
                    button.isEnabled = hasResult
                    button.alpha = if (hasResult) 1.0f else 0.35f
                }
            }
        }
    }

    fun updateMicrophoneAmplitude(amplitude: Int) {
        if (keyboardStatus != KeyboardStatus.Recording) {
            return
        }

        val clampedAmplitude = MathUtils.clamp(
            amplitude,
            AMPLITUDE_CLAMP_MIN,
            AMPLITUDE_CLAMP_MAX
        )

        // decibel-like calculation
        val normalizedPower =
            (log10(clampedAmplitude * 1f) - LOG_10_10) / (LOG_10_25000 - LOG_10_10)

        // normalizedPower ranges from 0 to 1.
        // The inner-most ripple should be the most sensitive to audio,
        // represented by a gamma-correction-like curve.
        for (micRippleIdx in micRipples.indices) {
            micRipples[micRippleIdx].clearAnimation()
            micRipples[micRippleIdx].alpha = normalizedPower.pow(amplitudePowers[micRippleIdx])
            micRipples[micRippleIdx].animate().alpha(0f).setDuration(AMPLITUDE_ANIMATION_DURATION)
                .start()
        }
    }

    fun tryStartRecording() {
        if (keyboardStatus == KeyboardStatus.Idle) {
            setKeyboardStatus(KeyboardStatus.Recording)
            onStartRecording()
        }
    }

    fun tryCancelRecording() {
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Idle)
            onCancelRecording()
        }
    }

    fun tryStartTranscribing(attachToEnd: String) {
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Transcribing)
            onStartTranscribing(attachToEnd)
        }
    }

    private fun onButtonSpaceBarClick() {
        // Upon button space bar click.
        // Recording -> Start transcribing (with a whitespace included)
        // else -> invokes onSpaceBar
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Transcribing)
            onStartTranscribing(" ")
        } else {
            onSpaceBar()
        }
    }

    private fun onButtonBackspaceClick() {
        // Currently, this onClick only makes a call to onButtonBackspace()
        this.onButtonBackspace()
    }

    private fun onButtonPreviousImeClick() {
        // Currently, this onClick only makes a call to onSwitchIme()
        this.onSwitchIme()
    }

    private fun onButtonPreviousImeLongClick() {
        // Fork change: haptic feedback only (no animation, matching the
        // project's plain style); the system picker itself is the
        // confirmation that the long-press worked.
        buttonPreviousIme!!.performHapticFeedback(
            android.view.HapticFeedbackConstants.LONG_PRESS
        )
        this.onShowInputMethodPicker()
    }

    private fun onButtonSettingsClick() {
        // Currently, this onClick only makes a call to onOpenSettings()
        this.onOpenSettings()
    }

    // Fork change (fork3): polish toggle on the keyboard panel.
    private fun updatePolishToggleButton() {
        val button = buttonPolishToggle ?: return
        // On: full brightness. Off: clearly faded, matching the faded
        // copy-button pattern used elsewhere on this panel.
        button.alpha = if (polishEnabled) 1.0f else 0.35f
        button.contentDescription = if (polishEnabled) {
            button.context.getString(R.string.polish_toggle_on)
        } else {
            button.context.getString(R.string.polish_toggle_off)
        }
    }

    private fun refreshPolishToggle() {
        val context = keyboardView?.context ?: return
        CoroutineScope(Dispatchers.Main).launch {
            try {
                polishEnabled = context.dataStore.data.map { preferences ->
                    preferences[POLISH_ENABLED] ?: true
                }.first()
            } catch (_: Exception) {
                // Reading failure keeps the default (on); the toggle
                // still works and will write a fresh value on tap.
                polishEnabled = true
            }
            updatePolishToggleButton()
        }
    }

    private fun onButtonPolishToggleClick() {
        val context = keyboardView?.context ?: return
        // Flip and paint immediately, then persist. The new value only
        // takes effect for the NEXT recording: WhisperTranscriber
        // snapshots POLISH_ENABLED once, before its STT request, and
        // never re-reads it mid-run, so this tap cannot cancel or
        // change a recording / polish already in flight.
        val newValue = !polishEnabled
        polishEnabled = newValue
        updatePolishToggleButton()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                context.dataStore.edit { settings ->
                    settings[POLISH_ENABLED] = newValue
                }
            } catch (_: Exception) {
                // Persist failure: revert the paint so the button never
                // claims a state that was not saved.
                polishEnabled = !newValue
                updatePolishToggleButton()
            }
        }
    }

    private fun onButtonMicClick() {
        // Upon button mic click...
        // Idle -> Start Recording
        // Recording -> Finish Recording (without a newline)
        // Transcribing -> Nothing (to avoid double-clicking by mistake, which starts transcribing and then immediately cancels it)
        when (keyboardStatus) {
            KeyboardStatus.Idle -> {
                setKeyboardStatus(KeyboardStatus.Recording)
                onStartRecording()
            }

            KeyboardStatus.Recording -> {
                setKeyboardStatus(KeyboardStatus.Transcribing)
                onStartTranscribing("")
            }

            KeyboardStatus.Transcribing -> {
                return
            }
        }
    }

    private fun onButtonEnterClick() {
        // Upon button enter click.
        // Recording -> Start transcribing (with a newline included)
        // else -> invokes onEnter
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Transcribing)
            onStartTranscribing("\r\n")
        } else {
            onEnter()
        }
    }

    private fun onButtonCancelClick() {
        // Upon button cancel click.
        // Recording -> Cancel
        // Transcribing -> Cancel
        // else -> nothing
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Idle)
            onCancelRecording()
        } else if (keyboardStatus == KeyboardStatus.Transcribing) {
            setKeyboardStatus(KeyboardStatus.Idle)
            onCancelTranscribing()
        }
    }

    private fun onButtonRetryClick() {
        // Upon button retry click.
        // Idle -> Retry
        // else -> nothing
        if (keyboardStatus == KeyboardStatus.Idle) {
            setKeyboardStatus(KeyboardStatus.Transcribing)
            onStartTranscribing("")
        }
    }

    private fun setKeyboardStatus(newStatus: KeyboardStatus) {
        // Assign first: updateCopyButton() reads keyboardStatus.
        keyboardStatus = newStatus
        when (newStatus) {
            KeyboardStatus.Idle -> {
                // Fork change: show the last result if there is one;
                // only fall back to the default title when there is none.
                // Recording / transcribing below still overwrite this
                // with their status, so a result never covers a status.
                // (reset() already cleared the pipeline before reaching
                // here; a cancel click reaching Idle directly also ends
                // any staged display, since that pipeline is aborted.)
                pipelineActive = false
                stagedText = null
                polishingInFlight = false
                updateStatusLabelForIdle()
                buttonMic!!.setImageResource(R.drawable.mic_idle)
                waitingIcon!!.visibility = View.INVISIBLE
                buttonCancel!!.visibility = View.INVISIBLE
                buttonRetry!!.visibility = if (shouldShowRetry()) View.VISIBLE else View.INVISIBLE
                micRippleContainer!!.visibility = View.GONE
                keyboardView!!.keepScreenOn = false
            }

            KeyboardStatus.Recording -> {
                // A new recording supersedes any staged display: the
                // status text replaces the raw line, and the polished
                // line is hidden until a new raw text arrives, so no
                // stale result from the previous segment is shown.
                pipelineActive = false
                stagedText = null
                polishingInFlight = false
                labelPolished?.visibility = View.GONE
                labelStatus!!.setText(R.string.recording)
                buttonMic!!.setImageResource(R.drawable.mic_pressed)
                waitingIcon!!.visibility = View.INVISIBLE
                buttonCancel!!.visibility = View.VISIBLE
                buttonRetry!!.visibility = View.INVISIBLE
                micRippleContainer!!.visibility = View.VISIBLE
                keyboardView!!.keepScreenOn = true
            }

            KeyboardStatus.Transcribing -> {
                // Plain transcribing (before the RAW stage callback):
                // status text only, polished line hidden. The RAW
                // stage callback re-enables the pipeline and restores
                // the two-line display for the new segment.
                labelPolished?.visibility = View.GONE
                labelStatus!!.setText(R.string.transcribing)
                buttonMic!!.setImageResource(R.drawable.mic_transcribing)
                waitingIcon!!.visibility = View.VISIBLE
                buttonCancel!!.visibility = View.VISIBLE
                buttonRetry!!.visibility = View.INVISIBLE
                micRippleContainer!!.visibility = View.GONE
                keyboardView!!.keepScreenOn = true
            }
        }

        updateCopyButton()
    }
}
