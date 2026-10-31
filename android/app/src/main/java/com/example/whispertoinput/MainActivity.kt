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

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Filter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.NestedScrollView
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import androidx.transition.TransitionManager
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec
import com.google.android.material.progressindicator.IndeterminateDrawable
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// An arbitrary request code, as long as it does not conflict with another one.
private const val MICROPHONE_PERMISSION_REQUEST_CODE = 200
private const val PROJECT_URL = "https://github.com/j3soon/whisper-to-input"
private const val STATE_ADVANCED_STT = "advanced_stt_expanded"
private const val STATE_ADVANCED_AI = "advanced_ai_expanded"
private const val PREFS_UI = "whisper_input_ui"
private const val PREF_MIC_ASKED = "mic_permission_asked"

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
val SPEECH_TO_TEXT_BACKEND = stringPreferencesKey("speech-to-text-backend")
val ENDPOINT = stringPreferencesKey("endpoint")
val LANGUAGE_CODE = stringPreferencesKey("language-code")
val API_KEY = stringPreferencesKey("api-key")
val MODEL = stringPreferencesKey("model")
val AUTO_RECORDING_START = booleanPreferencesKey("is-auto-recording-start")
val AUTO_SWITCH_BACK = booleanPreferencesKey("auto-switch-back")
val ADD_TRAILING_SPACE = booleanPreferencesKey("add-trailing-space")
val POSTPROCESSING = stringPreferencesKey("postprocessing")
// Fork change (fork2): LLM polish settings. LLM_API_KEY blank means
// "reuse the transcription API key"; the LLM prompt is editable here.
val POLISH_ENABLED = booleanPreferencesKey("polish-enabled")
val LLM_ENDPOINT = stringPreferencesKey("llm-endpoint")
val LLM_MODEL = stringPreferencesKey("llm-model")
val LLM_API_KEY = stringPreferencesKey("llm-api-key")
val LLM_PROMPT = stringPreferencesKey("llm-prompt")
// Round 11b: the AI tones (SPEC_r8 §15). tones-json is the ordered list,
// polish-tone the current tone id or "off". polish-enabled stays and is
// kept equal to (polish-tone != "off"). llm-prompt is only read once, by
// the migration.
val TONES_JSON = stringPreferencesKey("tones-json")
val POLISH_TONE = stringPreferencesKey("polish-tone")

/**
 * Fork6 round 6: the redesigned settings page. Every field saves itself
 * (there is no Apply button); the DataStore keys and stored values are the
 * same as before. See CHANGES.md ("第六輪").
 */
class MainActivity : AppCompatActivity() {
    private var settingsLoaded = false
    private val tester by lazy { ConnectionTester(applicationContext) }

    // ---- current (saved) values that other parts of the page depend on
    private var backend: String = SettingsOptions.BACKEND_OPENAI
    private var language: String = LanguageSettings.AUTO
    private var polishEnabled: Boolean = true
    private var micPermanentlyDenied = false

    // ---- views
    private lateinit var rootView: View
    private lateinit var appBar: AppBarLayout
    private lateinit var scrollView: NestedScrollView

    private lateinit var tilBackend: TextInputLayout
    private lateinit var acBackend: MaterialAutoCompleteTextView
    private lateinit var tilLanguage: TextInputLayout
    private lateinit var acLanguage: MaterialAutoCompleteTextView
    private lateinit var tilLanguageCustom: TextInputLayout
    private lateinit var blockSttEndpoint: View
    private lateinit var slotEndpointMain: ViewGroup
    private lateinit var bodyAdvancedStt: ViewGroup
    private lateinit var blockAdvancedStt: View
    private lateinit var btnTestStt: MaterialButton
    private lateinit var btnTestLlm: MaterialButton
    private lateinit var resultStt: View
    private lateinit var resultLlm: View
    private lateinit var aiBody: View
    private lateinit var cardStt: ViewGroup
    private lateinit var cardAi: ViewGroup
    private lateinit var toggleGroup: MaterialButtonToggleGroup
    private lateinit var toneList: RecyclerView
    private lateinit var btnToneAdd: MaterialButton
    private lateinit var textToneLimit: TextView
    private lateinit var toneAdapter: ToneListAdapter
    private lateinit var toneTouchHelper: ItemTouchHelper
    private lateinit var switchAi: MaterialSwitch

    private lateinit var fieldEndpoint: TextSetting
    private lateinit var fieldApiKey: TextSetting
    private lateinit var fieldModel: TextSetting
    private lateinit var fieldLanguageCustom: TextSetting
    private lateinit var fieldLlmEndpoint: TextSetting
    private lateinit var fieldLlmModel: TextSetting
    private lateinit var fieldLlmKey: TextSetting
    private val allFields = mutableListOf<TextSetting>()
    private val skippedFields = linkedSetOf<String>()

    private var advancedSttExpanded = false
    private var advancedAiExpanded = false
    private var keyRevealed = booleanArrayOf(false, false)
    private var navBottom = 0
    private var snackbar: Snackbar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The API key lives on this page: keep it out of screenshots and the
        // recents thumbnail. (Not for debuggable builds, so tests can take screenshots.)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        advancedSttExpanded = savedInstanceState?.getBoolean(STATE_ADVANCED_STT) ?: false
        advancedAiExpanded = savedInstanceState?.getBoolean(STATE_ADVANCED_AI) ?: false

