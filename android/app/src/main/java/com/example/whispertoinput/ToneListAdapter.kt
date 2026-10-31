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

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * The tone list of the settings page (SPEC_r8 §14): one 68dp row per tone, a
 * handle to drag it, a tap to edit it. The order is the order of the keyboard's
 * tone menu; [onOrderChanged] gets the new order when a drag ends.
 */
class ToneListAdapter(
    private val onClick: (Tone) -> Unit,
    private val onOrderChanged: (List<Tone>) -> Unit,
    private val startDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<ToneListAdapter.Holder>() {

    var tones: MutableList<Tone> = mutableListOf()
        private set
    private var currentId: String? = null

    /** [currentId] is the tone in use, or null when polishing is off. */
    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Tone>, currentId: String?) {
        tones = list.toMutableList()
        this.currentId = currentId
        notifyDataSetChanged()
    }

    fun move(from: Int, to: Int) {
        if (from !in tones.indices || to !in tones.indices) return
        tones.add(to, tones.removeAt(from))
        notifyItemMoved(from, to)
    }

    fun dropped() = onOrderChanged(tones.toList())

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val handle: View = view.findViewById(R.id.tone_handle)
        val name: TextView = view.findViewById(R.id.tone_name)
        val kind: TextView = view.findViewById(R.id.tone_kind)
        val current: TextView = view.findViewById(R.id.tone_current)
        val preview: TextView = view.findViewById(R.id.tone_preview)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_tone, parent, false))

    override fun getItemCount(): Int = tones.size

    @Suppress("DEPRECATION")
    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val tone = tones[position]
        val context = holder.itemView.context
        holder.name.text = tone.name
        holder.kind.setText(if (tone.builtin) R.string.set_tone_builtin else R.string.set_tone_custom)
        holder.current.visibility = if (tone.id == currentId) View.VISIBLE else View.GONE
        holder.preview.text = tone.prompt.replace(Regex("\\s+"), " ")
        holder.itemView.setOnClickListener { onClick(tone) }
        holder.handle.contentDescription = context.getString(R.string.set_drag_cd, tone.name)
        // Only the handle starts a drag.
        holder.handle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) startDrag(holder)
            false
        }
        // TalkBack accessibility actions for moving up/down
        ViewCompat.setAccessibilityDelegate(holder.itemView, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                val p = holder.adapterPosition
                if (p > 0) {
                    info.addAction(
                        androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            R.id.action_move_up,
                            context.getString(R.string.set_tone_move_up)
                        )
                    )
                }
                if (p in 0 until tones.size - 1) {
                    info.addAction(
                        androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            R.id.action_move_down,
                            context.getString(R.string.set_tone_move_down)
                        )
                    )
                }
            }

            override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                val p = holder.adapterPosition
                if (action == R.id.action_move_up && p > 0) {
                    move(p, p - 1)
                    return true
                }
                if (action == R.id.action_move_down && p in 0 until tones.size - 1) {
                    move(p, p + 1)
                    return true
                }
                return super.performAccessibilityAction(host, action, args)
            }
        })
    }

    /** Drag behaviour: up / down only, started from the handle. */
    fun touchCallback(): ItemTouchHelper.Callback = object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
    ) {
        override fun isLongPressDragEnabled(): Boolean = false

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            move(viewHolder.adapterPosition, target.adapterPosition)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            dropped()
        }
    }
}
