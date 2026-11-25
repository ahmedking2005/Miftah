package com.ahmed.miftah.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ahmed.miftah.MiftahApp
import com.ahmed.miftah.R
import com.ahmed.miftah.agent.AgentLoop
import com.ahmed.miftah.agent.DenyApprovalGate
import com.ahmed.miftah.agent.buildBuiltinRegistry
import com.ahmed.miftah.core.ChatMessage
import com.ahmed.miftah.core.ChatSession
import com.ahmed.miftah.core.CompressionMath
import com.ahmed.miftah.core.CompressionOutcome
import com.ahmed.miftah.core.ContextCompressor
import com.ahmed.miftah.core.Guide
import com.ahmed.miftah.core.MemoryContext
import com.ahmed.miftah.core.PreparedContext
import com.ahmed.miftah.core.SessionTitles
import com.ahmed.miftah.databinding.FragmentChatBinding
import com.ahmed.miftah.databinding.ItemMessageBinding
import com.ahmed.miftah.skills.SkillContext
import com.ahmed.miftah.skills.SkillDraft
import com.ahmed.miftah.skills.ToolUse
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.UUID

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

    // Persistent-session state: the visible history auto-saves under this id.
    private var currentSessionId: String? = null
    private var sessionCreatedMs: Long = 0L

    // Context-compression state: [summaryCache] folds the first
    // [summaryCovered] history messages. The visible history is never touched.
    private var summaryCache: String? = null
    private var summaryCovered: Int = 0

    // Last completed agent run (for "save as skill").
    private val lastRunUses = ArrayList<ToolUse>()
    private var lastRunGoal: String = ""

    private val app: MiftahApp get() = requireActivity().application as MiftahApp

    private val historyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        when {
            data.getBooleanExtra(SessionHistoryActivity.EXTRA_NEW_SESSION, false) ->
                resetConversation(deleteStored = false)
            data.getStringExtra(SessionHistoryActivity.EXTRA_SESSION_ID) != null ->
                app.sessionStore.byId(data.getStringExtra(SessionHistoryActivity.EXTRA_SESSION_ID)!!)
                    ?.let { loadSession(it) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.messagesList.layoutManager = LinearLayoutManager(requireContext())
        binding.messagesList.adapter = adapter
        binding.sendButton.setOnClickListener { send() }
        binding.clearButton.setOnClickListener { resetConversation(deleteStored = true) }
        binding.skillPickButton.setOnClickListener { pickSkill() }
        binding.historyButton.setOnClickListener {
            historyLauncher.launch(Intent(requireContext(), SessionHistoryActivity::class.java))
        }
        binding.compressButton.setOnClickListener { compressNow() }
        binding.saveSkillButton.setOnClickListener { saveSkillDraft() }
        binding.guideOpenButton.setOnClickListener {
            // Jump to the Keys tab, where the step-by-step guide lives.
            requireActivity()
                .findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
                ?.selectedItemId = R.id.nav_keys
        }
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        if (_binding == null) return
        val hasKeys = app.vault.hasAnyKey()
        // First-run pointer to the guide: same automatic rule as the guide
        // itself — visible only while no key exists, gone once one does.
        binding.guideBanner.visibility =
            if (Guide.shouldShow(hasKeys)) View.VISIBLE else View.GONE
        binding.statusText.text = when {
            !hasKeys -> getString(R.string.chat_no_keys)
            app.settings.chain.isEmpty() -> getString(R.string.keys_chain_empty)
            else -> ""
        }
        updateCompressVisibility()
    }

    /** Compress is offered only when there is something worth compressing. */
    private fun updateCompressVisibility() {
        if (_binding == null) return
        val outgoing = history.drop(summaryCovered)
        val withSummary = listOfNotNull(
            summaryCache?.let { ContextCompressor.summaryMessage(it) }
        ) + outgoing
        val worth = outgoing.isNotEmpty() &&
            CompressionMath.splitKeepingLast(outgoing).first.isNotEmpty() &&
            CompressionMath.estimateTokens(withSummary) > ContextCompressor.DEFAULT_CAP_TOKENS
        binding.compressButton.visibility = if (worth) View.VISIBLE else View.GONE
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

    // ---------------- sessions ----------------

    private fun persistSession() {
        if (history.isEmpty()) return
        val now = System.currentTimeMillis()
        if (currentSessionId == null) {
            currentSessionId = UUID.randomUUID().toString()
            sessionCreatedMs = now
        }
        app.sessionStore.upsert(
            ChatSession(
                id = currentSessionId!!,
                createdMs = sessionCreatedMs,
                updatedMs = now,
                title = SessionTitles.titleFor(history),
                messages = ArrayList(history)
            )
        )
    }

    private fun resetConversation(deleteStored: Boolean) {
        if (deleteStored) currentSessionId?.let { app.sessionStore.delete(it) }
        currentSessionId = null
        sessionCreatedMs = 0L
        summaryCache = null
        summaryCovered = 0
        lastRunUses.clear()
        lastRunGoal = ""
        binding.saveSkillBanner.visibility = View.GONE
        history.clear()
        adapter.items.clear()
        adapter.notifyDataSetChanged()
        updateCompressVisibility()
    }

    private fun loadSession(session: ChatSession) {
        resetConversation(deleteStored = false)
        currentSessionId = session.id
        sessionCreatedMs = session.createdMs
        history.addAll(session.messages)
        for (m in session.messages) {
            when (m.role) {
                "user" -> pushDisplay("You", m.content)
                "assistant" -> {
                    if (m.content.isNotBlank()) pushDisplay("Miftah", m.content)
                    m.toolCalls.forEach { tc ->
                        pushDisplay(getString(R.string.chat_tool_call, tc.name), tc.argumentsJson)
                    }
                }
                "tool" -> pushDisplay("↳ ${m.name ?: "tool"}", m.content)
            }
        }
        updateCompressVisibility()
    }

    // ---------------- compression ----------------

    private fun makeCompressor(): ContextCompressor = ContextCompressor { toSummarize ->
        val transcript = toSummarize.joinToString("\n") { m ->
            buildString {
                append(m.role).append(": ").append(m.content)
                m.toolCalls.forEach { tc ->
                    append("\n  tool_call ").append(tc.name).append(' ').append(tc.argumentsJson)
                }
            }
        }
        val resp = app.router.chat(
            messages = listOf(
                ChatMessage(
                    "system",
                    "Summarize the following conversation excerpt into a compact factual summary. " +
                        "Preserve goals, decisions, names, numbers, file paths and outcomes. Write only the summary."
                ),
                ChatMessage("user", transcript)
            )
        )
        resp.message.content
    }

    /** Applies a prepared context: caches a fresh summary and shows the one-line marker. */
    private fun applyCompression(prepared: PreparedContext) {
        when (val outcome = prepared.outcome) {
            CompressionOutcome.Compressed -> {
                summaryCache = prepared.summary
                summaryCovered += prepared.consumedCount
                pushDisplay(getString(R.string.chat_context_label), getString(R.string.chat_compressed_marker))
            }
            is CompressionOutcome.Trimmed ->
                pushDisplay(
                    getString(R.string.chat_context_label),
                    getString(R.string.chat_trimmed_marker, outcome.droppedTurns)
                )
            else -> Unit
        }
    }

    /** Manual trigger: the same compression path the send flow uses, on demand. */
    private fun compressNow() {
        if (busy || history.isEmpty()) return
        busy = true
        binding.sendButton.isEnabled = false
        binding.streamProgress.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val prepared = makeCompressor().prepare(history.drop(summaryCovered), summaryCache)
                applyCompression(prepared)
                if (prepared.outcome == CompressionOutcome.NotNeeded ||
                    prepared.outcome == CompressionOutcome.SentUncompressed
                ) {
                    Toast.makeText(
                        requireContext(),
                        if (prepared.outcome == CompressionOutcome.NotNeeded) {
                            R.string.chat_nothing_to_compress
                        } else {
                            R.string.chat_compress_failed
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                busy = false
                binding.sendButton.isEnabled = true
                if (_binding != null) {
                    binding.streamProgress.visibility = View.GONE
                    updateCompressVisibility()
                }
            }
        }
    }

    // ---------------- save as skill ----------------

    private fun saveSkillDraft() {
        if (busy) return
        val uses = lastRunUses.toList()
        val goal = lastRunGoal
        if (!SkillDraft.isWorthSaving(uses)) return
        busy = true
        binding.sendButton.isEnabled = false
        binding.streamProgress.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val probe = buildBuiltinRegistry(
                    app.vault, app.skillStore,
                    approvalGate = DenyApprovalGate,
                    memoryStore = app.memoryStore, sessionStore = app.sessionStore
                )
                val registered = probe.defs().map { it.name }.toSet()
                var draft = SkillDraft.template(goal, uses, registered)
                // One model call to write a nicer draft; any failure keeps the template.
                try {
                    val transcript = buildString {
                        append("Goal: ").append(goal).append("\n\nTool calls:\n")
                        uses.forEach { u ->
                            append("- ").append(u.name).append(' ').append(u.argumentsJson)
                                .append(" -> ").append(u.result.take(300)).append('\n')
                        }
                    }
                    val resp = app.router.chat(
                        messages = listOf(
                            ChatMessage(
                                "system",
                                "You write reusable agent skill files. Reply with ONLY a SKILL.md document: " +
                                    "YAML frontmatter with name (short slug) and description (one line), then a markdown " +
                                    "body with a Goal section and a Steps section describing the workflow generically " +
                                    "so it can be reused for similar requests."
                            ),
                            ChatMessage("user", transcript)
                        )
                    )
                    SkillDraft.fromModelMarkdown(resp.message.content, uses, registered, goal)
                        ?.let { draft = it }
                } catch (e: Exception) {
                    // Template draft stands.
                }
                val skill = app.skillStore.importMarkdown(SkillDraft.toMarkdown(draft), "chat")
                if (skill != null) {
                    binding.saveSkillBanner.visibility = View.GONE
                    Toast.makeText(requireContext(), R.string.chat_skill_saved, Toast.LENGTH_SHORT).show()
                    startActivity(
                        Intent(requireContext(), SkillReviewActivity::class.java)
                            .putExtra(SkillReviewActivity.EXTRA_SKILL, skill.id)
                    )
                } else {
                    Toast.makeText(requireContext(), R.string.chat_skill_failed, Toast.LENGTH_LONG).show()
                }
            } finally {
                busy = false
                binding.sendButton.isEnabled = true
                if (_binding != null) binding.streamProgress.visibility = View.GONE
            }
        }
    }

    // ---------------- sending ----------------

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
        binding.saveSkillBanner.visibility = View.GONE
        lastRunUses.clear()
        lastRunGoal = text
        val statusIdx = pushDisplay("Miftah", getString(R.string.chat_thinking))
        val agentMode = binding.agentSwitch.isChecked

        val sessionSkill = sessionSkillId?.let { app.skillStore.byId(it) }
        // System context: skills (progressive disclosure) + long-term memory notes.
        val system = SkillContext.systemContext(app.skillStore, sessionSkillId) +
            MemoryContext.section(app.memoryStore.notes())

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Compression touches only the outgoing copy of the conversation.
                val prepared = makeCompressor().prepare(history.drop(summaryCovered), summaryCache)
                applyCompression(prepared)
                val messages = ArrayList<ChatMessage>()
                messages.add(ChatMessage("system", system))
                messages.addAll(prepared.messages)
                if (agentMode) runAgent(messages, statusIdx, sessionSkill?.allowedTools)
                else runPlainChat(messages, statusIdx)
            } catch (e: Exception) {
                adapter.items[statusIdx] = DisplayMessage("Miftah", getString(R.string.chat_error) + "\n" + (e.message ?: ""))
                adapter.notifyItemChanged(statusIdx)
            } finally {
                persistSession()
                busy = false
                binding.sendButton.isEnabled = true
                if (_binding != null) {
                    binding.streamProgress.visibility = View.GONE
                    updateCompressVisibility()
                }
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
        // The batched-approval gate covers every write tool (Stage 2 service
        // tools + Stage 3 script runs + save_memory): one bundle, one decision.
        val registry = buildBuiltinRegistry(
            app.vault,
            app.skillStore,
            approvalGate = ChatApprovalGate(requireActivity()),
            audit = { app.requestLog.add(it) },
            scriptRunner = com.ahmed.miftah.agent.ScriptRunner(
                com.ahmed.miftah.agent.ChaquoPythonBridge(requireContext().applicationContext)
            ),
            memoryStore = app.memoryStore,
            sessionStore = app.sessionStore
        )
        val loop = AgentLoop(app.router, registry) { app.requestLog.add(it) }
        var firstEvent = true
        val final = loop.run(
            history = messages,
            allowedTools = if (allowedTools.isNullOrEmpty()) null
                else allowedTools + listOf("load_skill", "read_skill_file", "run_skill_script")
        ) { event ->
            // Record the run's tool uses synchronously (draft data), then update UI.
            when (event.kind) {
                "tool_call" -> lastRunUses.add(ToolUse(event.toolName, event.text, ""))
                "tool_result" -> {
                    val idx = lastRunUses.indexOfLast { it.name == event.toolName && it.result.isEmpty() }
                    if (idx >= 0) lastRunUses[idx] = lastRunUses[idx].copy(result = event.text)
                }
            }
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
        // Offer "save as skill" only for runs that actually used >= 2 tools.
        if (SkillDraft.isWorthSaving(lastRunUses) && _binding != null) {
            requireActivity().runOnUiThread {
                if (_binding == null) return@runOnUiThread
                binding.saveSkillText.text = getString(R.string.chat_save_skill_text, lastRunUses.size)
                binding.saveSkillBanner.visibility = View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
