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

package com.kairosvector.flowchat

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

/**
 * Edit one tone (SPEC_r8 §14), opened from the settings list, from "＋ 新增語氣"
 * ([EXTRA_NEW]) and from the keyboard's prompt viewer ([EXTRA_TONE_ID]).
 * Nothing is written until 儲存; leaving with changes asks first.
 */
class ToneEditActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TONE_ID = "tone_id"
        const val EXTRA_NEW = "new_tone"
    }

    private lateinit var tilName: TextInputLayout
    private lateinit var editName: TextInputEditText
    private lateinit var tilShort: TextInputLayout
    private lateinit var editShort: TextInputEditText
    private lateinit var tilPrompt: TextInputLayout
    private lateinit var editPrompt: TextInputEditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnReset: MaterialButton
    private lateinit var btnDelete: MaterialButton

    private var tones: List<Tone> = emptyList()
    private var tone: Tone? = null          // null while the tones are loading
    private var isNew = false
    private var shortTouched = false
    private var lastAutoShort = ""
    private var loaded = false
    // What the form held when it was shown: a different form is "unsaved changes".
    private var baseName = ""
    private var baseShort = ""
    private var basePrompt = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tone_edit)
        tilName = findViewById(R.id.til_tone_name)
        editName = findViewById(R.id.edit_tone_name)
        tilShort = findViewById(R.id.til_tone_short)
        editShort = findViewById(R.id.edit_tone_short)
        tilPrompt = findViewById(R.id.til_tone_prompt)
        editPrompt = findViewById(R.id.edit_tone_prompt)
        btnSave = findViewById(R.id.btn_tone_save)
        btnReset = findViewById(R.id.btn_tone_reset)
        btnDelete = findViewById(R.id.btn_tone_delete)
        isNew = intent.getBooleanExtra(EXTRA_NEW, false)

        findViewById<View>(R.id.btn_tone_back).setOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        btnSave.setOnClickListener { trySave() }
        btnReset.setOnClickListener { confirmReset() }
        btnDelete.setOnClickListener { confirmDelete() }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = onEdited()
        }
        editName.addTextChangedListener(watcher)
        editShort.addTextChangedListener(watcher)
        editPrompt.addTextChangedListener(watcher)
        // Typing in the key label means the user chose it; until then it follows the name.
        editShort.setOnFocusChangeListener { _, focused -> if (focused) shortTouched = true }

        lifecycleScope.launch {
            val state = ToneStore.load(this@ToneEditActivity)
            tones = state.tones
            val wanted = intent.getStringExtra(EXTRA_TONE_ID)
            tone = when {
                isNew -> Tone(
                    id = "u_" + System.currentTimeMillis(),
                    name = getString(R.string.set_tone_new_name),
                    short = ToneRules.defaultShort(getString(R.string.set_tone_new_name)),
                    desc = getString(R.string.set_tone_new_desc),
                    // A new tone starts from the 專業 instruction.
                    prompt = tones.firstOrNull { it.id == TONE_PRO }?.prompt ?: TonePrompts.PRO,
                    builtin = false
                )
                else -> tones.firstOrNull { it.id == wanted } ?: tones.first()
            }
            show(tone!!)
        }
    }

    private fun show(t: Tone) {
        baseName = t.name
        baseShort = t.short
        basePrompt = t.prompt
        editName.setText(t.name)
        editShort.setText(t.short)
        editPrompt.setText(t.prompt)
        shortTouched = false
        lastAutoShort = ToneRules.defaultShort(t.name)
        btnReset.visibility = if (t.builtin) View.VISIBLE else View.GONE
        // Built-in tones can only be reset; the button is there but at 38%, with the reason under it.
        btnDelete.isEnabled = !t.builtin && !isNew
        btnDelete.alpha = if (btnDelete.isEnabled) 1f else 0.38f
        btnDelete.visibility = if (isNew) View.GONE else View.VISIBLE
        findViewById<View>(R.id.text_tone_builtin_note).visibility = if (t.builtin) View.VISIBLE else View.GONE
        loaded = true
        onEdited()
    }

    private fun otherNames(): List<String> = tones.filter { it.id != tone?.id }.map { it.name }

    private fun hasChanges(): Boolean = loaded && (
        editName.text.toString() != baseName ||
            editShort.text.toString() != baseShort ||
            editPrompt.text.toString() != basePrompt ||
            isNew
        )

    private fun onEdited() {
        if (!loaded) return
        // The key label follows the first characters of the name until the user types one.
        if (!shortTouched && editShort.text.toString() == lastAutoShort) {
            val follow = ToneRules.defaultShort(editName.text.toString())
            if (follow != lastAutoShort) {
                lastAutoShort = follow
                editShort.setText(follow)
            }
        }
        tilName.error = null
        tilShort.error = null
        btnSave.isEnabled = editName.text.toString().isNotBlank() && editPrompt.text.toString().isNotBlank()
    }

    private fun trySave() {
        val name = editName.text.toString().trim()
        val short = editShort.text.toString().trim().ifEmpty { ToneRules.defaultShort(name) }
        val prompt = editPrompt.text.toString()
        var ok = true
        when (ToneRules.nameError(name, otherNames())) {
            ToneRules.NameError.LENGTH -> { tilName.error = getString(R.string.set_tone_name_len); ok = false }
            ToneRules.NameError.DUPLICATE -> { tilName.error = getString(R.string.set_tone_name_dup); ok = false }
            null -> Unit
        }
        if (!ToneRules.shortValid(short)) {
            tilShort.error = getString(R.string.set_tone_short_bad)
            ok = false
        }
        if (!ToneRules.promptValid(prompt)) {
            tilPrompt.error = getString(R.string.set_tone_prompt_help)
            ok = false
        }
        if (!ok) return
        if (!ToneRules.hasInjectionGuard(prompt)) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.set_tone_injection_warn)
                .setNegativeButton(R.string.set_cancel, null)
                .setPositiveButton(R.string.set_tone_injection_save) { _, _ -> save(name, short, prompt) }
                .show()
        } else {
            save(name, short, prompt)
        }
    }

    private fun save(name: String, short: String, prompt: String) {
        val current = tone ?: return
        val updated = current.copy(name = name, short = short, prompt = prompt)
        lifecycleScope.launch {
            val list = if (isNew) tones + updated else tones.map { if (it.id == current.id) updated else it }
            ToneStore.saveTones(this@ToneEditActivity, list)
            finish()
        }
    }

    private fun confirmReset() {
        val current = tone ?: return
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.set_tone_reset_confirm, current.name))
            .setNegativeButton(R.string.set_cancel, null)
            .setPositiveButton(R.string.set_tone_reset) { _, _ ->
                // Back to the built-in instruction; the form is filled, 儲存 still decides.
                TonePrompts.builtinPrompt(current.id)?.let { editPrompt.setText(it) }
            }
            .show()
    }

    private fun confirmDelete() {
        val current = tone ?: return
        if (current.builtin) return
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.set_tone_delete_confirm, current.name))
            .setNegativeButton(R.string.set_cancel, null)
            .setPositiveButton(R.string.set_tone_delete) { _, _ ->
                lifecycleScope.launch {
                    // Deleting the tone in use makes the first remaining tone current (see ToneStore.saveTones).
                    ToneStore.saveTones(this@ToneEditActivity, tones.filter { it.id != current.id })
                    finish()
                }
            }
            .show()
    }

    private fun leave() {
        if (!hasChanges()) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.set_tone_discard)
            .setNegativeButton(R.string.set_cancel, null)
            .setPositiveButton(R.string.set_discard) { _, _ -> finish() }
            .show()
    }
}
