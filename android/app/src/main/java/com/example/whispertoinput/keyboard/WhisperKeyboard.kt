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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.math.MathUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.datastore.preferences.core.edit
import com.example.whispertoinput.LANGUAGE_CODE
import com.example.whispertoinput.LanguageSettings
import com.example.whispertoinput.PolishNote
import com.example.whispertoinput.POLISH_ENABLED
import com.example.whispertoinput.R
import com.example.whispertoinput.TranscriptionHistory
import com.example.whispertoinput.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.log10

private const val AMPLITUDE_CLAMP_MIN: Int = 10
private const val AMPLITUDE_CLAMP_MAX: Int = 25000
private const val LOG_10_10: Float = 1.0F
private const val LOG_10_25000: Float = 4.398F

// Fork6 gesture / timing constants (DESIGN_NOTES.md).
private const val HOLD_TO_TALK_MS: Long = 400
private const val SWIPE_CANCEL_DP: Float = 48f
private const val WAVEFORM_TICK_MS: Long = 50
private const val LEVEL_DECAY_PER_TICK: Float = 0.90f

private const val LANGUAGE_AUTO = LanguageSettings.AUTO
private const val LANGUAGE_ZH = LanguageSettings.ZH
private const val LANGUAGE_EN = LanguageSettings.EN

// Largest system font scale the panel follows. The panel is a fixed 280dp,
// so labels / hints / badges / button text stop growing at 1.15x and the
// result card's body text at 1.3x (it scrolls); a bigger system scale is
// not applied beyond that.
private const val LABEL_MAX_FONT_SCALE: Float = 1.15f
private const val RESULT_TEXT_MAX_FONT_SCALE: Float = 1.3f

// Mic key sizes (dp): idle / processing / result vs. the recording stop key.
private const val MIC_SIZE_DP: Float = 96f
private const val STOP_SIZE_DP: Float = 84f
private const val MIC_SIZE_ANIM_MS: Long = 120

/**
 * Fork6 redesigned keyboard panel. The whole panel is driven by one
 * [UiState]; [render] is the only place that decides what is visible,
 * so no screen part has its own scattered visibility logic.
 */
class WhisperKeyboard {
    private enum class UiState {
        Idle,        // Ready to start recording
        Recording,   // Currently recording
        Processing,  // Waiting for transcription / polishing
        Result,      // Result card (raw vs polished) with quick actions
    }

    private enum class ProcessingStep { Transcribing, Polishing }

    // Keyboard event listeners. Assignable custom behaviors upon certain UI events (user-operated).
    private var onStartRecording: () -> Unit = { }
    private var onCancelRecording: () -> Unit = { }
    private var onStartTranscribing: (attachToEnd: String) -> Unit = { }
    private var onCancelTranscribing: () -> Unit = { }
    private var onButtonBackspace: () -> Unit = { }
    private var onButtonBackspaceWord: () -> Unit = { }
    private var onSwitchIme: () -> Unit = { }
    // Fork change: long-press on the switch key shows the system IME picker.
    private var onShowInputMethodPicker: () -> Unit = { }
    private var onOpenSettings: () -> Unit = { }
    private var onEnter: () -> Unit = { }
    private var onSpaceBar: () -> Unit = { }
    private var shouldShowRetry: () -> Boolean = { false }
    // Fork6: result-card actions that need the input connection / network.
    private var onInsertText: (String) -> Unit = { }
    // Returns false when the text before the cursor no longer matches
    // what was inserted, in which case nothing was deleted.
    private var onUndoInsert: (String) -> Boolean = { false }
    private var onRepolish: (raw: String, done: (String?) -> Unit) -> Unit = { _, done -> done(null) }

    // ---- state ----
    private var state: UiState = UiState.Idle
    private var processingStep: ProcessingStep = ProcessingStep.Transcribing
    private var showPolishStep: Boolean = true
    private var repolishing: Boolean = false
    private var stagedRaw: String? = null

    // What polishing did for the result on screen, and whether the (clamped)
    // original is expanded.
    private var polishNote: PolishNote = PolishNote.OFF
    private var rawExpanded: Boolean = false

    // Last record (what the history file holds) and the result card.
    private var rawResultText: String? = null
    private var polishedResultText: String? = null
    private val lastResultText: String? get() = polishedResultText ?: rawResultText
    // Text that is currently sitting in the editor because of this card
    // (null = nothing to undo). Undo deletes exactly this many chars.
    private var insertedText: String? = null
    // The result text (without the space / newline suffix) that
    // insertedText corresponds to, so "insert" is only offered for a text
    // that is not already in the editor.
    private var insertedBase: String? = null
    // Where the cursor sat right after the insert (-1 = not seen yet); a
    // cursor anywhere else means the card no longer owns that text.
    private var insertedCursor: Int = -1

    private var polishEnabled: Boolean = true
    private var languageCode: String = LANGUAGE_AUTO
    private var customLanguageCode: String? = null

    // Mic key size animation (layout size, not scale: the stop square and
    // the pulse rings must keep their dp sizes).
    private var micSizeDp: Float = MIC_SIZE_DP
    private var micSizeAnimator: ValueAnimator? = null
    private var offerImeSwitch: Boolean = true

    // Mic gesture bookkeeping
    private var micDownTime: Long = 0
    private var micDownY: Float = 0f
    private var micStartedRecording: Boolean = false
    private var cancelArmed: Boolean = false

    // Recording visuals
    private val handler = Handler(Looper.getMainLooper())
    private var recordingStartMs: Long = 0
    private var levelTarget: Float = 0f
    private var levelSmoothed: Float = 0f
    private var shownSeconds: Int = -1
    private val ticker = object : Runnable {
        override fun run() {
            if (state != UiState.Recording) return
            levelTarget *= LEVEL_DECAY_PER_TICK
            levelSmoothed = levelSmoothed * 0.5f + levelTarget * 0.5f
            waveLeft?.pushLevel(jitter(levelSmoothed))
            waveRight?.pushLevel(jitter(levelSmoothed))
            val seconds = ((SystemClock.uptimeMillis() - recordingStartMs) / 1000).toInt()
            if (seconds != shownSeconds) {
                shownSeconds = seconds
                previewTimer?.text = formatTimer(seconds)
            }
            handler.postDelayed(this, WAVEFORM_TICK_MS)
        }
    }

