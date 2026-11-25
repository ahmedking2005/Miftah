package com.ahmed.miftah.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ahmed.miftah.MiftahApp
import com.ahmed.miftah.R
import com.ahmed.miftah.agent.AgentLoop
import com.ahmed.miftah.agent.buildBuiltinRegistry
import com.ahmed.miftah.core.ChatMessage
import com.ahmed.miftah.databinding.FragmentChatBinding
import com.ahmed.miftah.databinding.ItemMessageBinding
import com.ahmed.miftah.skills.SkillContext
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

data class DisplayMessage(val role: String, val text: String, val meta: String = "")

class MessageAdapter : RecyclerView.Adapter<MessageAdapter.Holder>() {
    val items = ArrayList<DisplayMessage>()

    class Holder(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val b = holder.binding
        b.messageRole.text = item.role
        b.messageText.text = item.text
        b.messageMeta.text = item.meta
        b.messageMeta.visibility = if (item.meta.isEmpty()) View.GONE else View.VISIBLE

        // Presentation only: align and tint the bubble by speaker.
        val isUser = item.role == "You"
        val isTool = !isUser && item.role != "Miftah"
        val gravity = if (isUser) Gravity.END else Gravity.START
        for (v in listOf(b.messageRole, b.messageText, b.messageMeta)) {
            (v.layoutParams as LinearLayout.LayoutParams).gravity = gravity
        }
        val ctx = holder.itemView.context
        b.messageText.setBackgroundResource(
            when {
                isUser -> R.drawable.bg_bubble_user
                isTool -> R.drawable.bg_bubble_tool
                else -> R.drawable.bg_bubble_assistant
            }
        )
        b.messageText.setTextColor(
            ContextCompat.getColor(
                ctx,
                when {
                    isUser -> R.color.m3_on_primary_container
                    isTool -> R.color.m3_on_tertiary_container
                    else -> R.color.m3_on_surface
                }
            )
        )
    }
}

class ChatFragment : Fragment() {
    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val adapter = MessageAdapter()
    private val history = ArrayList<ChatMessage>()
    private var sessionSkillId: String? = null
    private var busy = false

    private val app: MiftahApp get() = requireActivity().application as MiftahApp

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.messagesList.layoutManager = LinearLayoutManager(requireContext())
        binding.messagesList.adapter = adapter
        binding.sendButton.setOnClickListener { send() }
        binding.clearButton.setOnClickListener {
            history.clear()
            adapter.items.clear()
            adapter.notifyDataSetChanged()
        }
        binding.skillPickButton.setOnClickListener { pickSkill() }
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        if (_binding == null) return
        binding.statusText.text = when {
            !app.vault.hasAnyKey() -> getString(R.string.chat_no_keys)
            app.settings.chain.isEmpty() -> getString(R.string.keys_chain_empty)
            else -> ""
        }
    }

    private fun pickSkill() {
        val enabled = app.skillStore.enabledSkills()
        val names = arrayOf(getString(R.string.chat_no_skill)) + enabled.map { it.name }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.chat_pick_skill)
            .setItems(names) { _, which ->
                sessionSkillId = if (which == 0) null else enabled[which - 1].id
                val label = if (which == 0) getString(R.string.chat_no_skill) else enabled[which - 1].name
                Toast.makeText(requireContext(), label, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun pushDisplay(role: String, text: String, meta: String = ""): Int {
        adapter.items.add(DisplayMessage(role, text, meta))
        adapter.notifyItemInserted(adapter.items.size - 1)
        binding.messagesList.scrollToPosition(adapter.items.size - 1)
        return adapter.items.size - 1
    }

    private fun send() {
        if (busy) return
        val text = binding.inputText.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (!app.vault.hasAnyKey() || app.settings.chain.isEmpty()) {
            Toast.makeText(requireContext(), binding.statusText.text, Toast.LENGTH_LONG).show()
            return
        }
        binding.inputText.text?.clear()
        pushDisplay("You", text)
        history.add(ChatMessage("user", text))
        busy = true
        binding.sendButton.isEnabled = false
        binding.streamProgress.visibility = View.VISIBLE
        val statusIdx = pushDisplay("Miftah", getString(R.string.chat_thinking))
        val agentMode = binding.agentSwitch.isChecked

        val sessionSkill = sessionSkillId?.let { app.skillStore.byId(it) }
        val system = SkillContext.systemContext(app.skillStore, sessionSkillId)
        val messages = ArrayList<ChatMessage>()
        messages.add(ChatMessage("system", system))
        messages.addAll(history)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (agentMode) runAgent(messages, statusIdx, sessionSkill?.allowedTools)
                else runPlainChat(messages, statusIdx)
            } catch (e: Exception) {
                adapter.items[statusIdx] = DisplayMessage("Miftah", getString(R.string.chat_error) + "\n" + (e.message ?: ""))
                adapter.notifyItemChanged(statusIdx)
            } finally {
                busy = false
                binding.sendButton.isEnabled = true
                if (_binding != null) binding.streamProgress.visibility = View.GONE
            }
        }
    }

    private suspend fun runPlainChat(messages: List<ChatMessage>, statusIdx: Int) {
        val sb = StringBuilder()
        val resp = app.router.chat(
            messages = messages,
            onToken = { token ->
                sb.append(token)
                requireActivity().runOnUiThread {
                    adapter.items[statusIdx] = DisplayMessage("Miftah", sb.toString())
                    adapter.notifyItemChanged(statusIdx)
                }
            }
        )
        history.add(resp.message)
        adapter.items[statusIdx] = DisplayMessage(
            "Miftah",
            resp.message.content.ifEmpty { sb.toString() },
            getString(R.string.chat_via, resp.providerId, resp.modelId)
        )
        adapter.notifyItemChanged(statusIdx)
    }

    private suspend fun runAgent(
        messages: List<ChatMessage>,
        statusIdx: Int,
        allowedTools: List<String>?
    ) {
        val registry = buildBuiltinRegistry(app.vault, app.skillStore)
        val loop = AgentLoop(app.router, registry) { app.requestLog.add(it) }
        var firstEvent = true
        val final = loop.run(
            history = messages,
            allowedTools = if (allowedTools.isNullOrEmpty()) null else allowedTools + listOf("load_skill", "read_skill_file")
        ) { event ->
            requireActivity().runOnUiThread {
                when (event.kind) {
                    "tool_call" -> {
                        if (firstEvent) {
                            adapter.items[statusIdx] = DisplayMessage("Miftah", "")
                            adapter.notifyItemChanged(statusIdx)
                            firstEvent = false
                        }
                        pushDisplay(getString(R.string.chat_tool_call, event.toolName), event.text)
                    }
                    "tool_result" -> pushDisplay("↳ ${event.toolName}", event.text)
                    "final" -> {
                        val meta = getString(R.string.chat_via, event.providerId, event.modelId)
                        if (firstEvent) {
                            adapter.items[statusIdx] = DisplayMessage("Miftah", event.text, meta)
                            adapter.notifyItemChanged(statusIdx)
                            firstEvent = false
                        } else {
                            pushDisplay("Miftah", event.text, meta)
                        }
                    }
                }
            }
        }
        history.add(final)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
