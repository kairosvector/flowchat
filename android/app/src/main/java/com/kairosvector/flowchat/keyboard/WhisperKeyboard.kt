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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.appcompat.widget.TooltipCompat
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.math.MathUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.widget.TextViewCompat
import androidx.datastore.preferences.core.edit
import com.kairosvector.flowchat.ClipClassifier
import com.kairosvector.flowchat.ClipItem
import com.kairosvector.flowchat.ClipKind
import com.kairosvector.flowchat.ClipList
import com.kairosvector.flowchat.ClipPreview
import com.kairosvector.flowchat.ClipStore
import com.kairosvector.flowchat.LastTranscriptionChip
import com.kairosvector.flowchat.PasteChip
import com.kairosvector.flowchat.ErrorDisplay
import com.kairosvector.flowchat.ErrorMessageMapper
import com.kairosvector.flowchat.ErrorStage
import com.kairosvector.flowchat.LANGUAGE_CODE
import com.kairosvector.flowchat.LanguageSettings
import com.kairosvector.flowchat.PolishNote
import com.kairosvector.flowchat.TONE_OFF
import com.kairosvector.flowchat.ToneDisplay
import com.kairosvector.flowchat.TONE_PRO
import com.kairosvector.flowchat.TonePrompts
import com.kairosvector.flowchat.ToneState
import com.kairosvector.flowchat.ToneStore
import com.kairosvector.flowchat.R
import com.kairosvector.flowchat.TranscriptionHistory
import com.kairosvector.flowchat.TranscriptionRecord
import com.kairosvector.flowchat.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

// Largest system font scale the panel follows (SPEC_r8 §4). The panel is a
// fixed 280dp, so every text stops growing at 1.15x; a bigger system scale
// is not applied beyond that.
private const val MAX_FONT_SCALE: Float = 1.15f

// Line heights of the result card text (sp), so two lines always fit the
// 46dp / 44dp boxes of SPEC_r8 §2.3 at the capped scale.
private const val RESULT_AI_LINE_SP: Float = 20f
private const val RESULT_RAW_LINE_SP: Float = 19f

// Mic key sizes (dp): idle / processing / result vs. the recording stop key.
private const val MIC_SIZE_DP: Float = 96f
private const val STOP_SIZE_DP: Float = 84f
private const val MIC_SIZE_ANIM_MS: Long = 120

// How long the "潤飾失敗：…" sentence stays in the status bar (SPEC_layout §3.4: errors 4 s).
private const val POLISH_ERROR_STATUS_MS: Long = 4000

/**
 * Fork6 redesigned keyboard panel (SPEC_r8, layout D). The whole panel is
 * driven by one [UiState] and the selected [Tab]; [render] is the only place
 * that decides what is visible, so no screen part has its own scattered
 * visibility logic.
 */
class WhisperKeyboard {
    private enum class UiState {
        Idle,        // Ready to start recording (an error is only a status text on top of this)
        Recording,   // Currently recording
        Processing,  // Waiting for transcription / polishing
        Inserted,    // Result card, inserted (everything unlocked; undo available)
    }

    private enum class Tab { Voice, Clip, Emoji }

    // The two segments of the 📋 tab.
    private enum class ClipSegment { Clipboard, History }

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
    // Plain text into the editor (history rows).
    private var onInsertText: (String) -> Unit = { }
    // The result card's text into the editor; with submit = true the field's
    // own action (send, go ...) or a newline follows (Enter = insert + send).
    // Returns false when the text before the cursor no longer matches
    // what was inserted, in which case nothing was deleted.
    private var onUndoInsert: (String) -> Boolean = { false }
    // Re-polish with the card's tone (SPEC_r8: it never changes the tone).
    private var onRepolish: (raw: String, toneId: String?, done: (polished: String?, error: ErrorDisplay?, usedFallback: Boolean) -> Unit) -> Unit =
        { _, _, done -> done(null, null, false) }
    // Swaps the text before the cursor for another one (only if it still
    // matches); false = nothing was changed.
    private var onReplaceInserted: (old: String, new: String) -> Boolean = { _, _ -> false }
    // "編輯" in the prompt viewer: open that tone's edit screen in the settings.
    private var onOpenToneEditor: (toneId: String) -> Unit = { }

    // ---- state ----
    private var state: UiState = UiState.Idle
    private var tab: Tab = Tab.Voice
    private var processingStep: ProcessingStep = ProcessingStep.Transcribing
    private var showPolishStep: Boolean = true
    private var repolishing: Boolean = false
    // The re-polish was started from the inserted card (after a failure): it returns there.
    private var stagedRaw: String? = null

    // The result card: what polishing did, the texts, and the space / newline
    // suffix of the space / enter key the recording ended with.
    private var polishNote: PolishNote = PolishNote.OFF
    private var rawResultText: String? = null
    private var polishedResultText: String? = null
    private var pendingSuffix: String = ""
    private val resultCore: String? get() = polishedResultText ?: rawResultText
    // Exactly what the card put into the editor (Inserted only); undo deletes it.
    private var insertedText: String? = null

    // History tab: the row that shows its delete button (-1 = none).
    private var deleteIndex: Int = -1

    // Clipboard (SPEC_r8 §13): the segment shown, the masked text that is uncovered for
    // five seconds, the armed state of "清除" and of the chip's "不要顯示這筆" (3 s each).
    private var clipSegment: ClipSegment = ClipSegment.Clipboard
    private var revealedText: String? = null
    private var clipClearArmed: Boolean = false
    private var chipDismissArmed: Boolean = false
    // Clips already pasted from the chip (memory only), and the one the user dismissed.
    private val pastedKeys = HashSet<String>()
    private var dismissedKey: String? = null
    private var clipWatcher: ClipboardWatcher? = null
    private val revealRunnable = Runnable {
        revealedText = null
        if (tab == Tab.Clip && state == UiState.Idle) keyboardView?.context?.let { renderClipTab(it) }
    }
    private val clipClearDisarmRunnable = Runnable {
        clipClearArmed = false
        if (tab == Tab.Clip && state == UiState.Idle) keyboardView?.context?.let { renderClipTab(it) }
    }
    private val chipDisarmRunnable = Runnable {
        chipDismissArmed = false
        renderStatus()
    }
    private val lastChipDisarmRunnable = Runnable {
        lastChipDismissArmed = false
        renderStatus()
    }
    private val chipExpiryRunnable = Runnable { renderStatus() }
    private val clipRetryRunnable = Runnable {
        clipWatcher?.refresh()
    }

    // The tone list and the current tone (cached; reloaded on every reset()).
    private var toneState: ToneState = ToneState(TonePrompts.defaultTones(), TONE_PRO)
    private val polishEnabled: Boolean get() = toneState.polishOn
    // The tone the result card was made with (re-polish and its tag use it).
    private var cardToneId: String? = null