    // ---- views ----
    private var keyboardView: View? = null
    private var previewChip: LinearLayout? = null
    private var previewTag: TextView? = null
    private var recordingDot: View? = null
    private var previewText: TextView? = null
    private var previewTimer: TextView? = null
    private var buttonPreviewInsert: ImageButton? = null
    private var buttonPolishToggle: LinearLayout? = null
    private var polishIcon: ImageView? = null
    private var polishLabel: TextView? = null
    private var polishSwitch: ImageView? = null
    private var buttonSettings: ImageButton? = null

    private var groupMain: View? = null
    private var groupResult: View? = null
    private var keyLanguageCol: View? = null
    private var buttonLanguage: ImageButton? = null
    private var labelLanguage: TextView? = null
    private var keyHistoryCol: View? = null
    private var buttonHistory: ImageButton? = null
    private var waveLeft: WaveformView? = null
    private var waveRight: WaveformView? = null
    private var pulseRings: PulseRingView? = null
    private var spinnerRing: SpinnerRingView? = null
    private var buttonMic: ImageButton? = null
    private var labelHint: TextView? = null
    private var stepsContainer: View? = null
    private var stepRecord: TextView? = null
    private var stepTranscribe: TextView? = null
    private var stepPolish: TextView? = null
    private var buttonRetry: TextView? = null

    private var rowAi: View? = null
    private var rowNotice: View? = null
    private var textNotice: TextView? = null
    private var buttonNoticeAction: TextView? = null
    private var textNoChange: TextView? = null
    private var tagRaw: View? = null
    private var textRaw: TextView? = null
    private var textAi: TextView? = null
    private var actionInsertIcon: ImageView? = null
    private var actionInsertLabel: TextView? = null
    private var actionInsert: View? = null
    private var actionRepolish: View? = null
    private var actionCopy: View? = null
    private var actionUndo: View? = null
    private var actionsRow: View? = null
    private var actionRepolishLabel: View? = null
    private var actionCopyLabel: View? = null
    private var actionUndoLabel: View? = null
    private var resultScroll: ScrollView? = null

