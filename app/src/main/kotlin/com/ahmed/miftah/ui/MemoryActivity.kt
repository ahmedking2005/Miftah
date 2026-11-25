package com.ahmed.miftah.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ahmed.miftah.MiftahApp
import com.ahmed.miftah.R
import com.ahmed.miftah.core.MemoryNote
import com.ahmed.miftah.databinding.ActivityMemoryBinding
import com.ahmed.miftah.databinding.ItemMemoryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Long-term memory notes: everything the agent saved (with the user's
 * approval, via save_memory) plus notes the user adds directly. Notes are
 * injected into the chat's system context at chat start, newest first,
 * capped — the user can edit or delete any of them here.
 */
class MemoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMemoryBinding
    private val app: MiftahApp get() = application as MiftahApp
    private val adapter = MemoryAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMemoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.memory_title)
        binding.memoryList.layoutManager = LinearLayoutManager(this)
        binding.memoryList.adapter = adapter
        binding.memoryAddButton.setOnClickListener {
            val text = binding.memoryInput.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@setOnClickListener
            app.memoryStore.add(text)
            binding.memoryInput.text?.clear()
            refresh()
        }
        refresh()
    }

    private fun refresh() {
        val notes = app.memoryStore.notes().sortedByDescending { it.createdMs }
        adapter.items.clear()
        adapter.items.addAll(notes)
        adapter.notifyDataSetChanged()
        binding.memoryEmpty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun editNote(note: MemoryNote) {
        val input = EditText(this).apply {
            setText(note.text)
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.memory_edit)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    app.memoryStore.update(note.id, text)
                    refresh()
                }
            }
            .show()
    }

    private fun confirmDelete(note: MemoryNote) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.memory_delete_title)
            .setMessage(R.string.memory_delete_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                app.memoryStore.delete(note.id)
                refresh()
            }
            .show()
    }

    inner class MemoryAdapter : RecyclerView.Adapter<MemoryAdapter.Holder>() {
        val items = ArrayList<MemoryNote>()

        inner class Holder(val binding: ItemMemoryBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemMemoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val note = items[position]
            val b = holder.binding
            b.memoryText.text = note.text
            b.memoryDate.text = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(Date(note.createdMs))
            b.memoryEdit.setOnClickListener { editNote(note) }
            b.memoryDelete.setOnClickListener { confirmDelete(note) }
        }
    }
}
