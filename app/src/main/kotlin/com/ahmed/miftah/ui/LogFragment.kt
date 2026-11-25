package com.ahmed.miftah.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ahmed.miftah.MiftahApp
import com.ahmed.miftah.R
import com.ahmed.miftah.core.LogEntry
import com.ahmed.miftah.databinding.FragmentLogBinding
import com.ahmed.miftah.databinding.ItemLogBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogFragment : Fragment() {
    private var _binding: FragmentLogBinding? = null
    private val binding get() = _binding!!
    private val app: MiftahApp get() = requireActivity().application as MiftahApp

    private inner class LogAdapter : RecyclerView.Adapter<LogAdapter.Holder>() {
        var items: List<LogEntry> = emptyList()
        inner class Holder(val b: ItemLogBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = items[position]
            val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(e.timeMs))
            val status = getString(if (e.success) R.string.log_ok else R.string.log_fail)
            val kindLabel = when (e.kind) {
                "tool" -> getString(R.string.log_tool)
                "approval" -> getString(R.string.log_approval)
                else -> e.kind
            }
            holder.b.logLine1.text = "$time · $kindLabel · ${e.providerId} · ${e.modelId} · $status"
            holder.b.logLine2.text = e.detail + if (e.tokens > 0) " · ${e.tokens} tok" else ""
        }
    }

    private val adapter = LogAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.logList.layoutManager = LinearLayoutManager(requireContext())
        binding.logList.adapter = adapter
        binding.logClearButton.setOnClickListener {
            app.requestLog.clear()
            refresh()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (_binding == null) return
        adapter.items = app.requestLog.all().asReversed()
        adapter.notifyDataSetChanged()
        binding.logEmpty.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