    private enum class Overlay { None, Menu, Viewer }
    private var overlay: Overlay = Overlay.None
    private var viewerToneId: String? = null
    // Result cards fading out (so a quick re-show can cancel the hide).
    private val hidingGroups = HashSet<View>()
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
                renderStatus()
            }
            handler.postDelayed(this, WAVEFORM_TICK_MS)
        }
    }

    // Status resolver and handlers
    private val statusResolver = StatusResolver()
    // Full reasons for TalkBack / the long-press tooltip, and the 429 hint
    // under the mic; each lives exactly as long as the status it belongs to.
    private var errorDetail: String? = null
    private var transientDetail: String? = null
    private var errorRateLimited: Boolean = false
    private var isErrorShowing: Boolean = false
    private var keyboardScope: CoroutineScope? = null
        get() {
            if (field == null) field = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            return field
        }
    private val clearTransientRunnable = Runnable {
        statusResolver.clearTransient()
        transientDetail = null
        renderStatus()
    }
    private val clearErrorRunnable = Runnable { clearErrorUi() }

    // ---- views ----
    private var keyboardView: View? = null
    private var tabVoice: ImageButton? = null
    private var tabEmoji: ImageButton? = null
    private var tabClip: ImageButton? = null
    private var buttonTone: View? = null
    private var toneLabel: TextView? = null
    private var statusText: TextView? = null
    private var buttonSettings: ImageButton? = null

    private var groupMain: View? = null
    private var groupClip: View? = null
    private var groupResult: View? = null
    private var clipList: LinearLayout? = null
    private var clipEmpty: TextView? = null
    private var textClipDiag: TextView? = null
    private var segClipboard: TextView? = null
    private var segHistory: TextView? = null
    private var btnClipClear: TextView? = null
    private var pasteChip: View? = null
    private var pasteChipIcon: ImageView? = null
    private var pasteChipTag: TextView? = null
    private var pasteChipText: TextView? = null
    private var lastChip: View? = null
    private var lastChipIcon: ImageView? = null
    private var lastChipText: TextView? = null
    private var lastChipDismissArmed: Boolean = false
    private var lastChipDismissed: Boolean = false

    // Input target tracking (SPEC_r8 §22.4)
    private var currentPackageName: String? = null
    private var currentFieldId: Int = 0
    private var currentInputType: Int = 0
    private var currentImeOptions: Int = 0

    // Last transcription tracking (SPEC_r8 §22.4)
    private var lastInsertedPackage: String? = null
    private var lastInsertedFieldId: Int = 0
    private var lastTranscriptionText: String? = null
    private var lastTranscriptionTimestamp: Long = 0L
    private var keyLanguageCol: View? = null
    private var keySettingsCol: View? = null
    private var buttonLanguage: ImageButton? = null
    private var labelLanguage: TextView? = null
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

    private var rowNotice: View? = null
    private var buttonNoticeAction: TextView? = null
    private var textRaw: TextView? = null
    private var textAi: TextView? = null
    private var actionUndo: View? = null
    private var actionUndoIcon: ImageView? = null
    private var actionUndoLabel: TextView? = null
    private var actionRepolish: View? = null
    private var actionRepolishLabel: TextView? = null
    private var actionCopy: View? = null
    private var actionCollapse: View? = null
    private var resultScroll: ScrollView? = null
    private var isRepolishing: Boolean = false
    private var repolishShimmerAnimator: ValueAnimator? = null

    private var buttonLeftAction: ImageButton? = null
    private var buttonEmoji: View? = null
    private var buttonEmojiIcon: ImageView? = null
    private var buttonEmojiBackContent: View? = null
    private var labelEmojiBack: TextView? = null
    private var topRow: View? = null
    private var buttonSpaceBar: View? = null
    private var buttonPeriod: View? = null
    private var buttonPeriodText: TextView? = null
    private var buttonPeriodBackspace: BackspaceButton? = null
    private var buttonBackspace: BackspaceButton? = null
    private var buttonEnter: ImageButton? = null
    private var emojiPanel: View? = null
    private var emojiPickerView: androidx.emoji2.emojipicker.EmojiPickerView? = null

    private var scrim: View? = null
    private var toneMenu: View? = null
    private var toneMenuOffSlot: ViewGroup? = null
    private var toneMenuDivider: View? = null
    private var toneMenuScroll: ScrollView? = null
    private var toneMenuList: LinearLayout? = null
    private var toneMenuFade: View? = null
    private var promptSheet: View? = null
    private var promptChips: LinearLayout? = null
    private var promptText: TextView? = null
    private var promptScroll: ScrollView? = null
    private var promptBack: View? = null
    private var promptEdit: View? = null

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
        onReplaceInserted: (old: String, new: String) -> Boolean = { _, _ -> false },
        onRepolish: (raw: String, toneId: String?, done: (polished: String?, error: ErrorDisplay?, usedFallback: Boolean) -> Unit) -> Unit =
            { _, _, done -> done(null, null, false) },
        onOpenToneEditor: (toneId: String) -> Unit = { },
    ): View {
        val root = layoutInflater.inflate(R.layout.keyboard_view, null)
        keyboardView = root
        offerImeSwitch = shouldOfferImeSwitch

        tabVoice = root.findViewById(R.id.tab_voice)
        tabEmoji = root.findViewById(R.id.tab_emoji)
        tabClip = root.findViewById(R.id.tab_clip)
        buttonTone = root.findViewById(R.id.btn_tone)
        toneLabel = root.findViewById(R.id.tone_label)
        statusText = root.findViewById(R.id.status_text)
        buttonSettings = root.findViewById(R.id.btn_settings)

        groupMain = root.findViewById(R.id.group_main)
        groupClip = root.findViewById(R.id.group_clip)
        groupResult = root.findViewById(R.id.group_result)
        clipList = root.findViewById(R.id.clip_list)
        clipEmpty = root.findViewById(R.id.clip_empty)
        textClipDiag = root.findViewById(R.id.text_clip_diag)
        segClipboard = root.findViewById(R.id.seg_clipboard)
        segHistory = root.findViewById(R.id.seg_history)
        btnClipClear = root.findViewById(R.id.btn_clip_clear)
        pasteChip = root.findViewById(R.id.paste_chip)
        pasteChipIcon = root.findViewById(R.id.paste_chip_icon)
        pasteChipTag = root.findViewById(R.id.paste_chip_tag)
        pasteChipText = root.findViewById(R.id.paste_chip_text)
        lastChip = root.findViewById(R.id.last_chip)
        lastChipIcon = root.findViewById(R.id.last_chip_icon)
        lastChipText = root.findViewById(R.id.last_chip_text)
        keyLanguageCol = root.findViewById(R.id.key_language_col)
        keySettingsCol = root.findViewById(R.id.key_settings_col)
        buttonLanguage = root.findViewById(R.id.btn_language)
        labelLanguage = root.findViewById(R.id.label_language)
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

        rowNotice = root.findViewById(R.id.row_notice)
        buttonNoticeAction = root.findViewById(R.id.btn_notice_action)
        textRaw = root.findViewById(R.id.text_raw)
        textAi = root.findViewById(R.id.text_ai)
        actionUndo = root.findViewById(R.id.action_undo)
        actionUndoIcon = root.findViewById(R.id.action_undo_icon)
        actionUndoLabel = root.findViewById(R.id.action_undo_label)
        actionRepolish = root.findViewById(R.id.action_repolish)
        actionRepolishLabel = root.findViewById(R.id.action_repolish_label)
        actionCopy = root.findViewById(R.id.action_copy)
        actionCollapse = root.findViewById(R.id.action_collapse)
        resultScroll = root.findViewById(R.id.result_scroll)

        buttonLeftAction = root.findViewById(R.id.btn_left_action)
        buttonEmoji = root.findViewById<View>(R.id.btn_emoji)
        buttonEmojiIcon = root.findViewById(R.id.btn_emoji_icon)
        buttonEmojiBackContent = root.findViewById(R.id.btn_emoji_back_content)
        labelEmojiBack = root.findViewById(R.id.label_emoji_back)
        topRow = root.findViewById(R.id.top_row)
        buttonSpaceBar = root.findViewById(R.id.btn_space_bar)
        buttonPeriod = root.findViewById(R.id.btn_period)
        buttonPeriodText = root.findViewById(R.id.btn_period_text)
        buttonPeriodBackspace = root.findViewById(R.id.btn_period_backspace)
        buttonBackspace = root.findViewById(R.id.btn_backspace)
        buttonEnter = root.findViewById(R.id.btn_enter)
        emojiPanel = root.findViewById<View>(R.id.emoji_panel)
        emojiPickerView = root.findViewById(R.id.emoji_picker_view)
        scrim = root.findViewById(R.id.scrim)
        toneMenu = root.findViewById(R.id.tone_menu)
        toneMenuOffSlot = root.findViewById(R.id.tone_menu_off_slot)
        toneMenuDivider = root.findViewById(R.id.tone_menu_divider)
        toneMenuScroll = root.findViewById(R.id.tone_menu_scroll)
        toneMenuList = root.findViewById(R.id.tone_menu_list)
        toneMenuFade = root.findViewById(R.id.tone_menu_fade)
        promptSheet = root.findViewById(R.id.prompt_sheet)
        promptChips = root.findViewById(R.id.prompt_chips)
        promptText = root.findViewById(R.id.prompt_text)
        promptScroll = root.findViewById(R.id.prompt_scroll)
        promptBack = root.findViewById(R.id.prompt_back)
        promptEdit = root.findViewById(R.id.prompt_edit)

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
        this.onReplaceInserted = onReplaceInserted
        this.onRepolish = onRepolish
        this.onOpenToneEditor = onOpenToneEditor

        val fontScale = root.resources.configuration.fontScale
        capFontScale(root, fontScale)
        applyResultLineHeights(root, fontScale)
        applyNavigationBarInset(root)
        setupMicGesture()
        buttonLeftAction!!.setOnClickListener { onLeftActionClick() }
        buttonLeftAction!!.setOnLongClickListener { onLeftActionLongClick() }
        buttonEmoji?.setOnClickListener { onEmojiKeyClick() }
        emojiPickerView?.setOnEmojiPickedListener { emojiViewItem ->
            val emoji = emojiViewItem.emoji
            if (!emoji.isNullOrEmpty()) {
                haptic(emojiPickerView, HapticFeedbackConstants.KEYBOARD_TAP)
                onInsertText(emoji)
            }
        }
        buttonSpaceBar!!.setOnClickListener { onButtonSpaceBarClick() }
        buttonPeriod?.setOnClickListener { onButtonPeriodClick() }
        buttonPeriodBackspace?.setBackspaceCallback(
            { if (!closeOverlayIfOpen()) { collapseInserted(); this.onButtonBackspace() } },
            { if (!closeOverlayIfOpen()) { collapseInserted(); this.onButtonBackspaceWord() } }
        )
        buttonEnter!!.setOnClickListener { onButtonEnterClick() }
        buttonBackspace!!.setBackspaceCallback(
            { if (!closeOverlayIfOpen()) { collapseInserted(); this.onButtonBackspace() } },
            { if (!closeOverlayIfOpen()) { collapseInserted(); this.onButtonBackspaceWord() } }
        )
        buttonSettings!!.setOnClickListener {
            clearError()
            this.onOpenSettings()
        }
        tabVoice!!.setOnClickListener { onTabVoiceClick() }
        tabClip!!.setOnClickListener { onTabClipClick() }
        segClipboard!!.setOnClickListener { selectSegment(ClipSegment.Clipboard) }
        segHistory!!.setOnClickListener { selectSegment(ClipSegment.History) }
        btnClipClear!!.setOnClickListener { onClipClearClick() }
        pasteChip!!.setOnClickListener { onPasteChipClick() }
        pasteChip!!.setOnLongClickListener { onPasteChipLongClick() }
        lastChip?.setOnClickListener { onLastChipClick() }
        lastChip?.setOnLongClickListener { onLastChipLongClick() }
        clipWatcher = ClipboardWatcher(root.context) { onClipChanged() }
        buttonTone!!.setOnClickListener { onToneKeyClick() }
        scrim!!.setOnClickListener { closeOverlay() }
        promptBack!!.setOnClickListener { onPromptBack() }
        promptEdit!!.setOnClickListener { onPromptEdit() }
        buttonLanguage!!.setOnClickListener { onLanguageClick() }
        buttonRetry!!.setOnClickListener { onButtonRetryClick() }
        actionUndo!!.setOnClickListener { onActionUndo() }
        actionRepolish!!.setOnClickListener { onActionRepolish() }
        actionCopy!!.setOnClickListener { onActionCopy() }
        actionCollapse!!.setOnClickListener { onActionCollapse() }
        // Retry the polish only (the recording is not repeated).
        buttonNoticeAction!!.setOnClickListener { onActionRepolish() }

        // Resets keyboard upon setup
        reset()

        return root
    }

    // =====================================================================
    // Public entry points used by WhisperInputService
    // =====================================================================

    fun onStartInput(info: EditorInfo?) {
        currentPackageName = info?.packageName
        currentFieldId = info?.fieldId ?: 0
        currentInputType = info?.inputType ?: 0
        currentImeOptions = info?.imeOptions ?: 0
        lastChipDismissArmed = false
        renderStatus()
    }

    fun setLastTranscription(pkg: String?, fieldId: Int, text: String, timestampMillis: Long) {
        lastInsertedPackage = pkg
        lastInsertedFieldId = fieldId
        lastTranscriptionText = text
        lastTranscriptionTimestamp = timestampMillis
        lastChipDismissed = false
        lastChipDismissArmed = false
    }

    fun reset() {
        handler.removeCallbacks(ticker)
        repolishing = false
        stagedRaw = null
        insertedText = null
        // A result that was never inserted is dropped when the keyboard
        // shows or hides again; the history still has it.
        rawResultText = null
        polishedResultText = null
        pendingSuffix = ""
        polishNote = PolishNote.OFF
        deleteIndex = -1
        clipClearArmed = false
        clearReveal()
        cardToneId = null
        tab = Tab.Voice
        closeOverlay(animated = false, rerender = false)
        setState(UiState.Idle)
        // Re-read the toggle and the language on every reset (the service
        // calls reset() when the keyboard window is shown), so a change
        // made in the settings page is picked up without a view rebuild.
        refreshTones()
        refreshLanguage()
    }

    fun showStatus(@StringRes resId: Int, durationMs: Long = 2000L) {
        val context = keyboardView?.context ?: return
        showStatus(context.getString(resId), durationMs)
    }

    fun showStatus(message: String, durationMs: Long = 2000L, detail: String? = null) {
        transientDetail = detail
        statusResolver.setTransient(message, durationMs, SystemClock.uptimeMillis())
        renderStatus()
        handler.removeCallbacks(clearTransientRunnable)
        handler.postDelayed(clearTransientRunnable, durationMs)
    }

    fun showErrorStatus(@StringRes resId: Int, durationMs: Long = 4000L) {
        val context = keyboardView?.context ?: return
        showErrorStatus(context.getString(resId), durationMs)
    }

    fun showErrorStatus(message: String, durationMs: Long = 4000L) =
        showErrorStatus(ErrorDisplay(message), durationMs)

    fun showErrorStatus(error: ErrorDisplay, durationMs: Long = 4000L) {
        errorDetail = error.detail
        errorRateLimited = error.rateLimited
        handler.removeCallbacks(ticker)
        repolishing = false
        stagedRaw = null
        insertedText = null
        tab = Tab.Voice
        state = UiState.Idle
        isErrorShowing = true
        statusResolver.setError(error.text, durationMs, SystemClock.uptimeMillis())
        render()
        keyboardView?.let { haptic(it, rejectHaptic(), onlyFrom = Build.VERSION_CODES.R) }
        handler.removeCallbacks(clearErrorRunnable)
        handler.postDelayed(clearErrorRunnable, durationMs)
    }

    fun isProcessing(): Boolean = state == UiState.Processing || isRepolishing

    /**
     * Polished successfully and inserted into the editor directly (SPEC_r8 §21).
     * Card is shown as INSERTED with tone tag, raw row and action buttons.
     */
    fun showInsertedResult(
        raw: String,
        polished: String,
        textWithAttach: String,
        toneId: String? = null,
        usedFallbackModel: Boolean = false
    ) {
        cardToneId = toneId
        rawResultText = raw
        polishedResultText = polished
        polishNote = PolishNote.OK
        pendingSuffix = textWithAttach.takeIf { it.startsWith(polished) }?.substring(polished.length).orEmpty()
        insertedText = textWithAttach
        repolishing = false
        stagedRaw = null
        tab = Tab.Voice
        resultScroll?.scrollTo(0, 0)
        setState(UiState.Inserted)
        if (usedFallbackModel) {
            showStatus(R.string.kb_status_fallback_model, 2000L)
        } else {
            showStatus(R.string.kb_status_inserted, 2000L)
        }
    }

    /**
     * Polishing failed and the original was already typed into the editor
     * (round 12): the card is the inserted one, with the failure notice and its
     * retry, and the status bar says why. [textWithAttach] is what was typed.
     */
    fun showInsertedAfterFailure(
        raw: String,
        textWithAttach: String,
        polishError: ErrorDisplay?,
        toneId: String?
    ) {
        cardToneId = toneId
        rawResultText = raw
        polishedResultText = null
        polishNote = PolishNote.FAILED
        pendingSuffix = textWithAttach.takeIf { it.startsWith(raw) }?.substring(raw.length).orEmpty()
        insertedText = textWithAttach
        repolishing = false
        stagedRaw = null
        tab = Tab.Voice
        resultScroll?.scrollTo(0, 0)
        setState(UiState.Inserted)
        val ctx = keyboardView?.context
        if (ctx != null) {
            val shown = polishError ?: ErrorMessageMapper.other(ErrorStage.POLISH).display(ctx)
            showStatus(shown.text, POLISH_ERROR_STATUS_MS, shown.detail)
        }
    }

    /** The text went straight into the editor (AI off, or too short to polish): no card. */
    fun showDirectInserted() {
        insertedText = null
        repolishing = false
        stagedRaw = null
        tab = Tab.Voice
        setState(UiState.Idle)
        showStatus(R.string.kb_status_inserted, 2000L)
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

    /**
     * The editor's selection changed (the service forwards
     * onUpdateSelection). Since r8 the card no longer follows the cursor:
     * undo checks the text before the cursor itself, so nothing to do here.
     */
    @Suppress("UNUSED_PARAMETER")
    fun onSelectionChanged(selStart: Int, selEnd: Int) = Unit

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
        clearError()
        // Whatever was on the card (pending or inserted) is done with; a
        // result that was not inserted stays in the history.
        insertedText = null
        rawResultText = null
        polishedResultText = null
        pendingSuffix = ""
        tab = Tab.Voice
        haptic(buttonMic, confirmHaptic())
        setState(UiState.Recording)
        onStartRecording()
    }

    private fun cancelRecording() {
        haptic(buttonMic, rejectHaptic())
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

    /** INSERTED -> IDLE: the card is put away (typing in the editor, switching tabs ...). */
    private fun collapseInserted() {
        if (state != UiState.Inserted) return
        if (isRepolishing) {
            stopRepolishShimmer()
            isRepolishing = false
            onCancelTranscribing()
        }
        insertedText = null
        rawResultText = null
        polishedResultText = null
        pendingSuffix = ""
        setState(UiState.Idle)
    }

    fun clearErrorUi() {
        handler.removeCallbacks(clearErrorRunnable)
        if (!isErrorShowing && statusResolver.errorText == null && errorDetail == null && !errorRateLimited) {
            buttonRetry?.visibility = View.GONE
            return
        }
        statusResolver.clearError()
        errorDetail = null
        errorRateLimited = false
        isErrorShowing = false
        buttonRetry?.visibility = View.GONE
        labelHint?.setText(R.string.kb_hint_idle)
        render()
    }

    private fun clearError() = clearErrorUi()

    // =====================================================================
    // The one place that decides what is on screen
    // =====================================================================

    private fun render() {
        val view = keyboardView ?: return
        val context = view.context
        val s = state
        val showCard = s == UiState.Inserted
        val showEmoji = tab == Tab.Emoji && !showCard
        val showClip = tab == Tab.Clip && !showCard && !showEmoji

        // A1: When in Emoji mode, hide top_row to free 48dp so middle_area/emoji_panel expands to 204dp
        topRow?.visibility = if (showEmoji) View.GONE else View.VISIBLE

        // The tone key is locked in some states: an open menu goes with it.
        if (overlay != Overlay.None && !toneKeyEnabled(s)) closeOverlay(animated = false, rerender = false)
        if (!showEmoji) renderTopRow(context, s)

        setGroupVisible(groupMain, !(showCard || showClip || showEmoji), appearMs = 150, card = false)
        setGroupVisible(groupClip, showClip, appearMs = 120, card = false)
        setGroupVisible(emojiPanel, showEmoji, appearMs = 120, card = false)
        setGroupVisible(groupResult, showCard, appearMs = 200, card = true)

        keyLanguageCol?.visibility = if (s == UiState.Idle) View.VISIBLE else View.GONE
        keySettingsCol?.visibility = if (s == UiState.Idle) View.VISIBLE else View.GONE
        waveLeft?.visibility = if (s == UiState.Recording) View.VISIBLE else View.GONE
        waveRight?.visibility = if (s == UiState.Recording) View.VISIBLE else View.GONE
        pulseRings?.visibility = if (s == UiState.Recording) View.VISIBLE else View.GONE
        spinnerRing?.visibility = if (s == UiState.Processing) View.VISIBLE else View.GONE

        renderMic(context, s)
        renderHintRow(context, s)
        renderBottomRow(context, s)
        if (showCard) renderResultCard(context)
        if (showClip) renderClipTab(context) else clearReveal()

        view.keepScreenOn = s == UiState.Recording || s == UiState.Processing
    }

    private fun setKeyEnabled(view: View?, enabled: Boolean) {
        view ?: return
        view.isEnabled = enabled
        Motion.alphaTo(view, if (enabled) 1f else 0.38f)
    }

    // The three middle-area groups fade in; the result card also rises 8dp
    // (200 ms) and fades out in 120 ms (SPEC_r8 §7). With animations off the
    // visibility just switches.
    private fun setGroupVisible(view: View?, visible: Boolean, appearMs: Long, card: Boolean) {
        view ?: return
        if (visible) {
            val shown = view.visibility == View.VISIBLE && !hidingGroups.contains(view)
            if (shown) return
            hidingGroups.remove(view)
            if (card) {
                Motion.appear(view, appearMs, Motion.linearOutSlowIn, slideDp = 8f)
            } else {
                Motion.appear(view, appearMs, Motion.fastOutSlowIn)
            }
        } else {
            if (view.visibility != View.VISIBLE || hidingGroups.contains(view)) return
            if (card) {
                hidingGroups.add(view)
                Motion.disappear(view, 120, Motion.fastOutLinearIn) { hidingGroups.remove(view) }
            } else {
                view.animate().cancel()
                view.visibility = View.GONE
                Motion.reset(view)
            }
        }
    }

    private fun renderTopRow(context: Context, s: UiState) {
        val voice = tabVoice
        val clip = tabClip
        val tone = buttonTone
        val backspace = buttonBackspace

        // Tabs: 🎤 is always the way home; the others are only free when
        // nothing is recording, processing or waiting to be inserted.
        voice?.isSelected = tab == Tab.Voice
        clip?.isSelected = tab == Tab.Clip
        voice?.let { ViewCompat.setStateDescription(it, if (it.isSelected) context.getString(R.string.kb_state_selected) else null) }
        clip?.let { ViewCompat.setStateDescription(it, if (it.isSelected) context.getString(R.string.kb_state_selected) else null) }
        val tabsFree = s == UiState.Idle || s == UiState.Inserted
        setKeyEnabled(voice, true)
        setKeyEnabled(clip, tabsFree)

        when (s) {
            UiState.Idle, UiState.Inserted -> {
                setKeyEnabled(tone, !isRepolishing)
                setKeyEnabled(backspace, true)
            }
            UiState.Recording -> {
                // The tone is read when the request is sent, so it can still change.
                setKeyEnabled(tone, true)
                setKeyEnabled(backspace, false)
            }
            UiState.Processing -> {
                setKeyEnabled(tone, false)
                setKeyEnabled(backspace, false)
            }
        }
        // The prompt viewer covers the keyboard: tabs and backspace wait.
        if (overlay == Overlay.Viewer) {
            setKeyEnabled(voice, false)
            setKeyEnabled(clip, false)
            setKeyEnabled(backspace, false)
        }
        tone?.isSelected = overlay != Overlay.None
        renderStatus()
    }

    private fun renderStatus() {
        val statusView = statusText ?: return
        val context = statusView.context
        val now = SystemClock.uptimeMillis()

        val recordingText = "● %s %s".format(
            context.getString(R.string.kb_listening),
            formatTimer(shownSeconds.coerceAtLeast(0))
        )
        val processingText = when {
            processingStep == ProcessingStep.Polishing && !stagedRaw.isNullOrEmpty() -> stagedRaw!!
            processingStep == ProcessingStep.Polishing -> context.getString(R.string.kb_step_polishing)
            else -> context.getString(R.string.kb_step_transcribing)
        }
        val defaultText = when {
            state == UiState.Inserted && polishNote == PolishNote.FAILED ->
                context.getString(R.string.kb_status_inserted_raw)
            else -> ""
        }

        val resolved = statusResolver.resolve(
            isRecording = state == UiState.Recording,
            recordingTimerText = recordingText,
            isProcessing = state == UiState.Processing,
            processingText = processingText,
            defaultText = defaultText,
            now = now
        )

        statusView.text =
            if (overlay == Overlay.Viewer && !resolved.isError) viewerStatus(context) else resolved.text
        // The full reason for an error / polish failure: read by TalkBack and
        // shown by a long press; both go away with the status itself.
        val detail = when (resolved.type) {
            StatusType.ERROR -> errorDetail
            StatusType.TRANSIENT -> transientDetail
            else -> null
        }
        statusView.contentDescription = detail
        TooltipCompat.setTooltipText(statusView, detail)
        if (resolved.isError || resolved.isRecording) {
            statusView.setTextColor(color(context, R.color.kb_recording))
            if (resolved.isRecording) {
                statusView.fontFeatureSettings = "tnum"
                statusView.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                statusView.fontFeatureSettings = null
                statusView.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        } else {
            statusView.setTextColor(color(context, R.color.kb_on_surface_variant))
            statusView.fontFeatureSettings = null
            statusView.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
        renderPasteChip(resolved)
    }

    private fun renderMic(context: Context, s: UiState) {
        val mic = buttonMic ?: return
        when (s) {
            UiState.Idle, UiState.Inserted -> {
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
                        s == UiState.Idle && errorRateLimited -> R.string.kb_hint_rate_limited
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

            UiState.Inserted -> {
                hint.visibility = View.GONE
                steps.visibility = View.GONE
            }
        }
        buttonRetry?.visibility =
            if (s == UiState.Idle && isErrorShowing && shouldShowRetry()) View.VISIBLE else View.GONE
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

            UiState.Inserted -> {
                left.visibility = View.VISIBLE
                left.setBackgroundResource(R.drawable.kb_bg_key_primary)
                left.setImageResource(R.drawable.kb_ic_mic)
                left.imageTintList = tint(context, R.color.kb_on_primary)
                left.contentDescription = context.getString(R.string.start_speech_to_text)
            }
        }
        renderEmojiKey(context, s)
        renderPeriodKey(context, s)
    }

    private fun renderPeriodKey(context: Context, s: UiState) {
        val isEmoji = (tab == Tab.Emoji) && (s != UiState.Inserted)
        if (isEmoji) {
            buttonPeriodText?.visibility = View.GONE
            buttonPeriodBackspace?.visibility = View.VISIBLE
            buttonPeriod?.contentDescription = null
            buttonPeriod?.isClickable = false
        } else {
            buttonPeriodText?.visibility = View.VISIBLE
            buttonPeriodBackspace?.visibility = View.GONE
            buttonPeriod?.contentDescription = context.getString(R.string.kb_period_cd)
            buttonPeriod?.isClickable = true
        }
    }

    private fun renderEmojiKey(context: Context, s: UiState) {
        val emojiBtn = buttonEmoji ?: return
        val isMiddleReplaced = (tab == Tab.Emoji) || (tab == Tab.Clip) || (s == UiState.Inserted) || isRepolishing || (overlay == Overlay.Viewer)

        if (s == UiState.Recording || s == UiState.Processing) {
            emojiBtn.isEnabled = false
            emojiBtn.alpha = 0.38f
            buttonEmojiIcon?.visibility = View.VISIBLE
            buttonEmojiBackContent?.visibility = View.GONE
            emojiBtn.setBackgroundResource(R.drawable.kb_bg_key_alt)
            emojiBtn.contentDescription = context.getString(R.string.kb_emoji_key_cd)
        } else if (isMiddleReplaced) {
            emojiBtn.isEnabled = true
            emojiBtn.alpha = 1.0f
            buttonEmojiIcon?.visibility = View.GONE
            buttonEmojiBackContent?.visibility = View.VISIBLE
            emojiBtn.setBackgroundResource(R.drawable.kb_bg_key_container)
            emojiBtn.contentDescription = context.getString(R.string.kb_back_to_voice_cd)
        } else {
            emojiBtn.isEnabled = true
            emojiBtn.alpha = 1.0f
            buttonEmojiIcon?.visibility = View.VISIBLE
            buttonEmojiBackContent?.visibility = View.GONE
            emojiBtn.setBackgroundResource(R.drawable.kb_bg_key_alt)
            emojiBtn.contentDescription = context.getString(R.string.kb_emoji_key_cd)
        }
    }

    private fun onEmojiKeyClick() {
        val s = state
        val isMiddleReplaced = (tab == Tab.Emoji) || (tab == Tab.Clip) || (s == UiState.Inserted) || isRepolishing || (overlay == Overlay.Viewer)
        if (isMiddleReplaced) {
            onReturnToVoice()
        } else {
            haptic(buttonEmoji, HapticFeedbackConstants.KEYBOARD_TAP)
            openEmojiPanel()
        }
    }

    private fun onReturnToVoice() {
        haptic(buttonEmoji, HapticFeedbackConstants.KEYBOARD_TAP)
        val context = keyboardView?.context
        if (overlay == Overlay.Viewer) {
            closeOverlay(animated = false, rerender = false)
        }
        if (state == UiState.Inserted || isRepolishing) {
            collapseInserted()
        }
        if (tab == Tab.Clip) {
            clearReveal()
        }
        tab = Tab.Voice
        deleteIndex = -1
        setState(UiState.Idle)
        if (context != null) {
            buttonMic?.announceForAccessibility(context.getString(R.string.kb_back_to_voice_announce))
        }
        buttonMic?.requestFocus()
    }

    private fun openEmojiPanel() {
        if (state != UiState.Idle && state != UiState.Inserted) return
        closeOverlay(animated = false, rerender = false)
        if (state == UiState.Inserted) {
            collapseInserted()
        }
        tab = Tab.Emoji
        deleteIndex = -1
        render()
    }

    // ---- result card ----

    // A line that starts with a small rounded tag ("✦ 專業", "原文").
    private fun tagged(context: Context, label: String, body: String, @ColorRes bg: Int, @ColorRes fg: Int): CharSequence {
        val density = context.resources.displayMetrics.density
        val scale = density * minOf(context.resources.configuration.fontScale, MAX_FONT_SCALE)
        val builder = SpannableStringBuilder("​")
        builder.setSpan(
            TagSpan(
                label, color(context, bg), color(context, fg),
                textSizePx = 11f * scale, hPaddingPx = 6f * density, vPaddingPx = 2f * density,
                radiusPx = 6f * density, gapPx = 6f * density
            ),
            0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.append(body)
        return builder
    }

    private fun renderResultCard(context: Context) {
        val raw = rawResultText.orEmpty()
        val polished = polishedResultText
        val hasPolished = !polished.isNullOrEmpty()
        val failed = !hasPolished && polishNote == PolishNote.FAILED

        rowNotice?.visibility = if (failed) View.VISIBLE else View.GONE
        textAi?.visibility = if (hasPolished) View.VISIBLE else View.GONE
        if (hasPolished) {
            textAi?.text = tagged(
                context, "✦ " + cardToneName(context), polished.orEmpty(),
                R.color.kb_tertiary_container, R.color.kb_on_tertiary_container
            )
        }
        textRaw?.let {
            it.text = if (hasPolished || failed) {
                tagged(context, context.getString(R.string.kb_label_raw), raw, R.color.kb_key_alt, R.color.kb_on_surface_variant)
            } else {
                raw
            }
            it.setTextColor(color(context, if (hasPolished) R.color.kb_on_surface_variant else R.color.kb_on_surface))
        }

        updateInsertedCardButtons()
        fitResultActions()
    }

    private fun updateInsertedCardButtons() {
        val context = keyboardView?.context ?: return
        if (state != UiState.Inserted) return

        // Slot 1: 復原
        actionUndo?.isEnabled = !isRepolishing
        actionUndo?.alpha = if (isRepolishing) 0.38f else 1.0f

        // Slot 2: 重新潤飾 or 改成 {語氣}
        if (isRepolishing) {
            actionRepolish?.isEnabled = false
            actionRepolish?.alpha = 0.38f
        } else {
            val currToneId = toneState.currentId
            val hasCardTone = cardToneId != null && polishNote == PolishNote.OK
            val currToneName = toneState.current?.name.orEmpty()

            if (toneState.polishOn && currToneId != cardToneId) {
                val labelText = context.getString(R.string.kb_result_apply_tone, currToneName)
                actionRepolishLabel?.text = labelText
                actionRepolish?.contentDescription = labelText
                actionRepolish?.isEnabled = true
                actionRepolish?.alpha = 1.0f
            } else if (toneState.polishOn) {
                actionRepolishLabel?.text = context.getString(R.string.kb_result_repolish)
                actionRepolish?.contentDescription = context.getString(R.string.kb_result_repolish)
                actionRepolish?.isEnabled = true
                actionRepolish?.alpha = 1.0f
            } else {
                if (hasCardTone) {
                    actionRepolishLabel?.text = context.getString(R.string.kb_result_repolish)
                    actionRepolish?.contentDescription = context.getString(R.string.kb_result_repolish)
                    actionRepolish?.isEnabled = true
                    actionRepolish?.alpha = 1.0f
                } else {
                    actionRepolishLabel?.text = context.getString(R.string.kb_result_repolish)
                    actionRepolish?.contentDescription = context.getString(R.string.kb_result_repolish)
                    actionRepolish?.isEnabled = false
                    actionRepolish?.alpha = 0.38f
                }
            }
        }

        // Slot 3: 複製
        actionCopy?.isEnabled = !isRepolishing
        actionCopy?.alpha = if (isRepolishing) 0.38f else 1.0f

        // Slot 4: ✕ 收起
        actionCollapse?.isEnabled = !isRepolishing
        actionCollapse?.alpha = if (isRepolishing) 0.38f else 1.0f
    }

    // The actions must never overflow or be cut off, whatever the screen
    // width or font scale: if they do not fit with their labels, repolish
    // drops to icon-only; its contentDescription keeps it readable by TalkBack.
    private fun fitResultActions() {
        val row = actionRepolishLabel?.parent?.parent as? ViewGroup ?: return
        val card = groupResult ?: return
        val root = keyboardView ?: return
        val density = root.resources.displayMetrics.density
        val label = actionRepolishLabel ?: return
        label.visibility = View.VISIBLE
        actionRepolish?.minimumWidth = (102f * density).toInt()
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
            label.visibility = View.GONE
            actionRepolish?.minimumWidth = (48f * density).toInt()
        }
    }

    private fun setActionEnabled(action: View?, enabled: Boolean) {
        action ?: return
        action.isEnabled = enabled
        action.alpha = if (enabled) 1f else 0.38f
    }

    // ---- paste chip (SPEC_r8 §13.1) ----

    // The chip stands in for the (empty) status text while nothing with a higher priority is
    // shown: no error, recording, processing or short message; only on an idle keyboard.
    private fun renderPasteChip(resolved: ResolvedStatus) {
        val chip = pasteChip ?: return
        val context = chip.context
        handler.removeCallbacks(chipExpiryRunnable)
        val item = clipWatcher?.current
        val now = System.currentTimeMillis()
        val show = resolved.type == StatusType.DEFAULT && resolved.text.isEmpty() &&
            state == UiState.Idle && overlay == Overlay.None &&
            PasteChip.visible(item, now, pastedKeys, dismissedKey)
        if (!show || item == null) {
            chip.visibility = View.GONE
            chipDismissArmed = false
            renderLastChip(resolved)
            return
        }
        lastChip?.visibility = View.GONE
        lastChipDismissArmed = false
        statusText?.visibility = View.INVISIBLE
        chip.visibility = View.VISIBLE
        val tag = pasteChipTag
        val text = pasteChipText
        val icon = pasteChipIcon
        if (chipDismissArmed) {
            // Long press: the chip asks once more before it hides this clip for good.
            icon?.setImageResource(R.drawable.kb_ic_close)
            tag?.visibility = View.GONE
            text?.text = context.getString(R.string.kb_clip_dismiss)
            chip.contentDescription = context.getString(R.string.kb_clip_dismiss)
        } else if (item.kind.masked) {
            icon?.setImageResource(R.drawable.kb_ic_lock)
            tag?.visibility = View.VISIBLE
            tag?.text = context.getString(sensitiveLabel(item.kind))
            text?.text = "••••"
            // Never the content: TalkBack says it is sensitive, no tooltip.
            val label = context.getString(sensitiveLabel(item.kind))
            chip.contentDescription = context.getString(R.string.kb_clip_paste_sensitive_cd, label)
        } else {
            icon?.setImageResource(R.drawable.kb_ic_paste)
            tag?.visibility = View.GONE
            val preview = ClipPreview.of(item.text)
            text?.text = preview
            chip.contentDescription = context.getString(R.string.kb_clip_paste_cd, preview)
        }
        handler.postDelayed(chipExpiryRunnable, maxOf(PasteChip.WINDOW_MILLIS - (now - item.timestampMillis), 0L) + 100L)
    }

    @StringRes
    private fun sensitiveLabel(kind: ClipKind): Int = when (kind) {
        ClipKind.CARD -> R.string.kb_sens_card
        ClipKind.PASSWORD -> R.string.kb_sens_password
        ClipKind.ID_NUMBER -> R.string.kb_sens_id
        else -> R.string.kb_sens_generic
    }

    private fun onPasteChipClick() {
        val item = clipWatcher?.current ?: return
        val context = keyboardView?.context ?: return
        haptic(pasteChip, HapticFeedbackConstants.KEYBOARD_TAP)
        handler.removeCallbacks(chipDisarmRunnable)
        if (chipDismissArmed) {
            // Second step of "不要顯示這筆": remember it so this clip never comes back.
            val key = PasteChip.key(item.text, item.timestampMillis)
            chipDismissArmed = false
            dismissedKey = key
            keyboardScope?.launch { ClipStore.setDismissed(context, key, item.kind.persistable) }
            renderStatus()
            return
        }
        pastedKeys.add(PasteChip.key(item.text, item.timestampMillis))
        onInsertText(item.text)          // the whole text, never the shortened preview
        renderStatus()
    }

    private fun onPasteChipLongClick(): Boolean {
        if (state != UiState.Idle || clipWatcher?.current == null) return false
        haptic(pasteChip, HapticFeedbackConstants.LONG_PRESS)
        chipDismissArmed = true
        renderStatus()
        handler.removeCallbacks(chipDisarmRunnable)
        handler.postDelayed(chipDisarmRunnable, 3000L)
        return true
    }

    // ---- "上次" chip (SPEC_r8 §22.4) ----

    private fun ensureLastTranscription(context: Context) {
        if (lastTranscriptionText == null) {
            val record = TranscriptionHistory.loadLast(context)
            if (record != null) {
                lastTranscriptionText = record.polishedText ?: record.rawText
                lastTranscriptionTimestamp = record.timestampMillis
            }
        }
    }

    private fun renderLastChip(resolved: ResolvedStatus) {
        val chip = lastChip ?: return
        val context = chip.context
        ensureLastTranscription(context)

        val variation = currentInputType and InputType.TYPE_MASK_VARIATION
        val isPassword = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val isNoPersonalized = (currentImeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

        val now = System.currentTimeMillis()
        val show = LastTranscriptionChip.canShow(
            isVoiceTab = tab == Tab.Voice,
            isIdle = state == UiState.Idle,
            noOverlay = overlay == Overlay.None,
            isStatusDefaultEmpty = resolved.type == StatusType.DEFAULT && resolved.text.isEmpty(),
            pasteChipVisible = false,
            isDismissed = lastChipDismissed,
            isPasswordField = isPassword,
            isNoPersonalizedLearning = isNoPersonalized,
            currentPackage = currentPackageName,
            currentFieldId = currentFieldId,
            lastPackage = lastInsertedPackage,
            lastFieldId = lastInsertedFieldId,
            lastText = lastTranscriptionText,
            lastTimestamp = lastTranscriptionTimestamp,
            now = now
        )

        if (!show || lastTranscriptionText.isNullOrEmpty()) {
            chip.visibility = View.GONE
            lastChipDismissArmed = false
            renderIdleHintOrStatus(resolved)
            return
        }

        statusText?.visibility = View.INVISIBLE
        chip.visibility = View.VISIBLE
        val text = lastChipText
        val icon = lastChipIcon
        val fullText = lastTranscriptionText!!
        val preview = ClipPreview.of(fullText)

        if (lastChipDismissArmed) {
            icon?.setImageResource(R.drawable.kb_ic_close)
            text?.text = context.getString(R.string.kb_clip_dismiss)
            chip.contentDescription = context.getString(R.string.kb_clip_dismiss)
        } else {
            icon?.setImageResource(R.drawable.kb_ic_history)
            text?.text = preview
            chip.contentDescription = context.getString(R.string.kb_last_chip_cd, preview)
            ViewCompat.replaceAccessibilityAction(
                chip,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                context.getString(R.string.kb_last_chip_cd, preview),
                null
            )
            ViewCompat.addAccessibilityAction(
                chip,
                context.getString(R.string.kb_clip_dismiss)
            ) { _, _ ->
                lastChipDismissed = true
                renderStatus()
                true
            }
        }
    }

    private fun renderIdleHintOrStatus(resolved: ResolvedStatus) {
        val statusView = statusText ?: return
        val context = statusView.context
        statusView.visibility = View.VISIBLE
        if (resolved.type == StatusType.DEFAULT && resolved.text.isEmpty() &&
            state == UiState.Idle && tab == Tab.Voice && overlay == Overlay.None
        ) {
            statusView.alpha = 0.7f
            statusView.text = context.getString(R.string.kb_status_idle_hint)
            statusView.setTextColor(color(context, R.color.kb_on_surface_variant))
            statusView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            statusView.contentDescription = null
        } else {
            statusView.alpha = 1.0f
            statusView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
    }

    private fun onLastChipClick() {
        val fullText = lastTranscriptionText ?: return
        val context = keyboardView?.context ?: return
        haptic(lastChip, HapticFeedbackConstants.KEYBOARD_TAP)
        handler.removeCallbacks(lastChipDisarmRunnable)
        if (lastChipDismissArmed) {
            lastChipDismissArmed = false
            lastChipDismissed = true
            renderStatus()
            return
        }
        lastChipDismissed = true
        lastInsertedPackage = currentPackageName
        lastInsertedFieldId = currentFieldId
        if (state == UiState.Inserted) collapseInserted()
        onInsertText(fullText)
        showStatus(context.getString(R.string.kb_status_inserted), 1500L)
    }

    private fun onLastChipLongClick(): Boolean {
        if (state != UiState.Idle || lastTranscriptionText == null) return false
        haptic(lastChip, HapticFeedbackConstants.LONG_PRESS)
        lastChipDismissArmed = true
        renderStatus()
        handler.removeCallbacks(lastChipDisarmRunnable)
        handler.postDelayed(lastChipDisarmRunnable, 3000L)
        return true
    }

    private fun onClipChanged() {
        val cur = clipWatcher?.current
        val now = System.currentTimeMillis()
        if (PasteChip.visible(cur, now, pastedKeys, dismissedKey)) {
            clearErrorUi()
        }
        renderStatus()
        if (tab == Tab.Clip && state == UiState.Idle) renderClipTab(keyboardView?.context ?: return)
    }

    /** The keyboard appeared: start reading the clipboard (the listener is removed in [onKeyboardHidden]). */
    fun onKeyboardShown() {
        clearErrorUi()
        val context = keyboardView?.context ?: return
        keyboardScope?.launch { dismissedKey = ClipStore.dismissedKey(context) }
        clipWatcher?.start()
    }

    /** The keyboard window is shown and focused (ROUND 14 §A2). Re-reads clipboard and schedules a 150ms retry. */
    fun onWindowShown() {
        clipWatcher?.refresh()
        handler.removeCallbacks(clipRetryRunnable)
        handler.postDelayed(clipRetryRunnable, 150L)
    }

    /** The keyboard went away: stop reading, forget masked clips and any revealed text. */
    fun onKeyboardHidden() {
        handler.removeCallbacks(clipRetryRunnable)
        handler.removeCallbacks(lastChipDisarmRunnable)
        clipWatcher?.stop()
        clearReveal()
        chipDismissArmed = false
        lastChipDismissArmed = false
        keyboardScope?.cancel()
        keyboardScope = null
    }

    // ---- 📋 tab: the clipboard and the transcription history ----

    private fun clipboardItems(): List<ClipItem> =
        ClipList.build(clipWatcher?.current, clipWatcher?.history.orEmpty(), ClipStore.memory, System.currentTimeMillis())

    private fun styleSegment(view: TextView?, selected: Boolean) {
        view ?: return
        val context = view.context
        view.setBackgroundResource(if (selected) R.drawable.kb_bg_chip_selected else R.drawable.kb_bg_chip_outline)
        view.setTextColor(color(context, if (selected) R.color.kb_on_tertiary_container else R.color.kb_on_surface))
        view.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun selectSegment(segment: ClipSegment) {
        if (clipSegment == segment) return
        haptic(if (segment == ClipSegment.Clipboard) segClipboard else segHistory, HapticFeedbackConstants.KEYBOARD_TAP)
        clipSegment = segment
        clearReveal()
        deleteIndex = -1
        clipClearArmed = false
        keyboardView?.context?.let { renderClipTab(it) }
    }

    private fun clearReveal() {
        handler.removeCallbacks(revealRunnable)
        revealedText = null
    }

    private fun renderClipTab(context: Context) {
        styleSegment(segClipboard, clipSegment == ClipSegment.Clipboard)
        styleSegment(segHistory, clipSegment == ClipSegment.History)
        btnClipClear?.visibility = if (clipSegment == ClipSegment.Clipboard) View.VISIBLE else View.GONE
        btnClipClear?.text = context.getString(if (clipClearArmed) R.string.kb_clip_clear_confirm else R.string.kb_clip_clear)
        val list = clipList ?: return
        list.removeAllViews()
        if (clipSegment == ClipSegment.History) {
            textClipDiag?.visibility = View.GONE
            val records = TranscriptionHistory.loadAll(context)
            clipEmpty?.setText(R.string.kb_history_empty)
            clipEmpty?.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
            records.forEachIndexed { index, record -> list.addView(historyRow(context, index, record)) }
        } else {
            val statusText = when (clipWatcher?.lastReadStatus) {
                ClipReadStatus.SUCCESS -> context.getString(R.string.kb_clip_diag_success)
                ClipReadStatus.EMPTY -> context.getString(R.string.kb_clip_diag_empty)
                ClipReadStatus.DENIED -> context.getString(R.string.kb_clip_diag_denied)
                else -> ""
            }
            if (statusText.isNotEmpty()) {
                textClipDiag?.visibility = View.VISIBLE
                textClipDiag?.text = "（$statusText）"
            } else {
                textClipDiag?.visibility = View.GONE
            }

            val items = clipboardItems()
            if (items.isEmpty()) {
                clipEmpty?.visibility = View.VISIBLE
                clipEmpty?.text = if (statusText.isNotEmpty()) {
                    context.getString(R.string.kb_clip_empty) + "\n（$statusText）"
                } else {
                    context.getString(R.string.kb_clip_empty)
                }
            } else {
                clipEmpty?.visibility = View.GONE
            }
            items.forEach { list.addView(clipRow(context, it)) }
        }
    }

    private fun onClipClearClick() {
        val context = keyboardView?.context ?: return
        haptic(btnClipClear, HapticFeedbackConstants.KEYBOARD_TAP)
        handler.removeCallbacks(clipClearDisarmRunnable)
        if (!clipClearArmed) {
            // First press only asks; the second one within three seconds clears.
            clipClearArmed = true
            renderClipTab(context)
            handler.postDelayed(clipClearDisarmRunnable, 3000L)
            return
        }
        clipClearArmed = false
        clearReveal()
        keyboardScope?.launch {
            ClipStore.clearAll(context)
            clipWatcher?.reloadHistory()
            renderClipTab(context)
        }
    }

    private fun clipRow(context: Context, item: ClipItem): View {
        val density = context.resources.displayMetrics.density
        val masked = item.kind.masked
        val revealed = masked && item.text == revealedText
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.kb_bg_key)
            setPadding((12 * density).toInt(), (8 * density).toInt(), (masked.let { if (it) 4 else 12 } * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * density).toInt() }
            isClickable = true
            isFocusable = true
            // A masked row never says what it holds, not even to TalkBack.
            contentDescription = if (masked) {
                context.getString(R.string.kb_clip_hidden_cd, context.getString(sensitiveLabel(item.kind)))
            } else {
                item.text
            }
        }
        row.addView(ImageView(context).apply {
            setImageResource(
                when {
                    masked -> R.drawable.kb_ic_lock
                    item.kind == ClipKind.URL -> R.drawable.kb_ic_link
                    else -> R.drawable.kb_ic_paste
                }
            )
            imageTintList = tint(context, R.color.kb_on_surface_variant)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt())
        })
        if (masked && !revealed) {
            row.addView(TextView(context).apply {
                text = context.getString(sensitiveLabel(item.kind))
                setTextColor(color(context, R.color.kb_on_surface_variant))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * historyScale(context))
                setTypeface(null, android.graphics.Typeface.BOLD)
                setBackgroundResource(R.drawable.kb_bg_label)
                setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (10 * density).toInt() }
            })
        }
        row.addView(TextView(context).apply {
            text = if (masked && !revealed) "••••" else item.text
            setTextColor(color(context, R.color.kb_on_surface))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * historyScale(context))
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO.takeIf { masked } ?: View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = (10 * density).toInt() }
        })
        row.addView(TextView(context).apply {
            text = timeLabel(context, item.timestampMillis)
            setTextColor(color(context, R.color.kb_on_surface_variant))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * historyScale(context))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (8 * density).toInt() }
        })
        if (masked) {
            // 40 x 40: shows the text for five seconds, then it is covered again.
            row.addView(ImageButton(context).apply {
                setImageResource(R.drawable.kb_ic_visibility)
                imageTintList = tint(context, R.color.kb_on_surface_variant)
                background = null
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
                contentDescription = context.getString(R.string.kb_clip_reveal)
                layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
                setOnClickListener {
                    haptic(it, HapticFeedbackConstants.KEYBOARD_TAP)
                    // `handler` alone would be the button's own (null while it is detached).
                    this@WhisperKeyboard.handler.removeCallbacks(revealRunnable)
                    revealedText = item.text
                    renderClipTab(context)
                    this@WhisperKeyboard.handler.postDelayed(revealRunnable, 5000L)
                }
            })
        }
        row.setOnClickListener {
            haptic(it, HapticFeedbackConstants.KEYBOARD_TAP)
            if (state == UiState.Inserted) collapseInserted()
            if (item.isCurrent) pastedKeys.add(PasteChip.key(item.text, item.timestampMillis))
            onInsertText(item.text)
            renderClipTab(context)
        }
        return row
    }

    // ---- transcription history rows (the "紀錄" segment) ----

    private fun historyRow(context: Context, index: Int, record: TranscriptionRecord): View {
        val density = context.resources.displayMetrics.density
        val text = record.polishedText ?: record.rawText
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.kb_bg_key)
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (44 * density).toInt()
            ).apply { bottomMargin = (4 * density).toInt() }
            isClickable = true
            isFocusable = true
            contentDescription = text
        }
        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.kb_ic_history)
            imageTintList = tint(context, R.color.kb_on_surface_variant)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt())
        })
        row.addView(TextView(context).apply {
            this.text = text.replace(Regex("\\s+"), " ")
            setTextColor(color(context, R.color.kb_on_surface))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * historyScale(context))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = (10 * density).toInt() }
        })
        if (index == deleteIndex) {
            row.addView(TextView(context).apply {
                this.text = context.getString(R.string.kb_clip_delete)
                setTextColor(color(context, R.color.kb_recording))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * historyScale(context))
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                minimumWidth = (48 * density).toInt()
                minimumHeight = (44 * density).toInt()
                isClickable = true
                setOnClickListener {
                    TranscriptionHistory.delete(context, index)
                    deleteIndex = -1
                    renderClipTab(context)
                }
            })
        } else {
            row.addView(TextView(context).apply {
                this.text = timeLabel(context, record.timestampMillis)
                setTextColor(color(context, R.color.kb_on_surface_variant))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * historyScale(context))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (8 * density).toInt() }
            })
        }
        row.setOnClickListener {
            haptic(it, HapticFeedbackConstants.KEYBOARD_TAP)
            deleteIndex = -1
            if (state == UiState.Inserted) collapseInserted()
            onInsertText(text)
            renderClipTab(context)
        }
        row.setOnLongClickListener {
            haptic(it, HapticFeedbackConstants.LONG_PRESS)
            deleteIndex = if (deleteIndex == index) -1 else index
            renderClipTab(context)
            true
        }
        return row
    }

    // Text built in code has to follow the same 1.15x cap as the XML text.
    private fun historyScale(context: Context): Float {
        val fs = context.resources.configuration.fontScale
        return if (fs > MAX_FONT_SCALE) MAX_FONT_SCALE / fs else 1f
    }

    private fun timeLabel(context: Context, timestampMillis: Long): String =
        when (val t = RelativeTime.of(System.currentTimeMillis(), timestampMillis)) {
            RelativeTime.JustNow -> context.getString(R.string.kb_time_now)
            is RelativeTime.MinutesAgo -> context.getString(R.string.kb_time_min, t.minutes)
            is RelativeTime.HoursAgo -> context.getString(R.string.kb_time_hour, t.hours)
            RelativeTime.Yesterday -> context.getString(R.string.kb_time_yesterday)
            RelativeTime.Older -> SimpleDateFormat("M/d", Locale.getDefault()).format(Date(timestampMillis))
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
        haptic(buttonMic, HapticFeedbackConstants.KEYBOARD_TAP)
        beginProcessing()
        onStartTranscribing("")
    }

    // =====================================================================
    // Button handlers
    // =====================================================================

    private fun onLeftActionClick() {
        clearError()
        when (state) {
            UiState.Idle -> onSwitchIme()
            UiState.Recording -> cancelRecording()
            UiState.Processing -> cancelProcessing()
            UiState.Inserted -> startRecording()
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
        val cameFromCard = repolishing
        repolishing = false
        stagedRaw = null
        haptic(buttonLeftAction, rejectHaptic())
        if (cameFromCard && !rawResultText.isNullOrEmpty()) {
            setState(UiState.Inserted)
        } else {
            setState(UiState.Idle)
        }
    }

    private fun onButtonSpaceBarClick() {
        clearError()
        haptic(buttonSpaceBar, HapticFeedbackConstants.KEYBOARD_TAP)
        // Recording -> Start transcribing (with a whitespace included)
        // else -> types a space into the editor (a pending card stays)
        if (state == UiState.Recording) {
            beginProcessing()
            onStartTranscribing(" ")
        } else {
            collapseInserted()
            onSpaceBar()
        }
    }

    private fun onButtonPeriodClick() {
        clearError()
        haptic(buttonPeriod, HapticFeedbackConstants.KEYBOARD_TAP)
        if (state == UiState.Recording) {
            beginProcessing()
            onStartTranscribing("。")
        } else {
            collapseInserted()
            onInsertText("。")
        }
    }

    private fun onButtonEnterClick() {
        clearError()
        haptic(buttonEnter, HapticFeedbackConstants.KEYBOARD_TAP)
        when (state) {
            // Recording -> Start transcribing (with a newline included)
            UiState.Recording -> {
                beginProcessing()
                onStartTranscribing("\r\n")
            }
            UiState.Inserted -> {
                collapseInserted()
                onEnter()
            }
            else -> onEnter()
        }
    }

    private fun onButtonRetryClick() {
        // Idle -> retry the recording that is still on disk
        if (state == UiState.Idle) {
            beginProcessing()
            onStartTranscribing("")
        }
    }

    private fun onTabVoiceClick() {
        if (closeOverlayIfOpen()) return
        haptic(tabVoice, HapticFeedbackConstants.KEYBOARD_TAP)
        collapseInserted()
        if (tab != Tab.Voice) {
            tab = Tab.Voice
            deleteIndex = -1
            render()
        }
    }

    private fun onTabClipClick() {
        if (closeOverlayIfOpen()) return
        if (state != UiState.Idle && state != UiState.Inserted) return
        haptic(tabClip, HapticFeedbackConstants.KEYBOARD_TAP)
        collapseInserted()
        tab = Tab.Clip
        deleteIndex = -1
        clipClearArmed = false
        clearReveal()
        // Round 14 §A3: Always open the clipboard segment first, never fallback to history when empty.
        clipSegment = ClipSegment.Clipboard
        render()
    }

    private fun onActionUndo() {
        if (state != UiState.Inserted || isRepolishing) return
        val inserted = insertedText ?: return
        haptic(actionUndo, HapticFeedbackConstants.KEYBOARD_TAP)
        val context = keyboardView?.context ?: return
        if (onUndoInsert(inserted)) {
            insertedText = null
            collapseInserted()
            showStatus(context.getString(R.string.kb_status_undone), 2000L)
        } else {
            showStatus(context.getString(R.string.kb_toast_undo_failed), 2000L)
        }
    }

    private fun startRepolishShimmer() {
        repolishShimmerAnimator?.cancel()
        val text = textAi ?: return
        val animator = ValueAnimator.ofFloat(1f, 0.4f).apply {
            duration = 600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                text.alpha = anim.animatedValue as Float
            }
        }
        repolishShimmerAnimator = animator
        animator.start()
    }

    private fun stopRepolishShimmer() {
        repolishShimmerAnimator?.cancel()
        repolishShimmerAnimator = null
        textAi?.alpha = 1f
    }

    private fun onActionRepolish() {
        if (state != UiState.Inserted || isRepolishing) return
        val raw = rawResultText?.takeIf { it.isNotEmpty() } ?: return
        val toneToUse = if (toneState.polishOn) toneState.currentId else cardToneId
        if (toneToUse == null && !toneState.polishOn && polishNote == PolishNote.FAILED) return

        isRepolishing = true
        haptic(actionRepolish, HapticFeedbackConstants.KEYBOARD_TAP)
        startRepolishShimmer()
        updateInsertedCardButtons()
        val context = keyboardView?.context ?: return
        showStatus(context.getString(R.string.kb_status_polishing), -1)

        onRepolish(raw, toneToUse) { polished, error, usedFallback ->
            if (state != UiState.Inserted || !isRepolishing) return@onRepolish
            stopRepolishShimmer()
            isRepolishing = false
            val ctx = keyboardView?.context ?: return@onRepolish
            if (polished.isNullOrEmpty()) {
                if (polishedResultText.isNullOrEmpty()) {
                    polishNote = PolishNote.FAILED
                }
                val shown = error ?: ErrorMessageMapper.other(ErrorStage.POLISH).display(ctx)
                showStatus(shown.text, POLISH_ERROR_STATUS_MS, shown.detail)
                renderResultCard(ctx)
            } else {
                val old = insertedText
                val replacement = polished + pendingSuffix
                if (old != null && onReplaceInserted(old, replacement)) {
                    insertedText = replacement
                    cardToneId = toneToUse
                    polishedResultText = polished
                    polishNote = PolishNote.OK
                    renderResultCard(ctx)
                    if (usedFallback) {
                        showStatus(ctx.getString(R.string.kb_status_fallback_model), 2000L)
                    } else {
                        val toneName = toneState.tones.find { it.id == toneToUse }?.name ?: toneState.current?.name.orEmpty()
                        showStatus(ctx.getString(R.string.kb_status_replaced, toneName), 1500L)
                    }
                } else {
                    cardToneId = toneToUse
                    polishedResultText = polished
                    polishNote = PolishNote.OK
                    renderResultCard(ctx)
                    showStatus(ctx.getString(R.string.kb_status_replace_skipped), 2500L)
                }
            }
            updateInsertedCardButtons()
        }
    }

    private fun onActionCopy() {
        if (state != UiState.Inserted || isRepolishing) return
        val target = polishedResultText?.takeIf { it.isNotEmpty() } ?: rawResultText
        if (!target.isNullOrEmpty()) {
            haptic(actionCopy, HapticFeedbackConstants.KEYBOARD_TAP)
            copyText(target)
        }
    }

    private fun onActionCollapse() {
        if (state != UiState.Inserted || isRepolishing) return
        haptic(actionCollapse, HapticFeedbackConstants.KEYBOARD_TAP)
        collapseInserted()
    }

    // =====================================================================
    // Settings-backed bits: AI tone key and language
    // =====================================================================

    // =====================================================================
    // Tone key, tone menu and prompt viewer (SPEC_r8 §10, §11)
    // =====================================================================

    // Free while recording (the tone is read when the request is sent), idle
    // and after an insert; locked while processing and while a result waits.
    private fun toneKeyEnabled(s: UiState = state): Boolean =
        s == UiState.Idle || s == UiState.Recording || (s == UiState.Inserted && !isRepolishing)

    private fun cardToneName(context: Context): String =
        toneState.byId(cardToneId)?.name ?: toneState.current?.name
            ?: context.getString(R.string.kb_tone_pro_short)

    private fun updateToneKey() {
        val key = buttonTone ?: return
        val context = key.context
        val on = toneState.polishOn
        key.isActivated = on
        val current = toneState.current
        val shortName = if (on && current != null) current.short else context.getString(R.string.kb_tone_off_short)
        val fullName = if (on && current != null) current.name else context.getString(R.string.kb_tone_off_name)
        toneLabel?.let { label ->
            if (label.text.toString() != shortName) {
                // 100 ms cross-fade of the label (SPEC_r8 §7).
                if (Motion.enabled(context) && key.isShown) {
                    label.animate().cancel()
                    label.animate().alpha(0f).setDuration(50).setInterpolator(Motion.linear).withEndAction {
                        label.text = shortName
                        label.animate().alpha(1f).setDuration(50).setInterpolator(Motion.linear).start()
                    }.start()
                } else {
                    label.alpha = 1f
                    label.text = shortName
                }
            }
        }
        key.contentDescription = context.getString(R.string.kb_tone_cd, fullName)
        ViewCompat.setStateDescription(
            key,
            context.getString(if (on) R.string.kb_state_open else R.string.kb_state_closed)
        )
    }

    // Re-read the tone list and the current tone (on every show of the keyboard,
    // so a change made in the settings page is picked up).
    private fun refreshTones() {
        val context = keyboardView?.context ?: return
        keyboardScope?.launch {
            toneState = ToneStore.load(context)
            updateToneKey()
            if (overlay == Overlay.Menu) buildMenu(context)
            if (overlay == Overlay.Viewer) renderViewer(context)
        }
    }

    private fun onToneKeyClick() {
        val context = keyboardView?.context ?: return
        if (!toneKeyEnabled()) return
        haptic(buttonTone, HapticFeedbackConstants.KEYBOARD_TAP)
        if (overlay != Overlay.None) closeOverlay() else openMenu(context)
    }

    private fun openMenu(context: Context) {
        buildMenu(context)
        overlay = Overlay.Menu
        scrim?.let { Motion.appear(it, 150, Motion.fastOutSlowIn) }
        toneMenu?.let { Motion.appear(it, 150, Motion.fastOutSlowIn, scaleFrom = 0.92f) }
        val n = toneState.tones.size
        val scroll = toneMenuScroll
        if (n >= 4 && scroll != null) {
            val density = context.resources.displayMetrics.density
            val curIndex = toneState.tones.indexOfFirst { it.id == toneState.currentId }
            if (curIndex >= 3) {
                scroll.post {
                    scroll.scrollTo(0, ((curIndex - 1) * 44 * density).toInt())
                }
            } else {
                scroll.scrollTo(0, 0)
            }
        }
        render()
    }

    private fun buildMenu(context: Context) {
        val density = context.resources.displayMetrics.density
        val offSlot = toneMenuOffSlot ?: return
        val list = toneMenuList ?: return
        offSlot.removeAllViews()
        list.removeAllViews()

        offSlot.addView(
            menuRow(
                context, TONE_OFF, context.getString(R.string.kb_tone_off_name),
                context.getString(R.string.kb_tone_off_desc), selected = !toneState.polishOn, withPrompt = false
            )
        )
        for (tone in toneState.tones) {
            list.addView(
                menuRow(
                    context, tone.id, tone.name, ToneDisplay.desc(context, tone),
                    selected = toneState.polishOn && toneState.currentId == tone.id, withPrompt = true
                )
            )
        }

        val n = toneState.tones.size
        val scroll = toneMenuScroll
        val divider = toneMenuDivider
        val fade = toneMenuFade
        val menu = toneMenu

        if (n <= 3) {
            menu?.let {
                val lp = it.layoutParams
                lp.height = ((12 + 44 * (n + 1)) * density).toInt()
                it.layoutParams = lp
            }
            scroll?.let {
                val lp = it.layoutParams
                lp.height = ((44 * n) * density).toInt()
                it.layoutParams = lp
                it.setOnScrollChangeListener(null)
            }
            divider?.visibility = View.GONE
            fade?.visibility = View.GONE
        } else {
            menu?.let {
                val lp = it.layoutParams
                lp.height = (210 * density).toInt()
                it.layoutParams = lp
            }
            scroll?.let {
                val lp = it.layoutParams
                lp.height = (154 * density).toInt()
                it.layoutParams = lp
                it.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                    divider?.visibility = if (scrollY > 0) View.VISIBLE else View.GONE
                    fade?.visibility = if (scroll.canScrollVertically(1)) View.VISIBLE else View.GONE
                }
            }
            divider?.visibility = if ((scroll?.scrollY ?: 0) > 0) View.VISIBLE else View.GONE
            fade?.visibility = if (scroll?.canScrollVertically(1) == true) View.VISIBLE else View.GONE
        }
    }

    private fun menuRow(
        context: Context, id: String, name: String, desc: String, selected: Boolean, withPrompt: Boolean
    ): View {
        val density = context.resources.displayMetrics.density
        val scale = historyScale(context)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (44 * density).toInt()
            )
            setPadding(0, 0, (4 * density).toInt(), 0)
            setBackgroundResource(if (selected) R.drawable.kb_bg_menu_row_selected else R.drawable.kb_bg_menu_row)
            isClickable = true
            isFocusable = true
            contentDescription = name + "，" + desc
            setOnClickListener { selectTone(id) }
        }
        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.kb_ic_check)
            imageTintList = tint(context, R.color.kb_primary)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = if (selected) View.VISIBLE else View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams((20 * density).toInt(), (20 * density).toInt())
                .apply { marginStart = (12 * density).toInt() }
        })
        row.addView(TextView(context).apply {
            text = name
            setTextColor(color(context, R.color.kb_on_surface))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * scale)
            setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (8 * density).toInt() }
        })
        row.addView(TextView(context).apply {
            text = desc
            setTextColor(color(context, R.color.kb_on_surface_variant))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * scale)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (8 * density).toInt()
                // The "off" row has no prompt key and keeps no room for one.
                marginEnd = if (withPrompt) (4 * density).toInt() else (12 * density).toInt()
            }
        })
        if (withPrompt) {
            // 44 x 44 touch box at x 356–400; the icon is 20dp.
            row.addView(ImageButton(context).apply {
                setImageResource(R.drawable.kb_ic_description)
                imageTintList = tint(context, R.color.kb_on_surface_variant)
                background = null
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
                contentDescription = context.getString(R.string.kb_tone_view_prompt_cd, name)
                layoutParams = LinearLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt())
                setOnClickListener {
                    haptic(it, HapticFeedbackConstants.KEYBOARD_TAP)
                    openViewer(id)
                }
            })
        }
        return row
    }

    private fun selectTone(id: String) {
        val context = keyboardView?.context ?: return
        toneState = toneState.copy(currentId = id)
        updateToneKey()
        haptic(buttonTone, HapticFeedbackConstants.CLOCK_TICK)
        closeOverlay()
        val message = if (id == TONE_OFF) {
            context.getString(R.string.kb_status_polish_off)
        } else {
            context.getString(R.string.kb_tone_switched, toneState.current?.name.orEmpty())
        }
        showStatus(message, 1500L)
        if (state == UiState.Inserted) {
            updateInsertedCardButtons()
        }
        keyboardScope?.launch {
            try {
                ToneStore.select(context, id)
            } catch (_: Exception) {
                // Persist failure: the next reset() re-reads the saved tone.
            }
        }
    }

    // ---- prompt viewer ----

    private fun openViewer(toneId: String) {
        val context = keyboardView?.context ?: return
        viewerToneId = toneId
        overlay = Overlay.Viewer
        renderViewer(context)
        toneMenu?.let { it.animate().cancel(); it.visibility = View.GONE; Motion.reset(it) }
        promptSheet?.let { Motion.appear(it, 220, Motion.fastOutSlowIn, slideDp = 16f) }
        render()
    }

    private fun renderViewer(context: Context) {
        val chips = promptChips ?: return
        val density = context.resources.displayMetrics.density
        val scale = historyScale(context)
        chips.removeAllViews()
        for (tone in toneState.tones) {
            val selected = tone.id == viewerToneId
            chips.addView(TextView(context).apply {
                text = if (selected) "✓ " + tone.name else tone.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * scale)
                setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setTextColor(color(context, if (selected) R.color.kb_on_tertiary_container else R.color.kb_on_surface))
                setBackgroundResource(if (selected) R.drawable.kb_bg_chip_selected else R.drawable.kb_bg_chip_outline)
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, (28 * density).toInt()
                ).apply { marginEnd = (8 * density).toInt() }
                isClickable = true
                isFocusable = true
                // Looking at another tone's text never changes the tone in use.
                setOnClickListener {
                    haptic(it, HapticFeedbackConstants.KEYBOARD_TAP)
                    viewerToneId = tone.id
                    renderViewer(context)
                }
            })
        }
        promptText?.text = toneState.byId(viewerToneId)?.prompt.orEmpty()
        promptScroll?.scrollTo(0, 0)
        renderStatus()
    }

    private fun viewerStatus(context: Context): String {
        val tone = toneState.byId(viewerToneId)
        return context.getString(R.string.kb_prompt_status, tone?.name.orEmpty(), tone?.prompt?.length ?: 0)
    }

    private fun onPromptBack() {
        if (overlay != Overlay.Viewer) return
        val context = keyboardView?.context ?: return
        haptic(promptBack, HapticFeedbackConstants.KEYBOARD_TAP)
        overlay = Overlay.Menu
        promptSheet?.let { Motion.disappear(it, 150, Motion.fastOutSlowIn, slideDp = 16f) }
        buildMenu(context)
        toneMenu?.let { Motion.appear(it, 150, Motion.fastOutSlowIn, scaleFrom = 0.92f) }
        render()
    }

    private fun onPromptEdit() {
        val id = viewerToneId ?: return
        haptic(promptEdit, HapticFeedbackConstants.KEYBOARD_TAP)
        closeOverlay()
        onOpenToneEditor(id)
    }

    // ---- overlay plumbing ----

    private fun closeOverlay(animated: Boolean = true, rerender: Boolean = true) {
        if (overlay == Overlay.None) return
        val wasViewer = overlay == Overlay.Viewer
        overlay = Overlay.None
        viewerToneId = null
        val scrimView = scrim
        val menuView = toneMenu
        val sheetView = promptSheet
        if (animated) {
            scrimView?.let { Motion.disappear(it, 100, Motion.fastOutSlowIn) }
            if (wasViewer) {
                sheetView?.let { Motion.disappear(it, 150, Motion.fastOutSlowIn, slideDp = 16f) }
            } else {
                menuView?.let { Motion.disappear(it, 100, Motion.fastOutSlowIn) }
            }
        } else {
            for (v in listOfNotNull(scrimView, menuView, sheetView)) {
                v.animate().cancel()
                v.visibility = View.GONE
                Motion.reset(v)
            }
        }
        if (rerender) render()
    }

    /** True when an overlay was open and has been closed (the tap does nothing else). */
    private fun closeOverlayIfOpen(): Boolean {
        if (overlay == Overlay.None) return false
        closeOverlay()
        return true
    }

    /** The system back key: closes an open overlay. Returns whether it did. */
    fun handleBack(): Boolean = closeOverlayIfOpen()

    private fun languageLabel(context: Context, code: String): String = when (code) {
        LANGUAGE_AUTO -> context.getString(R.string.kb_lang_auto)
        LANGUAGE_ZH -> context.getString(R.string.kb_lang_zh)
        LANGUAGE_EN -> context.getString(R.string.kb_lang_en)
        else -> code
    }

    private fun refreshLanguage() {
        val context = keyboardView?.context ?: return
        keyboardScope?.launch {
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
        haptic(buttonLanguage, HapticFeedbackConstants.KEYBOARD_TAP)
        val cycle = listOfNotNull(LANGUAGE_AUTO, LANGUAGE_ZH, LANGUAGE_EN, customLanguageCode)
        val index = cycle.indexOf(languageCode)
        val next = if (index < 0) LANGUAGE_AUTO else cycle[(index + 1) % cycle.size]
        languageCode = next
        val label = languageLabel(context, next)
        labelLanguage?.text = label
        showStatus(context.getString(R.string.kb_toast_language, label), 2000L)
        keyboardScope?.launch {
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
    // the 1.15x cap. A text view keeps its XML sp size up to the cap and is
    // scaled down in px beyond it (never up).
    private fun capFontScale(view: View, fontScale: Float) {
        if (view is TextView && fontScale > MAX_FONT_SCALE) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.textSize * MAX_FONT_SCALE / fontScale)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) capFontScale(view.getChildAt(i), fontScale)
        }
    }

    // 20sp / 19sp line heights at the capped scale, so two lines of the
    // polished text (46dp) and of the original (44dp) fit exactly.
    private fun applyResultLineHeights(root: View, fontScale: Float) {
        val scale = root.resources.displayMetrics.density * minOf(fontScale, MAX_FONT_SCALE)
        textAi?.let {
            it.includeFontPadding = false
            TextViewCompat.setLineHeight(it, (RESULT_AI_LINE_SP * scale).toInt())
        }
        textRaw?.let {
            it.includeFontPadding = false
            TextViewCompat.setLineHeight(it, (RESULT_RAW_LINE_SP * scale).toInt())
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

    private fun copyText(text: String?) {
        if (text.isNullOrEmpty()) return
        val context = keyboardView?.context ?: return
        try {
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("transcription", text))
            showStatus(context.getString(R.string.last_result_copied), 2000L)
        } catch (_: Exception) {
            // Copy failure must not crash the keyboard.
        }
    }

    // ---- haptics (SPEC_r8 §8): follow the system setting, API-level fallbacks ----

    private fun confirmHaptic(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.LONG_PRESS

    private fun rejectHaptic(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT
        else HapticFeedbackConstants.LONG_PRESS

    // [onlyFrom]: the API level the constant exists on (errors do not vibrate below it).
    private fun haptic(view: View?, constant: Int, onlyFrom: Int = 0) {
        if (Build.VERSION.SDK_INT < onlyFrom) return
        view?.performHapticFeedback(constant)
    }

    private fun color(context: Context, @ColorRes id: Int): Int = ContextCompat.getColor(context, id)

    private fun tint(context: Context, @ColorRes id: Int): ColorStateList =
        ColorStateList.valueOf(color(context, id))

    private fun formatTimer(totalSeconds: Int): String =
        "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
