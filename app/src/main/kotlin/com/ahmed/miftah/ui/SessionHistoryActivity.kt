package com.ahmed.miftah.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ahmed.miftah.MiftahApp
import com.ahmed.miftah.R
import com.ahmed.miftah.core.ChatSession
import com.ahmed.miftah.databinding.ActivityHistoryBinding
import com.ahmed.miftah.databinding.ItemSessionBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Past conversation sessions, newest first. Tapping one returns its id to
 * the chat screen, which reopens it (read/write-continuing). Sessions can
 * be deleted here; "New session" returns a flag instead of an id.
 */
class SessionHistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHistoryBinding
    private val app: MiftahApp get() = application as MiftahApp
    private val adapter = SessionAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.history_title)
        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = adapter
        binding.historyNewButton.setOnClickListener {
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_NEW_SESSION, true))
            finish()
        }
        refresh()
    }

    private fun refresh() {
        val sessions = app.sessionStore.all().sortedByDescending { it.updatedMs }
        adapter.items.clear()
        adapter.items.addAll(sessions)
        adapter.notifyDataSetChanged()
        binding.historyEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmDelete(session: ChatSession) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_delete_title)
            .setMessage(R.string.history_delete_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                app.sessionStore.delete(session.id)
                refresh()
            }
            .show()
    }

    inner class SessionAdapter : RecyclerView.Adapter<SessionAdapter.Holder>() {
        val items = ArrayList<ChatSession>()

        inner class Holder(val binding: ItemSessionBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val session = items[position]
            val b = holder.binding
            b.sessionTitle.text = session.title
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(session.updatedMs))
            b.sessionMeta.text = getString(R.string.history_meta, date, session.messages.size)
            b.root.setOnClickListener {
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SESSION_ID, session.id))
                finish()
            }
            b.sessionDelete.setOnClickListener { confirmDelete(session) }
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_NEW_SESSION = "new_session"
    }
}