        findViews()
        setupSystemBars()
        setupStaticTexts()
        setupAdvancedRows()
        setupAbout()
        loadSettings()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ADVANCED_STT, advancedSttExpanded)
        outState.putBoolean(STATE_ADVANCED_AI, advancedAiExpanded)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // Back from the tone editor: show the new list.
        if (settingsLoaded) reloadTones()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The IME picker closes without an onResume.
        if (hasFocus) refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        // Hide the API keys again whenever the page is left.
        setKeyRevealed(0, false)
        setKeyRevealed(1, false)
        if (!settingsLoaded) return
        lifecycleScope.launch {
            allFields.forEach { it.commit() }
            if (skippedFields.isNotEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.st_save_skipped, skippedFields.joinToString("、")),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // =====================================================================
    // Setup
    // =====================================================================

    private fun findViews() {
        rootView = findViewById(R.id.settings_root)
        appBar = findViewById(R.id.settings_app_bar)
        scrollView = findViewById(R.id.settings_scroll)
        tilBackend = findViewById(R.id.til_backend)
        acBackend = findViewById(R.id.ac_backend)
        tilLanguage = findViewById(R.id.til_language)
        acLanguage = findViewById(R.id.ac_language)
        tilLanguageCustom = findViewById(R.id.til_language_custom)
        blockSttEndpoint = findViewById(R.id.block_stt_endpoint)
        slotEndpointMain = findViewById(R.id.slot_endpoint_main)
        bodyAdvancedStt = findViewById(R.id.body_advanced_stt)
        blockAdvancedStt = findViewById(R.id.block_advanced_stt)
        btnTestStt = findViewById(R.id.btn_test_stt)
        btnTestLlm = findViewById(R.id.btn_test_llm)
        resultStt = findViewById(R.id.result_stt)
        resultLlm = findViewById(R.id.result_llm)
        aiBody = findViewById(R.id.ai_body)
        cardStt = findViewById(R.id.card_stt)
        cardAi = findViewById(R.id.card_ai)
        toggleGroup = findViewById(R.id.toggle_postprocessing)
        toneList = findViewById(R.id.tone_list)
        btnToneAdd = findViewById(R.id.btn_tone_add)
        textToneLimit = findViewById(R.id.text_tone_limit)
        switchAi = findViewById<View>(R.id.row_ai_enable).findViewById(R.id.row_switch)
    }

    // Edge-to-edge: the page draws behind both system bars, so the
    // background colour runs to the very bottom (no white strip), and the
    // bars' icons follow the theme.
    private fun setupSystemBars() {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            appBar.setPadding(0, bars.top, 0, 0)
            scrollView.setPadding(0, 0, 0, bars.bottom)
            navBottom = bars.bottom
            insets
        }
    }

    private fun setupStaticTexts() {
        bindSwitchRow(R.id.row_ai_enable, R.string.st_ai_enable, R.string.st_ai_enable_desc)
        bindSwitchRow(R.id.row_auto_start, R.string.st_auto_start, R.string.st_auto_start_desc)
        bindSwitchRow(R.id.row_switch_back, R.string.st_switch_back, R.string.st_switch_back_desc)
        bindSwitchRow(R.id.row_trailing_space, R.string.st_trailing_space, R.string.st_trailing_space_desc)
        // The AI switch uses the tertiary colours, like the AI pill on the keyboard.
        switchAi.thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(color(R.color.kb_on_tertiary), color(R.color.kb_on_surface_variant))
        )
        switchAi.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(color(R.color.kb_tertiary), color(R.color.kb_key_alt))
        )
    }

    private fun bindSwitchRow(rowId: Int, @StringRes title: Int, @StringRes desc: Int) {
        val row = findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.row_title).setText(title)
        row.findViewById<TextView>(R.id.row_desc).setText(desc)
        val sw = row.findViewById<MaterialSwitch>(R.id.row_switch)
        row.setOnClickListener { if (sw.isEnabled) sw.toggle() }
        row.contentDescription = null
    }

    private fun switchOf(rowId: Int): MaterialSwitch =
        findViewById<View>(rowId).findViewById(R.id.row_switch)

    private fun setupAdvancedRows() {
        findViewById<View>(R.id.row_advanced_stt).setOnClickListener {
            advancedSttExpanded = !advancedSttExpanded
            renderAdvanced(animate = true)
        }
        findViewById<View>(R.id.row_advanced_ai).setOnClickListener {
            advancedAiExpanded = !advancedAiExpanded
            renderAdvanced(animate = true)
        }
        renderAdvanced(animate = false)
    }

    private fun renderAdvanced(animate: Boolean) {
        fun render(card: ViewGroup, body: View, icon: ImageView, label: TextView, hintRes: Int, expanded: Boolean) {
            if (animate) TransitionManager.beginDelayedTransition(card)
            body.visibility = if (expanded) View.VISIBLE else View.GONE
            icon.setImageResource(if (expanded) R.drawable.st_ic_expand_less else R.drawable.st_ic_expand_more)
            icon.contentDescription = getString(if (expanded) R.string.st_advanced_collapse else R.string.st_advanced_expand)
            val text = SpannableStringBuilder(getString(R.string.st_advanced))
            text.setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append(' ').append(getString(hintRes))
            label.text = text
            label.setTextColor(color(R.color.kb_on_surface_variant))
        }
        render(
            cardStt, bodyAdvancedStt, findViewById(R.id.icon_advanced_stt), findViewById(R.id.label_row_advanced_stt),
            R.string.st_advanced_stt_hint, advancedSttExpanded
        )
        render(
            cardAi, findViewById(R.id.body_advanced_ai), findViewById(R.id.icon_advanced_ai),
            findViewById(R.id.label_row_advanced_ai), R.string.st_advanced_ai_hint, advancedAiExpanded
        )
    }

    private fun setupAbout() {
        // Read from the installed package (same numbers as BuildConfig, without
        // having to switch on the BuildConfig build feature).
        val info = packageManager.getPackageInfo(packageName, 0)
        findViewById<TextView>(R.id.about_version_value).text = getString(
            R.string.st_about_version_value, info.versionName.orEmpty(), PackageInfoCompat.getLongVersionCode(info)
        )
        findViewById<TextView>(R.id.about_data_value).setText(R.string.st_about_data_desc)
        findViewById<TextView>(R.id.about_source_value).setText(R.string.st_about_source_desc)
        findViewById<View>(R.id.row_about_source).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)))
            } catch (_: Exception) {
                // No browser installed: nothing to open.
            }
        }
    }

    // =====================================================================
    // Loading and saving
    // =====================================================================

    private fun loadSettings() {
        lifecycleScope.launch {
            // Move the old single prompt / switch into the tone list
            // before the page reads it (runs once).
            PolishPromptMigration.runIfNeeded(this@MainActivity)
            val prefs = dataStore.data.first()
            backend = prefs[SPEECH_TO_TEXT_BACKEND] ?: SettingsOptions.BACKEND_OPENAI
            language = prefs[LANGUAGE_CODE] ?: LanguageSettings.AUTO
            polishEnabled = prefs[POLISH_ENABLED] ?: true

            setupTextFields(prefs)
            setupBackendDropdown()
            setupLanguageDropdown()
            setupSwitches(prefs)
            setupPostprocessing(prefs)
            setupTones()
            setupTestButtons()

            applyBackendLayout()
            applyAiVisibility()
            updateHostHelper()
            updateTestButtons()
            settingsLoaded = true
        }
    }

    private suspend fun <T> save(key: Preferences.Key<T>, value: T) {
        dataStore.edit { it[key] = value }
    }

    // ---- text fields -----------------------------------------------------

    /** One text field that saves itself (focus lost / done / onPause) after validating. */
    private inner class TextSetting(
        val til: TextInputLayout,
        val edit: EditText,
        val key: Preferences.Key<String>,
        @StringRes val label: Int,
        private val card: () -> Int,
        private val isActive: () -> Boolean = { true },
        private val validate: (String) -> Int? = { null },
        private val blocking: Boolean = true,
        private val transform: (String) -> String = { it },
        private val afterSave: suspend (String) -> Unit = {},
    ) {
        var saved: String = ""
        private var liveCheck = false
        private var suppress = false

        fun bind(initial: String) {
            saved = initial
            setText(initial)
            edit.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus && settingsLoaded) {
                    liveCheck = true
                    lifecycleScope.launch { commit() }
                }
            }
            edit.setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    v.clearFocus()
                    hideKeyboard(v)
                    true
                } else false
            }
            edit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (suppress || !settingsLoaded) return
                    if (liveCheck) showError(validate(transform(s?.toString().orEmpty())))
                    onFieldEdited(card())
                    if (key == ENDPOINT) updateHostHelper()
                }
            })
        }

        fun setText(value: String) {
            suppress = true
            edit.setText(value)
            suppress = false
        }

        private fun showError(@StringRes res: Int?) {
            til.error = res?.let { getString(it) }
            til.isErrorEnabled = res != null
        }

        /** True when the text in the field passes validation (or the field is not in use). */
        fun isValid(): Boolean {
            if (!isActive() || !blocking) return true
            return validate(transform(edit.text.toString())) == null
        }

        /** Programmatic change (switching service, reset): update the field AND save it right away. */
        suspend fun setAndSave(value: String) {
            setText(value)
            showError(null)
            skippedFields.remove(getString(label))
            if (value != saved) {
                save(key, value)
                saved = value
                afterSave(value)
            }
        }

        /** Validates and, if the value is valid and changed, saves it. Returns false when it was rejected. */
        suspend fun commit(): Boolean {
            if (!isActive()) return true
            val name = getString(label)
            val value = transform(edit.text.toString())
            val error = validate(value)
            if (error != null) {
                showError(error)
                if (blocking) {
                    skippedFields.add(name)
                    return false
                }
            } else {
                showError(null)
            }
            skippedFields.remove(name)
            if (value != edit.text.toString() && !edit.hasFocus()) setText(value)
            if (value != saved) {
                save(key, value)
                saved = value
                afterSave(value)
                showSaved()
            }
            return true
        }
    }

    private fun validateUrl(value: String): Int? =
        if (isHttpUrl(value)) null else R.string.st_err_url

    private fun isHttpUrl(value: String): Boolean {
        val v = value.trim()
        if (!(v.startsWith("http://") || v.startsWith("https://"))) return false
        return v.toHttpUrlOrNull() != null
    }

    private fun setupTextFields(prefs: Preferences) {
        val openai = { backend == SettingsOptions.BACKEND_OPENAI }
        fieldEndpoint = TextSetting(
            findViewById(R.id.til_endpoint), findViewById(R.id.edit_endpoint), ENDPOINT, R.string.st_endpoint,
            card = { R.id.card_stt }, validate = ::validateUrl,
            afterSave = { updateHostHelper() }
        )
        fieldApiKey = TextSetting(
            tilOf(R.id.til_api_key), findViewById(R.id.edit_api_key), API_KEY, R.string.st_api_key,
            card = { R.id.card_stt }, isActive = openai,
            validate = { if (it.isBlank()) R.string.st_err_key_required else null },
            transform = { it.trim() }
        )
        fieldModel = TextSetting(
            tilOf(R.id.til_model), findViewById(R.id.edit_model), MODEL, R.string.st_model,
            card = { R.id.card_stt }, isActive = openai,
            validate = { if (it.isBlank()) R.string.st_err_model else null }
        )
        fieldLanguageCustom = TextSetting(
            tilLanguageCustom, findViewById(R.id.edit_language_custom), LANGUAGE_CODE, R.string.st_language,
            card = { R.id.card_stt }, isActive = { tilLanguageCustom.visibility == View.VISIBLE },
            validate = { if (isValidCustomLanguage(it)) null else R.string.st_err_language },
            transform = { normalizeCustomLanguage(it) },
            afterSave = { value ->
                language = value
                LanguageSettings.syncCustomFromSetting(this@MainActivity, value)
            }
        )
        fieldLlmEndpoint = TextSetting(
            tilOf(R.id.til_llm_endpoint), findViewById(R.id.edit_llm_endpoint), LLM_ENDPOINT, R.string.st_endpoint,
            card = { R.id.card_ai }, isActive = { polishEnabled }, validate = ::validateUrl
        )
        fieldLlmModel = TextSetting(
            tilOf(R.id.til_llm_model), findViewById(R.id.edit_llm_model), LLM_MODEL, R.string.st_model,
            card = { R.id.card_ai }, isActive = { polishEnabled },
            validate = { if (it.isBlank()) R.string.st_err_model else null }
        )
        fieldLlmKey = TextSetting(
            tilOf(R.id.til_llm_key), findViewById(R.id.edit_llm_key), LLM_API_KEY, R.string.st_ai_key,
            card = { R.id.card_ai }, isActive = { polishEnabled }, transform = { it.trim() }
        )

        // Initial values: saved ones, or the same defaults the old page showed.
        val endpointDefault = SettingsOptions.defaultEndpoint(backend)
        fieldEndpoint.bind(prefs[ENDPOINT] ?: endpointDefault)
        fieldApiKey.bind(prefs[API_KEY].orEmpty())
        fieldModel.bind(prefs[MODEL] ?: SettingsOptions.defaultModel(backend))
        fieldLanguageCustom.bind(if (LanguageSettings.isBuiltIn(language) || language == SettingsOptions.LANGUAGE_MULTI) "" else language)
        fieldLlmEndpoint.bind(prefs[LLM_ENDPOINT] ?: DEFAULT_LLM_ENDPOINT)
        fieldLlmModel.bind(prefs[LLM_MODEL] ?: DEFAULT_LLM_MODEL)
        fieldLlmKey.bind(prefs[LLM_API_KEY].orEmpty())
        // "saved" for the language field is the language itself.
        fieldLanguageCustom.saved = language
        allFields += listOf(
            fieldEndpoint, fieldApiKey, fieldModel, fieldLanguageCustom,
            fieldLlmEndpoint, fieldLlmModel, fieldLlmKey
        )

        // API keys: only the last 4 characters are visible until the eye is pressed.
        setupKeyField(0, fieldApiKey.til, fieldApiKey.edit)
        setupKeyField(1, fieldLlmKey.til, fieldLlmKey.edit)

        findViewById<View>(R.id.btn_endpoint_restore).setOnClickListener {
            lifecycleScope.launch {
                fieldEndpoint.setAndSave(SettingsOptions.defaultEndpoint(backend))
                updateHostHelper()
                onFieldEdited(R.id.card_stt)
                showSaved()
            }
        }
        findViewById<View>(R.id.btn_llm_endpoint_restore).setOnClickListener {
            lifecycleScope.launch {
                fieldLlmEndpoint.setAndSave(DEFAULT_LLM_ENDPOINT)
                onFieldEdited(R.id.card_ai)
                showSaved()
            }
        }
    }

    private fun tilOf(id: Int): TextInputLayout = findViewById(id)

    private fun isValidCustomLanguage(value: String): Boolean {
        val raw = value.trim()
        return LanguageSettings.normalize(raw) != LanguageSettings.AUTO || raw.lowercase() == LanguageSettings.AUTO
    }

    // Valid input is saved as its normalized code ("zh-TW" -> "zh"); invalid input is kept as typed.
    private fun normalizeCustomLanguage(value: String): String =
        if (isValidCustomLanguage(value)) LanguageSettings.normalize(value) else value.trim()

    // ---- API key masking ---------------------------------------------------

    private fun setupKeyField(index: Int, til: TextInputLayout, edit: EditText) {
        edit.transformationMethod = LastFourTransformation()
        til.endIconMode = TextInputLayout.END_ICON_CUSTOM
        til.setEndIconDrawable(R.drawable.st_ic_visibility)
        til.endIconContentDescription = getString(R.string.st_api_key_show)
        til.setEndIconOnClickListener { setKeyRevealed(index, !keyRevealed[index]) }
    }

    private fun setKeyRevealed(index: Int, revealed: Boolean) {
        if (!::fieldApiKey.isInitialized) return
        keyRevealed[index] = revealed
        val (til, edit) = if (index == 0) fieldApiKey.til to fieldApiKey.edit else fieldLlmKey.til to fieldLlmKey.edit
        val start = edit.selectionStart
        val end = edit.selectionEnd
        edit.transformationMethod = if (revealed) null else LastFourTransformation()
        if (start >= 0 && end >= 0) edit.setSelection(start, end)
        til.setEndIconDrawable(if (revealed) R.drawable.st_ic_visibility_off else R.drawable.st_ic_visibility)
        til.endIconContentDescription = getString(if (revealed) R.string.st_api_key_hide else R.string.st_api_key_show)
    }

    // ---- backend dropdown ---------------------------------------------------

    /** An adapter that always lists every item (these are pickers, not autocomplete). */
    private open class FixedAdapter<T>(
        context: Context, resource: Int, private val all: List<T>
    ) : ArrayAdapter<T>(context, resource, all) {
        override fun getFilter(): Filter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?) =
                FilterResults().also { it.values = all; it.count = all.size }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                notifyDataSetChanged()
            }
        }
    }

    private fun setupBackendDropdown() {
        val options = SettingsOptions.backends
        val adapter = object : FixedAdapter<StoredOption>(this, R.layout.item_dropdown_two_line, options) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(R.layout.item_dropdown_two_line, parent, false)
                val option = getItem(position)!!
                row.findViewById<TextView>(R.id.option_title).setText(option.label)
                row.findViewById<TextView>(R.id.option_sub).text = option.sub?.let { getString(it) }.orEmpty()
                return row
            }
        }
        acBackend.setAdapter(adapter)
        showBackendSelection()
        acBackend.setOnItemClickListener { _, _, position, _ ->
            val selected = SettingsOptions.storedAt(options, position) ?: return@setOnItemClickListener
            if (selected != backend) lifecycleScope.launch { switchBackend(selected) }
        }
    }

    private fun showBackendSelection() {
        val index = SettingsOptions.indexOfStored(SettingsOptions.backends, backend).coerceAtLeast(0)
        acBackend.setText(getString(SettingsOptions.backends[index].label), false)
    }

    private suspend fun switchBackend(newBackend: String) {
        val previous = BackendSnapshot(
            backend, fieldEndpoint.saved, fieldModel.saved, language
        )
        val newEndpoint = SettingsOptions.endpointAfterSwitch(newBackend, fieldEndpoint.saved)
        val newModel = SettingsOptions.modelAfterSwitch(newBackend, fieldModel.saved)
        val newLanguage = SettingsOptions.languageAfterSwitch(newBackend, language)
        applyBackend(newBackend, newEndpoint, newModel, newLanguage)

        val changedEndpoint = newEndpoint != previous.endpoint
        if (changedEndpoint || newModel != previous.model) {
            val name = getString(SettingsOptions.backends[SettingsOptions.indexOfStored(SettingsOptions.backends, newBackend)].label)
            showSnackbar(getString(R.string.st_backend_switched, name), getString(R.string.st_undo)) {
                lifecycleScope.launch { applyBackend(previous.backend, previous.endpoint, previous.model, previous.language) }
            }
        }
    }

    private data class BackendSnapshot(val backend: String, val endpoint: String, val model: String, val language: String)

    /** Changes the service and its dependent fields, saves all of them, and refreshes the page. */
    private suspend fun applyBackend(newBackend: String, endpoint: String, model: String, newLanguage: String) {
        backend = newBackend
        save(SPEECH_TO_TEXT_BACKEND, newBackend)
        fieldEndpoint.setAndSave(endpoint)
        fieldModel.setAndSave(model)
        if (newLanguage != language) {
            language = newLanguage
            save(LANGUAGE_CODE, newLanguage)
            LanguageSettings.syncCustomFromSetting(this, newLanguage)
        }
        fieldLanguageCustom.saved = language
        showBackendSelection()
        setupLanguageDropdown()
        applyBackendLayout()
        updateHostHelper()
        onFieldEdited(R.id.card_stt)
    }

    // What is shown depends on the service (hidden fields keep their values).
    private fun applyBackendLayout() {
        val openai = backend == SettingsOptions.BACKEND_OPENAI
        findViewById<View>(R.id.til_api_key).visibility = if (openai) View.VISIBLE else View.GONE
        findViewById<View>(R.id.til_model).visibility = if (openai) View.VISIBLE else View.GONE
        blockAdvancedStt.visibility = if (openai) View.VISIBLE else View.GONE
        // The service URL is a main field for the self-hosted services and an advanced one for OpenAI-compatible ones.
        val target: ViewGroup = if (openai) bodyAdvancedStt else slotEndpointMain
        if (blockSttEndpoint.parent !== target) {
            (blockSttEndpoint.parent as? ViewGroup)?.removeView(blockSttEndpoint)
            target.addView(blockSttEndpoint)
        }
        updateTestButtons()
    }

    private fun updateHostHelper() {
        val host = ConnectionTestMapper.hostOf(fieldEndpoint.edit.text.toString().trim())
        tilBackend.helperText = if (host.isEmpty()) getString(R.string.st_backend_host_none)
        else getString(R.string.st_backend_host, host)
    }

    // ---- language dropdown ----------------------------------------------------

    private class LanguageOption(val code: String?, val label: String) {
        override fun toString(): String = label
    }

    private fun languageOptions(): List<LanguageOption> {
        val list = mutableListOf(
            LanguageOption(LanguageSettings.AUTO, getString(R.string.st_language_auto)),
            LanguageOption(LanguageSettings.ZH, getString(R.string.st_language_zh)),
            LanguageOption(LanguageSettings.EN, getString(R.string.st_language_en)),
        )
        if (backend == SettingsOptions.BACKEND_NIM) {
            list += LanguageOption(SettingsOptions.LANGUAGE_MULTI, getString(R.string.st_language_multi))
        }
        list += LanguageOption(null, getString(R.string.st_language_other))
        return list
    }

    private fun setupLanguageDropdown() {
        val options = languageOptions()
        acLanguage.setAdapter(FixedAdapter(this, android.R.layout.simple_dropdown_item_1line, options))
        // Show what is stored: a built-in code, "multi" for NIM, anything else = "other code".
        val selected = options.firstOrNull { it.code == language } ?: options.last()
        acLanguage.setText(selected.label, false)
        val custom = selected.code == null
        tilLanguageCustom.visibility = if (custom) View.VISIBLE else View.GONE
        if (custom) fieldLanguageCustom.setText(language.takeIf { it != LanguageSettings.AUTO } ?: "")
        acLanguage.setOnItemClickListener { _, _, position, _ ->
            val option = options[position]
            lifecycleScope.launch {
                if (option.code == null) {
                    tilLanguageCustom.visibility = View.VISIBLE
                    fieldLanguageCustom.edit.requestFocus()
                } else {
                    tilLanguageCustom.visibility = View.GONE
                    fieldLanguageCustom.setText("")
                    tilLanguageCustom.error = null
                    skippedFields.remove(getString(R.string.st_language))
                    if (option.code != language) {
                        language = option.code
                        save(LANGUAGE_CODE, option.code)
                        LanguageSettings.syncCustomFromSetting(this@MainActivity, option.code)
                        fieldLanguageCustom.saved = option.code
                    }
                }
                onFieldEdited(R.id.card_stt)
            }
        }
    }

    // ---- switches / toggle group -------------------------------------------------

    private fun setupSwitches(prefs: Preferences) {
        fun bind(rowId: Int, key: Preferences.Key<Boolean>, default: Boolean, after: (Boolean) -> Unit = {}) {
            val sw = switchOf(rowId)
            // The value always comes from the settings, never from a saved view state
            // (a stale restored state must not look like a tap and rewrite the tone).
            sw.isSaveEnabled = false
            sw.isChecked = prefs[key] ?: default
            sw.setOnCheckedChangeListener { _, checked ->
                if (!settingsLoaded || syncingSwitch) return@setOnCheckedChangeListener
                lifecycleScope.launch { save(key, checked) }
                after(checked)
            }
        }
        bind(R.id.row_auto_start, AUTO_RECORDING_START, true)
        bind(R.id.row_switch_back, AUTO_SWITCH_BACK, false)
        bind(R.id.row_trailing_space, ADD_TRAILING_SPACE, false)
        bind(R.id.row_ai_enable, POLISH_ENABLED, true) { checked ->
            polishEnabled = checked
            syncToneWithSwitch(checked)
            TransitionManager.beginDelayedTransition(cardAi)
            applyAiVisibility()
            updateTestButtons()
        }
    }

    private fun applyAiVisibility() {
        aiBody.visibility = if (polishEnabled) View.VISIBLE else View.GONE
    }

    private fun setupPostprocessing(prefs: Preferences) {
        val buttons = listOf(R.id.btn_pp_traditional, R.id.btn_pp_simplified, R.id.btn_pp_none)
        val stored = prefs[POSTPROCESSING] ?: SettingsOptions.POSTPROCESS_TRADITIONAL
        val index = SettingsOptions.indexOfStored(SettingsOptions.postprocessing, stored).coerceAtLeast(0)
        toggleGroup.check(buttons[index])
        fun styleChecked() {
            // the selected segment is bold (its keyAlt fill comes from the theme)
            for (id in buttons) {
                val b = findViewById<MaterialButton>(id)
                b.setTypeface(null, if (b.isChecked) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        }
        styleChecked()
        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            styleChecked()
            if (!isChecked || !settingsLoaded) return@addOnButtonCheckedListener
            val value = SettingsOptions.storedAt(SettingsOptions.postprocessing, buttons.indexOf(checkedId)) ?: return@addOnButtonCheckedListener
            lifecycleScope.launch { save(POSTPROCESSING, value) }
        }
    }

    // ---- tones -------------------------------------------------------------------

    private fun setupTones() {
        toneAdapter = ToneListAdapter(
            onClick = { tone ->
                startActivity(
                    Intent(this, ToneEditActivity::class.java).putExtra(ToneEditActivity.EXTRA_TONE_ID, tone.id)
                )
            },
            onOrderChanged = { list ->
                if (!settingsLoaded) return@ToneListAdapter
                lifecycleScope.launch { ToneStore.saveTones(this@MainActivity, list) }
            },
            startDrag = { holder -> toneTouchHelper.startDrag(holder) }
        )
        toneList.layoutManager = LinearLayoutManager(this)
        toneList.adapter = toneAdapter
        toneTouchHelper = ItemTouchHelper(toneAdapter.touchCallback()).also { it.attachToRecyclerView(toneList) }
        btnToneAdd.setOnClickListener {
            startActivity(Intent(this, ToneEditActivity::class.java).putExtra(ToneEditActivity.EXTRA_NEW, true))
        }
        reloadTones()
    }

    // True while the page itself moves the AI switch (it is not a tap then).
    private var syncingSwitch = false

    private fun reloadTones() {
        lifecycleScope.launch {
            val state = ToneStore.load(this@MainActivity)
            // The tone may have been changed on the keyboard while this page was away.
            val aiSwitch = switchOf(R.id.row_ai_enable)
            if (settingsLoaded && aiSwitch.isChecked != state.polishOn) {
                syncingSwitch = true
                aiSwitch.isChecked = state.polishOn
                syncingSwitch = false
                polishEnabled = state.polishOn
                applyAiVisibility()
            }
            toneAdapter.submit(state.tones, if (state.polishOn) state.currentId else null)
            val canAdd = ToneRules.canAdd(state.tones.size)
            btnToneAdd.isEnabled = canAdd
            textToneLimit.visibility = if (canAdd) View.GONE else View.VISIBLE
        }
    }

    // The switch above the list: on = the first tone (or the one in use), off = no polishing.
    private fun syncToneWithSwitch(checked: Boolean) {
        lifecycleScope.launch {
            val state = ToneStore.load(this@MainActivity)
            val target = when {
                !checked -> TONE_OFF
                state.polishOn -> state.currentId
                else -> state.tones.first().id
            }
            ToneStore.select(this@MainActivity, target)
            reloadTones()
        }
    }

    // =====================================================================
    // Feedback: snackbars
    // =====================================================================

    private fun showSaved() = showSnackbar(getString(R.string.st_save_done), null, null)

    private fun showSnackbar(message: String, actionLabel: String?, action: (() -> Unit)?) {
        snackbar?.dismiss()
        val bar = Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT)
        if (actionLabel != null && action != null) {
            bar.duration = Snackbar.LENGTH_LONG
            bar.setAction(actionLabel) { action() }
        }
        // 16dp above the bottom edge plus the navigation bar.
        (bar.view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.bottomMargin = (16 * resources.displayMetrics.density).toInt() + navBottom
        }
        snackbar = bar
        bar.show()
    }

    /** Something in [cardId] changed: an old connection test result no longer applies. */
    private fun onFieldEdited(cardId: Int) {
        if (cardId == R.id.card_stt) resultStt.visibility = View.GONE
        if (cardId == R.id.card_ai) resultLlm.visibility = View.GONE
        updateTestButtons()
    }

    // =====================================================================
    // Connection test
    // =====================================================================

    private fun setupTestButtons() {
        btnTestStt.setOnClickListener { runTest(stt = true) }
        btnTestLlm.setOnClickListener { runTest(stt = false) }
    }

    private fun updateTestButtons() {
        if (!::fieldEndpoint.isInitialized || !::fieldLlmEndpoint.isInitialized) return
        val sttFields = listOf(fieldEndpoint, fieldApiKey, fieldModel, fieldLanguageCustom)
        val llmFields = listOf(fieldLlmEndpoint, fieldLlmModel, fieldLlmKey)
        if (btnTestStt.tag != "busy") btnTestStt.isEnabled = sttFields.all { it.isValid() }
        if (btnTestLlm.tag != "busy") btnTestLlm.isEnabled = llmFields.all { it.isValid() }
    }

    private fun setTesting(button: MaterialButton, testing: Boolean) {
        button.tag = if (testing) "busy" else ""
        button.isEnabled = !testing
        if (testing) {
            val spec = CircularProgressIndicatorSpec(this, null, 0, com.google.android.material.R.style.Widget_Material3_CircularProgressIndicator_ExtraSmall)
            spec.indicatorColors = intArrayOf(color(R.color.kb_on_primary_container))
            button.icon = IndeterminateDrawable.createCircularDrawable(this, spec)
            button.setText(R.string.st_testing)
        } else {
            button.setIconResource(R.drawable.st_ic_bolt)
            button.setText(R.string.st_test)
        }
    }

    private fun runTest(stt: Boolean) {
        val button = if (stt) btnTestStt else btnTestLlm
        if (button.tag == "busy") return
        val resultView = if (stt) resultStt else resultLlm
        lifecycleScope.launch {
            setTesting(button, true)
            resultView.visibility = View.GONE
            try {
                val fields = if (stt) listOf(fieldEndpoint, fieldApiKey, fieldModel, fieldLanguageCustom)
                else listOf(fieldLlmEndpoint, fieldLlmModel, fieldLlmKey)
                var ok = true
                for (field in fields) if (!field.commit()) ok = false
                if (!ok) return@launch
                val sttKey = fieldApiKey.saved
                val result = withContext(Dispatchers.IO) {
                    if (stt) {
                        tester.testStt(backend, fieldEndpoint.saved, sttKey, fieldModel.saved, language)
                    } else {
                        tester.testLlm(fieldLlmEndpoint.saved, fieldLlmKey.saved.ifBlank { sttKey }, fieldLlmModel.saved)
                    }
                }
                val model = if (stt) fieldModel.saved else fieldLlmModel.saved
                showTestResult(resultView, result, model)
            } finally {
                setTesting(button, false)
                updateTestButtons()
            }
        }
    }

    private fun showTestResult(view: View, result: TestResult, model: String) {
        val title = view.findViewById<TextView>(R.id.result_title)
        val detail = view.findViewById<TextView>(R.id.result_detail)
        val icon = view.findViewById<ImageView>(R.id.result_icon)
        when (result) {
            is TestResult.Ok -> {
                view.setBackgroundResource(R.drawable.st_bg_result_ok)
                icon.setImageResource(R.drawable.st_ic_check_circle)
                icon.imageTintList = ColorStateList.valueOf(color(R.color.kb_primary))
                title.setText(R.string.st_test_ok_title)
                val seconds = String.format(Locale.US, "%.1f", result.elapsedMs / 1000f)
                detail.text = if (result.model != null) getString(R.string.st_test_ok_detail_model, seconds, result.model)
                else getString(R.string.st_test_ok_detail_plain, seconds)
            }

            is TestResult.Failed -> {
                view.setBackgroundResource(R.drawable.st_bg_result_err)
                icon.setImageResource(R.drawable.st_ic_error)
                icon.imageTintList = ColorStateList.valueOf(color(R.color.kb_recording))
                when (result.kind) {
                    FailureKind.INVALID_KEY -> {
                        title.text = getString(R.string.st_test_key_title, result.code ?: 401)
                        detail.setText(R.string.st_test_key_detail)
                    }
                    FailureKind.MODEL_NOT_FOUND -> {
                        title.text = getString(R.string.st_test_model_title, model)
                        detail.setText(R.string.st_test_model_detail)
                    }
                    FailureKind.UNREACHABLE -> {
                        title.setText(R.string.st_test_net_title)
                        detail.text = result.detail
                    }
                    FailureKind.RATE_LIMITED -> {
                        title.setText(R.string.st_test_rate_title)
                        detail.setText(R.string.st_test_rate_detail)
                    }
                    FailureKind.OTHER -> {
                        title.text = getString(R.string.st_test_other_title, result.code ?: 0)
                        detail.text = result.detail
                    }
                }
            }
        }
        detail.visibility = if (detail.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        view.visibility = View.VISIBLE
        view.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    // =====================================================================
    // Keyboard status card
    // =====================================================================

    private class StatusItem(
        @StringRes val title: Int, val desc: String, @StringRes val action: Int, val onClick: () -> Unit
    )

    private fun ownId(): String = ComponentName(this, WhisperInputService::class.java).flattenToShortString()

    private fun refreshStatus() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabledList = imm.enabledInputMethodList
        val enabled = enabledList.any { it.id == ownId() }
        val currentId = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val current = enabled && currentId == ownId()
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        val pending = mutableListOf<StatusItem>()
        if (!enabled) {
            pending += StatusItem(
                R.string.st_status_enable_title, getString(R.string.st_status_enable_desc), R.string.st_status_enable_action
            ) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        } else if (!current) {
            val label = enabledList.firstOrNull { it.id == currentId }?.loadLabel(packageManager)?.toString()
            pending += StatusItem(
                R.string.st_status_current_title,
                if (label.isNullOrBlank()) getString(R.string.st_status_current_desc_unknown)
                else getString(R.string.st_status_current_desc, label),
                R.string.st_status_current_action
            ) { imm.showInputMethodPicker() }
        }
        if (!micGranted) {
            pending += StatusItem(
                R.string.st_status_mic_title, getString(R.string.st_status_mic_desc), R.string.st_status_mic_action
            ) { requestMicrophone() }
        }

        val okParts = mutableListOf<String>()
        if (enabled) okParts += getString(R.string.st_status_ok_enabled)
        if (current) okParts += getString(R.string.st_status_ok_current)
        if (micGranted) okParts += getString(R.string.st_status_ok_mic)

        val title = findViewById<TextView>(R.id.status_title)
        val badge = findViewById<TextView>(R.id.status_badge)
        val icon = findViewById<ImageView>(R.id.status_icon)
        val list = findViewById<ViewGroup>(R.id.status_list)
        val okDesc = findViewById<View>(R.id.status_ok_desc)
        val divider = findViewById<View>(R.id.status_divider)
        val summaryRow = findViewById<View>(R.id.status_summary_row)
        list.removeAllViews()

        if (pending.isEmpty()) {
            title.setText(R.string.st_status_all_ok)
            icon.setImageResource(R.drawable.st_ic_check_circle)
            icon.imageTintList = ColorStateList.valueOf(color(R.color.kb_primary))
            badge.setText(R.string.st_status_badge_ok)
            badge.setBackgroundResource(R.drawable.st_bg_badge_ok)
            badge.setTextColor(color(R.color.kb_on_primary_container))
            okDesc.visibility = View.VISIBLE
            divider.visibility = View.GONE
            summaryRow.visibility = View.GONE
        } else {
            title.setText(R.string.st_section_status)
            icon.setImageResource(R.drawable.kb_ic_keyboard)
            icon.imageTintList = ColorStateList.valueOf(color(R.color.kb_on_surface_variant))
            badge.text = getString(R.string.st_status_badge_todo, pending.size)
            badge.setBackgroundResource(R.drawable.st_bg_badge_err)
            badge.setTextColor(color(R.color.kb_on_surface))
            okDesc.visibility = View.GONE
            for (item in pending) {
                val row = layoutInflater.inflate(R.layout.item_status_row, list, false)
                row.findViewById<TextView>(R.id.item_title).setText(item.title)
                row.findViewById<TextView>(R.id.item_desc).text = item.desc
                row.findViewById<MaterialButton>(R.id.item_action).apply {
                    setText(item.action)
                    setOnClickListener { item.onClick() }
                }
                list.addView(row)
            }
            val showSummary = okParts.isNotEmpty()
            divider.visibility = if (showSummary) View.VISIBLE else View.GONE
            summaryRow.visibility = if (showSummary) View.VISIBLE else View.GONE
            findViewById<TextView>(R.id.status_summary).text =
                okParts.joinToString(getString(R.string.st_status_ok_join))
        }
    }

    // The mic request: ask first; once Android stops showing the dialog,
    // send the user to the app's system settings instead.
    private fun requestMicrophone() {
        val prefs = getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
        val asked = prefs.getBoolean(PREF_MIC_ASKED, false)
        val canAsk = !asked || ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
        if (canAsk && !micPermanentlyDenied) {
            prefs.edit().putBoolean(PREF_MIC_ASKED, true).apply()
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_PERMISSION_REQUEST_CODE)
        } else {
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addCategory(Intent.CATEGORY_DEFAULT)
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MICROPHONE_PERMISSION_REQUEST_CODE) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            micPermanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
            Toast.makeText(this, R.string.st_mic_denied, Toast.LENGTH_SHORT).show()
        }
        refreshStatus()
    }

    // =====================================================================
    // Small helpers
    // =====================================================================

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }
}