    private var buttonLeftAction: ImageButton? = null
    private var buttonSpaceBar: View? = null
    private var buttonBackspace: BackspaceButton? = null
    private var buttonEnter: ImageButton? = null

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
        onButtonBackspaceWord: () -> Unit = { },
        onInsertText: (String) -> Unit = { },
        onUndoInsert: (String) -> Boolean = { false },
        onRepolish: (raw: String, done: (String?) -> Unit) -> Unit = { _, done -> done(null) },
    ): View {
        val root = layoutInflater.inflate(R.layout.keyboard_view, null)
        keyboardView = root
        offerImeSwitch = shouldOfferImeSwitch

        previewChip = root.findViewById(R.id.preview_chip)
        previewTag = root.findViewById(R.id.preview_tag)
        recordingDot = root.findViewById(R.id.recording_dot)
        previewText = root.findViewById(R.id.preview_text)
        previewTimer = root.findViewById(R.id.preview_timer)
        buttonPreviewInsert = root.findViewById(R.id.btn_preview_insert)
        buttonPolishToggle = root.findViewById(R.id.btn_polish_toggle)
        polishIcon = root.findViewById(R.id.polish_icon)
        polishLabel = root.findViewById(R.id.polish_label)
        polishSwitch = root.findViewById(R.id.polish_switch)
        buttonSettings = root.findViewById(R.id.btn_settings)

        groupMain = root.findViewById(R.id.group_main)
        groupResult = root.findViewById(R.id.group_result)
        keyLanguageCol = root.findViewById(R.id.key_language_col)
        buttonLanguage = root.findViewById(R.id.btn_language)
        labelLanguage = root.findViewById(R.id.label_language)
        keyHistoryCol = root.findViewById(R.id.key_history_col)
        buttonHistory = root.findViewById(R.id.btn_history)
        waveLeft = root.findViewById<WaveformView>(R.id.wave_left).also { it.newestOnRight = true }
        waveRight = root.findViewById<WaveformView>(R.id.wave_right).also { it.newestOnRight = false }
        pulseRings = root.findViewById(R.id.pulse_rings)
        spinnerRing = root.findViewById(R.id.spinner_ring)
        buttonMic = root.findViewById(R.id.btn_mic)
        labelHint = root.findViewById(R.id.label_hint)
        stepsContainer = root.findViewById(R.id.steps_container)
        stepRecord = root.findViewById(R.id.step_record)
        stepTranscribe = root.findViewById(R.id.step_transcribe)
        stepPolish = root.findViewById(R.id.step_polish)
        buttonRetry = root.findViewById(R.id.btn_retry)

        rowAi = root.findViewById(R.id.row_ai)
        rowNotice = root.findViewById(R.id.row_notice)
        textNotice = root.findViewById(R.id.text_notice)
        buttonNoticeAction = root.findViewById(R.id.btn_notice_action)
        textNoChange = root.findViewById(R.id.text_no_change)
        tagRaw = root.findViewById(R.id.tag_raw)
        textRaw = root.findViewById(R.id.text_raw)
        textAi = root.findViewById(R.id.text_ai)
        actionInsertIcon = root.findViewById(R.id.action_insert_icon)
        actionInsertLabel = root.findViewById(R.id.action_insert_label)
        actionInsert = root.findViewById(R.id.action_insert)
        actionRepolish = root.findViewById(R.id.action_repolish)
        actionCopy = root.findViewById(R.id.action_copy)
        actionUndo = root.findViewById(R.id.action_undo)
        actionsRow = root.findViewById(R.id.row_actions)
        actionRepolishLabel = root.findViewById(R.id.action_repolish_label)
        actionCopyLabel = root.findViewById(R.id.action_copy_label)
        actionUndoLabel = root.findViewById(R.id.action_undo_label)
        resultScroll = root.findViewById(R.id.result_scroll)

        buttonLeftAction = root.findViewById(R.id.btn_left_action)
        buttonSpaceBar = root.findViewById(R.id.btn_space_bar)
        buttonBackspace = root.findViewById(R.id.btn_backspace)
        buttonEnter = root.findViewById(R.id.btn_enter)

        // Event listeners supplied by the service
        this.onStartRecording = onStartRecording
        this.onCancelRecording = onCancelRecording
        this.onStartTranscribing = onStartTranscribing
        this.onCancelTranscribing = onCancelTranscribing
        this.onButtonBackspace = onButtonBackspace
        this.onButtonBackspaceWord = onButtonBackspaceWord
        this.onSwitchIme = onSwitchIme
        this.onShowInputMethodPicker = onShowInputMethodPicker
        this.onOpenSettings = onOpenSettings
        this.onEnter = onEnter
        this.onSpaceBar = onSpaceBar
        this.shouldShowRetry = shouldShowRetry
        this.onInsertText = onInsertText
        this.onUndoInsert = onUndoInsert
        this.onRepolish = onRepolish

        capFontScale(root, root.resources.configuration.fontScale)
        applyNavigationBarInset(root)
        setupMicGesture()
        buttonLeftAction!!.setOnClickListener { onLeftActionClick() }
        buttonLeftAction!!.setOnLongClickListener { onLeftActionLongClick() }
        buttonSpaceBar!!.setOnClickListener { onButtonSpaceBarClick() }
        buttonEnter!!.setOnClickListener { onButtonEnterClick() }
        buttonBackspace!!.setBackspaceCallback(
            { clearInserted(); this.onButtonBackspace() },
            { clearInserted(); this.onButtonBackspaceWord() }
        )
        buttonSettings!!.setOnClickListener { this.onOpenSettings() }
        buttonPolishToggle!!.setOnClickListener { onButtonPolishToggleClick() }
        buttonLanguage!!.setOnClickListener { onLanguageClick() }
        buttonHistory!!.setOnClickListener { onHistoryClick() }
        buttonPreviewInsert!!.setOnClickListener {
            clearInserted()
            lastResultText?.let { this.onInsertText(it) }
        }
        // Tapping the preview text still copies the last result (as before).
        previewText!!.setOnClickListener { if (state == UiState.Idle) copyText(lastResultText) }
        buttonRetry!!.setOnClickListener { onButtonRetryClick() }
        actionInsert!!.setOnClickListener { onActionInsert() }
        actionRepolish!!.setOnClickListener { onActionRepolish() }
        actionCopy!!.setOnClickListener { copyText(lastResultText) }
        actionUndo!!.setOnClickListener { onActionUndo() }
        // Retry / force the polish only (the recording is not repeated).
        buttonNoticeAction!!.setOnClickListener { onActionRepolish() }
        // The original is clamped to two lines next to a polished text;
        // tapping it expands / collapses it.
        textRaw!!.setOnClickListener {
            if (state == UiState.Result && !polishedResultText.isNullOrEmpty()) {
                rawExpanded = !rawExpanded
                render()
            }
        }

        // Resets keyboard upon setup
        reset()

        return root
    }

    // =====================================================================
    // Public entry points used by WhisperInputService
    // =====================================================================

    fun reset() {
        handler.removeCallbacks(ticker)
        repolishing = false
        stagedRaw = null
        insertedText = null
        insertedBase = null
        insertedCursor = -1
        refreshLastRecord()
        setState(UiState.Idle)
        // Re-read the toggle and the language on every reset (the service
        // calls reset() when the keyboard window is shown), so a change
        // made in the settings page is picked up without a view rebuild.
        refreshPolishToggle()
        refreshLanguage()
    }

    /**
     * The transcription finished and [insertedText] was committed to the
     * editor. [raw] and [polished] are handed over directly by the
     * transcriber (not read back from the history file, which may have
     * failed to save); [insertedText] is what "undo" will delete again.
     */
    fun showResult(raw: String, polished: String?, insertedText: String?, note: PolishNote) {
        rawResultText = raw
        polishedResultText = polished?.takeIf { it.isNotEmpty() }
        polishNote = note
        rawExpanded = false
        val inserted = insertedText?.takeIf { it.isNotEmpty() }
        this.insertedText = inserted
        this.insertedBase = if (inserted != null) lastResultText else null
        this.insertedCursor = -1
        repolishing = false
        stagedRaw = null
        resultScroll?.scrollTo(0, 0)
        setState(UiState.Result)
    }

    /** Stage 1: STT finished, show the raw text in the preview chip. */
    fun showTranscriptionStage(text: String?) {
        val trimmed = text?.trim()
        if (trimmed.isNullOrEmpty() || state != UiState.Processing) return
        stagedRaw = trimmed
        render()
    }

    /** Stage 2: an LLM polish call is in flight. */
    fun showPolishingStage() {
        if (state != UiState.Processing) return
        processingStep = ProcessingStep.Polishing
        render()
    }

    fun updateMicrophoneAmplitude(amplitude: Int) {
        if (state != UiState.Recording) {
            return
        }
        val clampedAmplitude = MathUtils.clamp(
            amplitude,
            AMPLITUDE_CLAMP_MIN,
            AMPLITUDE_CLAMP_MAX
        )
        // decibel-like calculation, 0..1
        val normalizedPower =
            (log10(clampedAmplitude * 1f) - LOG_10_10) / (LOG_10_25000 - LOG_10_10)
        levelTarget = maxOf(levelTarget, normalizedPower.coerceIn(0f, 1f))
    }

    fun tryStartRecording() {
        if (state == UiState.Idle) {
            startRecording()
        }
    }

    fun tryCancelRecording() {
        if (state == UiState.Recording) {
            cancelRecording()
        }
    }

    fun tryStartTranscribing(attachToEnd: String) {
        if (state == UiState.Recording) {
            beginProcessing()
            onStartTranscribing(attachToEnd)
        }
    }

    // =====================================================================
    // State transitions
    // =====================================================================

    private fun setState(newState: UiState) {
        state = newState
        handler.removeCallbacks(ticker)
        if (newState == UiState.Recording) {
            recordingStartMs = SystemClock.uptimeMillis()
            levelTarget = 0f
            levelSmoothed = 0f
            shownSeconds = -1
            cancelArmed = false
            waveLeft?.clear()
            waveRight?.clear()
            handler.post(ticker)
        }
        render()
    }

    private fun startRecording() {
        insertedText = null
        insertedBase = null
        insertedCursor = -1
        setState(UiState.Recording)
        onStartRecording()
    }

    private fun cancelRecording() {
        setState(UiState.Idle)
        onCancelRecording()
    }

    private fun beginProcessing() {
        processingStep = ProcessingStep.Transcribing
        showPolishStep = polishEnabled
        repolishing = false
        stagedRaw = null
        setState(UiState.Processing)
    }

    // =====================================================================
    // The one place that decides what is on screen
    // =====================================================================

    private fun render() {
        val view = keyboardView ?: return
        val context = view.context
        val s = state

        renderTopRow(context, s)

        groupMain?.visibility = if (s == UiState.Result) View.GONE else View.VISIBLE
        groupResult?.visibility = if (s == UiState.Result) View.VISIBLE else View.GONE

        keyLanguageCol?.visibility = if (s == UiState.Idle) View.VISIBLE else View.GONE
        keyHistoryCol?.visibility = if (s == UiState.Idle) View.VISIBLE else View.GONE
        waveLeft?.visibility = if (s == UiState.Recording) View.VISIBLE else View.GONE
        waveRight?.visibility = if (s == UiState.Recording) View.VISIBLE else View.GONE
        pulseRings?.visibility = if (s == UiState.Recording) View.VISIBLE else View.GONE
        spinnerRing?.visibility = if (s == UiState.Processing) View.VISIBLE else View.GONE

        renderMic(context, s)
        renderHintRow(context, s)
        renderBottomRow(context, s)
        if (s == UiState.Result) renderResultCard(context)

        view.keepScreenOn = s == UiState.Recording || s == UiState.Processing
    }

    private fun renderTopRow(context: Context, s: UiState) {
        val chip = previewChip ?: return
        val tag = previewTag ?: return
        val text = previewText ?: return
        when (s) {
            UiState.Idle -> {
                chip.setBackgroundResource(R.drawable.kb_bg_chip)
                tag.visibility = View.VISIBLE
                tag.setText(R.string.kb_tag_last)
                recordingDot?.visibility = View.GONE
                previewTimer?.visibility = View.GONE
                text.text = lastResultText ?: context.getString(R.string.kb_no_last_result)
                text.setTextColor(color(context, R.color.kb_on_surface))
                buttonPreviewInsert?.visibility =
                    if (lastResultText != null) View.VISIBLE else View.GONE
            }

            UiState.Recording -> {
                chip.setBackgroundResource(R.drawable.kb_bg_chip_recording)
                tag.visibility = View.GONE
                recordingDot?.visibility = View.VISIBLE
                previewTimer?.visibility = View.VISIBLE
                previewTimer?.text = formatTimer(0)
                text.setText(R.string.kb_recording_label)
                text.setTextColor(color(context, R.color.kb_recording))
                buttonPreviewInsert?.visibility = View.GONE
            }

            UiState.Processing -> {
                chip.setBackgroundResource(R.drawable.kb_bg_chip)
                tag.visibility = View.VISIBLE
                tag.setText(R.string.kb_tag_processing)
                recordingDot?.visibility = View.GONE
                previewTimer?.visibility = View.GONE
                text.text = stagedRaw ?: context.getString(
                    if (processingStep == ProcessingStep.Polishing) R.string.kb_step_polishing
                    else R.string.kb_step_transcribing
                )
                text.setTextColor(color(context, R.color.kb_on_surface))
                buttonPreviewInsert?.visibility = View.GONE
            }

            UiState.Result -> {
                chip.setBackgroundResource(R.drawable.kb_bg_chip)
                tag.visibility = View.VISIBLE
                tag.setText(R.string.kb_tag_done)
                recordingDot?.visibility = View.GONE
                previewTimer?.visibility = View.GONE
                text.setText(
                    if (insertedText != null) R.string.kb_result_hint_inserted
                    else R.string.kb_result_hint_insert
                )
                text.setTextColor(color(context, R.color.kb_on_surface))
                buttonPreviewInsert?.visibility = View.GONE
            }
        }
    }

    private fun renderMic(context: Context, s: UiState) {
        val mic = buttonMic ?: return
        when (s) {
            UiState.Idle, UiState.Result -> {
                mic.setBackgroundResource(R.drawable.kb_bg_mic_idle)
                mic.scaleType = ImageView.ScaleType.FIT_CENTER
                mic.setImageResource(R.drawable.kb_ic_mic)
                mic.imageTintList = tint(context, R.color.kb_on_primary)
                mic.contentDescription = context.getString(R.string.start_speech_to_text)
                // 96dp key with a 44dp icon.
                setMicPadding(mic, 26f)
                mic.elevation = dpToPx(mic, 3f)
                animateMicSize(MIC_SIZE_DP)
            }

            UiState.Recording -> {
                mic.setBackgroundResource(R.drawable.kb_bg_mic_recording)
                mic.scaleType = ImageView.ScaleType.CENTER
                mic.setImageResource(R.drawable.kb_stop_square)
                mic.imageTintList = null
                mic.contentDescription = context.getString(R.string.start_transcribing)
                // 84dp circle, the 28dp stop square drawn at its own size.
                setMicPadding(mic, 28f)
                mic.elevation = dpToPx(mic, 3f)
                animateMicSize(STOP_SIZE_DP)
            }

            UiState.Processing -> {
                mic.setBackgroundResource(R.drawable.kb_bg_mic_processing)
                mic.scaleType = ImageView.ScaleType.FIT_CENTER
                mic.setImageResource(R.drawable.kb_ic_sparkle)
                mic.imageTintList = tint(context, R.color.kb_on_primary_container)
                mic.contentDescription = null
                // 96dp key with a 40dp sparkle, flat (no shadow).
                setMicPadding(mic, 28f)
                mic.elevation = 0f
                animateMicSize(MIC_SIZE_DP)
            }
        }
    }

    private fun setMicPadding(mic: View, dp: Float) {
        val px = dpToPx(mic, dp).toInt()
        mic.setPadding(px, px, px, px)
    }

    private fun dpToPx(view: View, dp: Float): Float = dp * view.resources.displayMetrics.density

    // Smooth 96 <-> 84dp change of the key's real layout size (it is
    // centred in the fixed 132dp mic frame, so nothing around it moves).
    private fun animateMicSize(targetDp: Float) {
        val mic = buttonMic ?: return
        if (micSizeDp == targetDp && micSizeAnimator?.isRunning != true) return
        micSizeAnimator?.cancel()
        val animator = ValueAnimator.ofFloat(micSizeDp, targetDp).apply {
            duration = MIC_SIZE_ANIM_MS
            addUpdateListener {
                micSizeDp = it.animatedValue as Float
                val px = dpToPx(mic, micSizeDp).toInt()
                val lp = mic.layoutParams
                lp.width = px
                lp.height = px
                mic.layoutParams = lp
            }
        }
        micSizeAnimator = animator
        animator.start()
    }

    private fun renderHintRow(context: Context, s: UiState) {
        val hint = labelHint ?: return
        val steps = stepsContainer ?: return
        when (s) {
            UiState.Idle, UiState.Recording -> {
                steps.visibility = View.GONE
                hint.visibility = View.VISIBLE
                hint.setText(
                    when {
                        s == UiState.Idle -> R.string.kb_hint_idle
                        cancelArmed -> R.string.kb_hint_cancel_armed
                        else -> R.string.kb_hint_recording
                    }
                )
            }

            UiState.Processing -> {
                hint.visibility = View.GONE
                steps.visibility = View.VISIBLE
                renderStepChips(context)
            }

            UiState.Result -> {
                hint.visibility = View.GONE
                steps.visibility = View.GONE
            }
        }
        buttonRetry?.visibility =
            if (s == UiState.Idle && shouldShowRetry()) View.VISIBLE else View.GONE
    }

    private fun renderStepChips(context: Context) {
        val transcribe = stepTranscribe ?: return
        val polish = stepPolish ?: return
        val transcribing = processingStep == ProcessingStep.Transcribing
        val polishing = processingStep == ProcessingStep.Polishing
        stepRecord?.text = context.getString(R.string.kb_step_record_done)

        transcribe.text = context.getString(
            if (transcribing) R.string.kb_step_transcribing else R.string.kb_step_transcribe_done
        )
        styleStepChip(context, transcribe, active = transcribing)

        // The polish step is only listed when polishing is on (or this is
        // an explicit re-polish); with polishing off there is no such step.
        polish.visibility = if (showPolishStep || polishing) View.VISIBLE else View.GONE
        polish.text = context.getString(
            if (polishing) R.string.kb_step_polishing else R.string.kb_step_polish
        )
        styleStepChip(context, polish, active = polishing)
    }

    private fun styleStepChip(context: Context, chip: TextView, active: Boolean) {
        chip.setBackgroundResource(
            if (active) R.drawable.kb_bg_step_chip_active else R.drawable.kb_bg_step_chip
        )
        chip.setTextColor(
            color(context, if (active) R.color.kb_on_primary_container else R.color.kb_on_surface_variant)
        )
        chip.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun renderBottomRow(context: Context, s: UiState) {
        val left = buttonLeftAction ?: return
        when (s) {
            UiState.Idle -> {
                left.visibility = if (offerImeSwitch) View.VISIBLE else View.INVISIBLE
                left.setBackgroundResource(R.drawable.kb_bg_key_alt)
                left.setImageResource(R.drawable.kb_ic_keyboard)
                left.imageTintList = tint(context, R.color.kb_on_surface_variant)
                left.contentDescription = context.getString(R.string.btn_previous_ime_hint)
            }

            UiState.Recording, UiState.Processing -> {
                // Same look while recording and while processing: keyAlt
                // key, recording-red cross.
                left.visibility = View.VISIBLE
                left.setBackgroundResource(R.drawable.kb_bg_key_alt)
                left.setImageResource(R.drawable.kb_ic_close)
                left.imageTintList = tint(context, R.color.kb_recording)
                left.contentDescription = context.getString(R.string.kb_cancel)
            }

            UiState.Result -> {
                left.visibility = View.VISIBLE
                left.setBackgroundResource(R.drawable.kb_bg_key_primary)
                left.setImageResource(R.drawable.kb_ic_mic)
                left.imageTintList = tint(context, R.color.kb_on_primary)
                left.contentDescription = context.getString(R.string.start_speech_to_text)
            }
        }
    }

    private fun renderResultCard(context: Context) {
        val raw = rawResultText.orEmpty()
        val polished = polishedResultText
        val hasPolished = !polished.isNullOrEmpty()

        // Polished text on top (it is what gets inserted), the original
        // under it in grey: two lines, tap to expand, no strike-through.
        // Without a polished text there is one text only, in full.
        rowAi?.visibility = if (hasPolished) View.VISIBLE else View.GONE
        textAi?.text = polished.orEmpty()
        textNoChange?.visibility =
            if (hasPolished && polished == raw) View.VISIBLE else View.GONE
        tagRaw?.visibility = if (hasPolished) View.VISIBLE else View.GONE
        textRaw?.let {
            it.text = raw
            val clamp = hasPolished && !rawExpanded
            it.maxLines = if (clamp) 2 else Int.MAX_VALUE
            it.ellipsize = if (clamp) android.text.TextUtils.TruncateAt.END else null
            it.setTextColor(
                color(context, if (hasPolished) R.color.kb_on_surface_variant else R.color.kb_on_surface)
            )
            val lp = it.layoutParams as ViewGroup.MarginLayoutParams
            val start = if (hasPolished) (10f * context.resources.displayMetrics.density).toInt() else 0
            if (lp.marginStart != start) {
                lp.marginStart = start
                it.layoutParams = lp
            }
        }

        // Polishing did not give a text although it was on: say why, never
        // just show the raw text as if nothing happened.
        val showNotice = !hasPolished &&
            (polishNote == PolishNote.FAILED || polishNote == PolishNote.TOO_SHORT)
        rowNotice?.visibility = if (showNotice) View.VISIBLE else View.GONE
        if (showNotice) {
            val failed = polishNote == PolishNote.FAILED
            textNotice?.setText(if (failed) R.string.kb_polish_failed else R.string.kb_polish_too_short)
            textNotice?.setTextColor(
                color(context, if (failed) R.color.kb_recording else R.color.kb_on_surface_variant)
            )
            buttonNoticeAction?.setText(if (failed) R.string.kb_polish_retry else R.string.kb_polish_force)
        }

        // Insert is only useful while the current text is not already the
        // one sitting in the editor; undo only while something is there.
        val hasText = !lastResultText.isNullOrEmpty()
        renderInsertButton(context, inserted = hasText && insertedBase == lastResultText, hasText = hasText)
        setActionEnabled(actionUndo, insertedText != null)
        setActionEnabled(actionRepolish, raw.isNotEmpty())
        setActionEnabled(actionCopy, !lastResultText.isNullOrEmpty())
        fitResultActions()
    }

    // The four buttons must never overflow or be cut off, whatever the
    // screen width or font scale: if they do not fit with their labels,
    // repolish / copy / undo drop to icon-only (48dp wide); their
    // contentDescription (set in the layout) keeps them readable by TalkBack.
    private fun fitResultActions() {
        val row = actionsRow as? ViewGroup ?: return
        val card = groupResult ?: return
        val root = keyboardView ?: return
        val density = root.resources.displayMetrics.density
        val labels = listOf(actionRepolishLabel, actionCopyLabel, actionUndoLabel)
        val iconOnly = listOf(actionRepolish, actionCopy, actionUndo)
        labels.forEach { it?.visibility = View.VISIBLE }
        iconOnly.forEach { it?.minimumWidth = 0 }
        // panel padding 8dp x2, card padding 12dp x2 and its 1dp border
        val available = root.width - (16f + 2f) * density - card.paddingLeft - card.paddingRight
        if (available <= 0f) return
        var total = 0
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            child.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val lp = child.layoutParams as ViewGroup.MarginLayoutParams
            total += child.measuredWidth + lp.marginStart + lp.marginEnd
        }
        if (total > available) {
            labels.forEach { it?.visibility = View.GONE }
            iconOnly.forEach { it?.minimumWidth = (48f * density).toInt() }
        }
    }

    // "Insert" is a solid primary button until the text is in the editor;
    // then it turns into a tonal "✓ 已插入" that is really disabled.
    private fun renderInsertButton(context: Context, inserted: Boolean, hasText: Boolean) {
        val button = actionInsert ?: return
        val icon = actionInsertIcon ?: return
        val label = actionInsertLabel ?: return
        if (inserted) {
            button.setBackgroundResource(R.drawable.kb_bg_action_tonal_touch)
            icon.setImageResource(R.drawable.kb_ic_check)
            icon.imageTintList = tint(context, R.color.kb_on_primary_container)
            label.setText(R.string.kb_action_inserted)
            label.setTextColor(color(context, R.color.kb_on_primary_container))
            button.isEnabled = false
            button.alpha = 1f
        } else {
            button.setBackgroundResource(R.drawable.kb_bg_action_primary_touch)
            icon.setImageResource(R.drawable.kb_ic_insert)
            icon.imageTintList = tint(context, R.color.kb_on_primary)
            label.setText(R.string.kb_action_insert)
            label.setTextColor(color(context, R.color.kb_on_primary))
            setActionEnabled(button, hasText)
        }
    }

    private fun setActionEnabled(action: View?, enabled: Boolean) {
        action ?: return
        action.isEnabled = enabled
        action.alpha = if (enabled) 1f else 0.4f
    }

    // =====================================================================
    // Mic gesture: tap = start / stop, hold = talk (release sends),
    // swipe up past 48dp while recording = cancel.
    // =====================================================================

    private fun setupMicGesture() {
        val mic = buttonMic ?: return
        // Accessibility services click the view instead of touching it.
        mic.setOnClickListener {
            when (state) {
                UiState.Idle -> startRecording()
                UiState.Recording -> stopAndTranscribe()
                else -> Unit
            }
        }
        mic.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    micDownTime = SystemClock.uptimeMillis()
                    micDownY = event.y
                    cancelArmed = false
                    micStartedRecording = false
                    if (state == UiState.Idle) {
                        micStartedRecording = true
                        startRecording()
                    }
                    v.isPressed = true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (state == UiState.Recording) {
                        val armed = micDownY - event.y > SWIPE_CANCEL_DP * v.resources.displayMetrics.density
                        if (armed != cancelArmed) {
                            cancelArmed = armed
                            labelHint?.setText(
                                if (armed) R.string.kb_hint_cancel_armed else R.string.kb_hint_recording
                            )
                        }
                    }
                }

                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (state == UiState.Recording) {
                        val held = SystemClock.uptimeMillis() - micDownTime >= HOLD_TO_TALK_MS
                        when {
                            cancelArmed -> cancelRecording()
                            // Pressed while idle: a quick tap keeps recording
                            // (tap-to-start); a hold is hold-to-talk, so
                            // releasing sends.
                            micStartedRecording -> if (held) stopAndTranscribe()
                            // Pressed while already recording: tap = stop.
                            else -> stopAndTranscribe()
                        }
                    }
                    cancelArmed = false
                }

                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    cancelArmed = false
                    // The system took the touch away (e.g. a gesture or a
                    // dialog) before release. If this touch started the
                    // recording, there is no release to send it, so it is
                    // cancelled instead of left recording on its own.
                    if (micStartedRecording && state == UiState.Recording) cancelRecording()
                    micStartedRecording = false
                }
            }
            true
        }
    }

    private fun stopAndTranscribe() {
        if (state != UiState.Recording) return
        beginProcessing()
        onStartTranscribing("")
    }

    // =====================================================================
    // Button handlers
    // =====================================================================

    private fun onLeftActionClick() {
        when (state) {
            UiState.Idle -> onSwitchIme()
            UiState.Recording -> cancelRecording()
            UiState.Processing -> cancelProcessing()
            UiState.Result -> startRecording()
        }
    }

    private fun onLeftActionLongClick(): Boolean {
        if (state != UiState.Idle) return false
        // Fork change: long-press shows the system IME picker so the user
        // can pick any enabled IME, not just the previous one.
        buttonLeftAction?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onShowInputMethodPicker()
        return true
    }

    private fun cancelProcessing() {
        onCancelTranscribing()
        val cameFromResult = repolishing
        repolishing = false
        stagedRaw = null
        if (cameFromResult && !rawResultText.isNullOrEmpty()) {
            setState(UiState.Result)
        } else {
            setState(UiState.Idle)
        }
    }

    private fun onButtonSpaceBarClick() {
        // Recording -> Start transcribing (with a whitespace included)
        // else -> invokes onSpaceBar
        if (state == UiState.Recording) {
            beginProcessing()
            onStartTranscribing(" ")
        } else {
            clearInserted()
            onSpaceBar()
        }
    }

    private fun onButtonEnterClick() {
        // Recording -> Start transcribing (with a newline included)
        // else -> invokes onEnter
        if (state == UiState.Recording) {
            beginProcessing()
            onStartTranscribing("\r\n")
        } else {
            clearInserted()
            onEnter()
        }
    }

    private fun onButtonRetryClick() {
        // Idle -> retry the recording that is still on disk
        if (state == UiState.Idle) {
            beginProcessing()
            onStartTranscribing("")
        }
    }

    private fun onHistoryClick() {
        if (state != UiState.Idle) return
        refreshLastRecord()
        if (rawResultText.isNullOrEmpty()) {
            toast(R.string.kb_toast_no_history)
            return
        }
        // Showing the last result again: nothing of it is in the editor
        // yet as far as this card knows, so insert is offered, undo is not.
        insertedText = null
        insertedBase = null
        // The history file keeps only what was polished; a failure is not stored.
        polishNote = if (polishedResultText.isNullOrEmpty()) PolishNote.OFF else PolishNote.OK
        rawExpanded = false
        resultScroll?.scrollTo(0, 0)
        setState(UiState.Result)
    }

    private fun onActionInsert() {
        val text = lastResultText ?: return
        if (insertedBase == text) return
        onInsertText(text)
        insertedText = text
        insertedBase = text
        insertedCursor = -1
        render()
    }

    private fun onActionUndo() {
        val inserted = insertedText ?: return
        // The service only deletes when the text before the cursor is
        // still exactly what was inserted; either way the card gives up
        // the text afterwards, so undo is disabled.
        val removed = onUndoInsert(inserted)
        insertedText = null
        insertedBase = null
        insertedCursor = -1
        toast(if (removed) R.string.kb_toast_undone else R.string.kb_toast_undo_failed)
        render()
    }

    /**
     * The editor's selection changed (the service forwards
     * onUpdateSelection). The first change after an insert is the insert
     * itself and only records where the cursor is; any later change means
     * the cursor was moved or the text edited, so undo is withdrawn.
     */
    fun onSelectionChanged(selStart: Int, selEnd: Int) {
        if (insertedText == null) return
        if (selStart != selEnd) {
            clearInserted()
        } else if (insertedCursor < 0) {
            insertedCursor = selEnd
        } else if (selEnd != insertedCursor) {
            clearInserted()
        }
    }

    /** Forget the inserted text: undo is disabled and insert is offered again. */
    fun clearInserted() {
        if (insertedText == null && insertedBase == null) return
        insertedText = null
        insertedBase = null
        insertedCursor = -1
        if (state == UiState.Result) render()
    }

    private fun onActionRepolish() {
        val raw = rawResultText?.takeIf { it.isNotEmpty() } ?: return
        processingStep = ProcessingStep.Polishing
        showPolishStep = true
        repolishing = true
        stagedRaw = raw
        setState(UiState.Processing)
        onRepolish(raw) { polished ->
            // Ignore a late answer after the user cancelled or left.
            if (state != UiState.Processing || !repolishing) return@onRepolish
            repolishing = false
            stagedRaw = null
            if (polished.isNullOrEmpty()) {
                if (polishedResultText.isNullOrEmpty()) {
                    // Still nothing polished: keep saying so on the card.
                    polishNote = PolishNote.FAILED
                } else {
                    toast(R.string.kb_toast_repolish_failed)
                }
            } else {
                polishedResultText = polished
                polishNote = PolishNote.OK
                rawExpanded = false
            }
            resultScroll?.scrollTo(0, 0)
            setState(UiState.Result)
        }
    }

    // =====================================================================
    // Settings-backed bits: polish toggle and language
    // =====================================================================

    private fun updatePolishToggleButton() {
        val toggle = buttonPolishToggle ?: return
        val context = toggle.context
        val on = polishEnabled
        toggle.setBackgroundResource(if (on) R.drawable.kb_bg_pill_on else R.drawable.kb_bg_pill_off)
        polishSwitch?.setImageResource(if (on) R.drawable.kb_switch_on else R.drawable.kb_switch_off)
        val content = color(context, if (on) R.color.kb_on_tertiary_container else R.color.kb_on_surface_variant)
        polishLabel?.setTextColor(content)
        polishIcon?.imageTintList = ColorStateList.valueOf(content)
        toggle.contentDescription = context.getString(
            if (on) R.string.polish_toggle_on else R.string.polish_toggle_off
        )
    }

    private fun refreshPolishToggle() {
        val context = keyboardView?.context ?: return
        CoroutineScope(Dispatchers.Main).launch {
            try {
                polishEnabled = context.dataStore.data.map { preferences ->
                    preferences[POLISH_ENABLED] ?: true
                }.first()
            } catch (_: Exception) {
                // Reading failure keeps the default (on).
                polishEnabled = true
            }
            updatePolishToggleButton()
        }
    }

    private fun onButtonPolishToggleClick() {
        val context = keyboardView?.context ?: return
        // Flip and paint immediately, then persist. The new value only
        // takes effect for the NEXT recording: WhisperTranscriber
        // snapshots POLISH_ENABLED once, before its STT request.
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

    private fun languageLabel(context: Context, code: String): String = when (code) {
        LANGUAGE_AUTO -> context.getString(R.string.kb_lang_auto)
        LANGUAGE_ZH -> context.getString(R.string.kb_lang_zh)
        LANGUAGE_EN -> context.getString(R.string.kb_lang_en)
        else -> code
    }

    private fun refreshLanguage() {
        val context = keyboardView?.context ?: return
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Only a code the API accepts is shown / cycled; anything
                // else (blank, "multi", garbage) counts as auto.
                languageCode = LanguageSettings.normalize(
                    context.dataStore.data.map { preferences ->
                        preferences[LANGUAGE_CODE] ?: LANGUAGE_AUTO
                    }.first()
                )
            } catch (_: Exception) {
                languageCode = LANGUAGE_AUTO
            }
            rememberCustomLanguage(context)
            labelLanguage?.text = languageLabel(context, languageCode)
        }
    }

    // A code from the settings page (e.g. ja) is not one of the three
    // built-in choices; keep it so cycling away from it does not lose it.
    // The settings page clears it again (LanguageSettings.syncCustomFromSetting)
    // when the code is set back to auto / zh / en.
    private fun rememberCustomLanguage(context: Context) {
        if (!LanguageSettings.isBuiltIn(languageCode)) {
            customLanguageCode = languageCode
            LanguageSettings.syncCustomFromSetting(context, languageCode)
        } else {
            customLanguageCode = LanguageSettings.loadCustom(context)
        }
    }

    // Language key: cycles the transcription language setting
    // (auto -> zh -> en -> the custom code from settings, if any -> auto),
    // the same LANGUAGE_CODE the settings page edits.
    private fun onLanguageClick() {
        val context = keyboardView?.context ?: return
        val cycle = listOfNotNull(LANGUAGE_AUTO, LANGUAGE_ZH, LANGUAGE_EN, customLanguageCode)
        val index = cycle.indexOf(languageCode)
        val next = if (index < 0) LANGUAGE_AUTO else cycle[(index + 1) % cycle.size]
        languageCode = next
        val label = languageLabel(context, next)
        labelLanguage?.text = label
        Toast.makeText(context, context.getString(R.string.kb_toast_language, label), Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                context.dataStore.edit { settings ->
                    settings[LANGUAGE_CODE] = next
                }
            } catch (_: Exception) {
                // Persist failure: the next reset() re-reads the saved value.
            }
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    // Fixed 280dp panel: stop the system font scale from pushing text past
    // the caps above. A text view keeps its XML sp size up to the cap and is
    // scaled down in px beyond it (never up).
    private fun capFontScale(view: View, fontScale: Float) {
        if (view is TextView && fontScale > 1f) {
            val cap = when (view.id) {
                R.id.text_raw, R.id.text_ai -> RESULT_TEXT_MAX_FONT_SCALE
                else -> LABEL_MAX_FONT_SCALE
            }
            if (fontScale > cap) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.textSize * cap / fontScale)
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) capFontScale(view.getChildAt(i), fontScale)
        }
    }

    // The panel's 8dp bottom margin is measured above the system gesture /
    // navigation bar: whatever bottom inset the window reports becomes
    // padding under the fixed 280dp panel (zero when the IME window already
    // sits above the bar).
    private fun applyNavigationBarInset(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            // Use the inset the bar has when shown (not what it is right now)
            // so a bar that hides / reappears can not change the panel height.
            val bottom = maxOf(
                insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom
            )
            if (v.paddingBottom != bottom) {
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottom)
            }
            insets
        }
    }

    private fun refreshLastRecord() {
        try {
            val context = keyboardView?.context ?: return
            val record = TranscriptionHistory.loadLast(context)
            rawResultText = record?.rawText
            polishedResultText = record?.polishedText
        } catch (_: Exception) {
            // Best-effort only; a missing record just shows the default.
        }
    }

    private fun copyText(text: String?) {
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

    private fun toast(@StringRes message: Int) {
        val context = keyboardView?.context ?: return
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun color(context: Context, @ColorRes id: Int): Int = ContextCompat.getColor(context, id)

    private fun tint(context: Context, @ColorRes id: Int): ColorStateList =
        ColorStateList.valueOf(color(context, id))

    private fun formatTimer(totalSeconds: Int): String =
        "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
