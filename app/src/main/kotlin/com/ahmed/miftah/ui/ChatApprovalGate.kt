package com.ahmed.miftah.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ahmed.miftah.R
import com.ahmed.miftah.agent.ApprovalBundle
import com.ahmed.miftah.agent.ApprovalDecision
import com.ahmed.miftah.agent.ApprovalGate
import com.ahmed.miftah.databinding.DialogApprovalBinding
import com.ahmed.miftah.databinding.ItemApprovalBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The chat UI's [ApprovalGate]: shows ONE Material 3 dialog per
 * [ApprovalBundle] — summary line (action + count), destination pill,
 * expandable item list with a checkbox per item (uncheck = exclude that
 * item), and a single Approve/Reject decision for the whole bundle.
 * Closing the dialog without deciding counts as [ApprovalDecision.Dismissed].
 *
 * v1 ships no write tools, so this gate is wired into the chat agent's
 * registry but stays dormant until the first write tool is registered;
 * bundle decisions are already recorded in the Log tab (kind "approval").
 */
class ChatApprovalGate(private val context: Context) : ApprovalGate {

    private inner class ItemsAdapter(private val bundle: ApprovalBundle) :
        RecyclerView.Adapter<ItemsAdapter.Holder>() {
        /** Items start included; the user unchecks the ones to exclude. */
        val included = BooleanArray(bundle.items.size) { true }

        inner class Holder(val b: ItemApprovalBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemApprovalBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = bundle.items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = bundle.items[position]
            holder.b.approvalItemTitle.text = item.toolName
            holder.b.approvalItemDesc.text = item.description
            holder.b.approvalCheck.setOnCheckedChangeListener(null)
            holder.b.approvalCheck.isChecked = included[position]
            holder.b.approvalCheck.setOnCheckedChangeListener { _, checked ->
                included[position] = checked
            }
        }

        fun excludedIndexes(): Set<Int> =
            included.indices.filter { !included[it] }.toSet()
    }

    override suspend fun approveBundle(bundle: ApprovalBundle): ApprovalDecision =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                val binding = DialogApprovalBinding.inflate(LayoutInflater.from(context))
                binding.approvalSummary.text =
                    context.getString(R.string.approval_summary, bundle.items.size, bundle.actionLabel)
                if (bundle.destination.isNotBlank()) {
                    binding.approvalDestination.visibility = View.VISIBLE
                    binding.approvalDestination.text =
                        context.getString(R.string.approval_destination, bundle.destination)
                }
                val adapter = ItemsAdapter(bundle)
                binding.approvalList.layoutManager = LinearLayoutManager(context)
                binding.approvalList.adapter = adapter

                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.approval_title)
                    .setView(binding.root)
                    .setPositiveButton(R.string.approval_approve) { _, _ ->
                        if (cont.isActive) {
                            cont.resume(ApprovalDecision.Approved(adapter.excludedIndexes()))
                        }
                    }
                    .setNegativeButton(R.string.approval_reject) { _, _ ->
                        if (cont.isActive) cont.resume(ApprovalDecision.Rejected)
                    }
                    .create()
                dialog.setOnDismissListener {
                    if (cont.isActive) cont.resume(ApprovalDecision.Dismissed)
                }
                cont.invokeOnCancellation { dialog.dismiss() }
                dialog.show()
            }
        }
}
