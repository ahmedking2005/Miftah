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
import com.ahmed.miftah.core.ProviderQuota
import com.ahmed.miftah.core.Providers
import com.ahmed.miftah.core.QuotaAggregator
import com.ahmed.miftah.core.QuotaScope
import com.ahmed.miftah.core.quotaInput
import com.ahmed.miftah.databinding.FragmentBalanceBinding
import com.ahmed.miftah.databinding.ItemBalanceBinding
import java.text.NumberFormat

/**
 * «رصيدي» — combined quota dashboard. Every number is an estimate from the
 * catalog's documented free tiers (see Quota.kt's honesty contract): providers
 * expose no live balance. Providers without a key never appear; providers
 * whose limit is undocumented show "—" and stay out of the sums.
 */
class BalanceFragment : Fragment() {
    private var _binding: FragmentBalanceBinding? = null
    private val binding get() = _binding!!
    private val app: MiftahApp get() = requireActivity().application as MiftahApp

    private inner class BalanceAdapter : RecyclerView.Adapter<BalanceAdapter.Holder>() {
        var items: List<ProviderQuota> = emptyList()
        inner class Holder(val b: ItemBalanceBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemBalanceBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val q = items[position]
            val input = q.input
            val fmt = NumberFormat.getInstance()
            holder.b.balanceProviderName.text = input.displayName
            holder.b.balanceProviderMeta.text = getString(
                if (input.scope == QuotaScope.PER_KEY) R.string.balance_keys_per_key
                else R.string.balance_keys_account,
                input.keyCount
            )
            holder.b.balanceProviderRemaining.text =
                q.remainingToday?.let { fmt.format(it) } ?: getString(R.string.balance_unknown)
            holder.b.balanceProviderUsed.text = getString(
                R.string.balance_used_limit,
                input.usedToday,
                input.dailyRequestLimit?.let { fmt.format(it) } ?: getString(R.string.balance_unknown)
            )
            val limit = input.dailyRequestLimit
            if (limit != null && limit > 0) {
                holder.b.balanceProgress.visibility = View.VISIBLE
                val pct = (input.usedToday.toDouble() / limit * 100).toInt().coerceIn(0, 100)
                holder.b.balanceProgress.setProgressCompat(pct, false)
            } else {
                holder.b.balanceProgress.visibility = View.GONE
            }
        }
    }

    private val adapter = BalanceAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBalanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.balanceList.layoutManager = LinearLayoutManager(requireContext())
        binding.balanceList.adapter = adapter
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (_binding == null) return
        val inputs = Providers.all.mapNotNull { p ->
            val keys = app.vault.keysFor(p.id)
            if (keys.isEmpty()) null
            else p.quotaInput(keys.size, keys.sumOf { app.rateTracker.usesToday(it.id) })
        }
        val (quotas, totals) = QuotaAggregator.aggregate(inputs)
        adapter.items = quotas
        adapter.notifyDataSetChanged()

        val fmt = NumberFormat.getInstance()
        binding.balanceEmpty.visibility = if (inputs.isEmpty()) View.VISIBLE else View.GONE
        binding.balanceHero.visibility = if (inputs.isEmpty()) View.GONE else View.VISIBLE
        binding.balanceRemainingValue.text = fmt.format(totals.estimatedRemainingToday)
        binding.balanceUsedLine.text =
            if (totals.providersWithKnownLimit == totals.providersTotal) {
                getString(R.string.balance_used_of, totals.usedToday, totals.knownDailyRequestLimit)
            } else {
                getString(
                    R.string.balance_used_of_partial,
                    totals.usedToday, totals.providersWithKnownLimit, totals.providersTotal
                )
            }
        binding.balanceTokensLine.text = getString(
            R.string.balance_tokens,
            totals.knownDailyTokenLimit?.let { fmt.format(it) } ?: getString(R.string.balance_unknown)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
