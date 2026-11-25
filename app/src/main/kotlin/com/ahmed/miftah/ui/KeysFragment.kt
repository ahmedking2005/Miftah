package com.ahmed.miftah.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ahmed.miftah.MiftahApp
import com.ahmed.miftah.R
import com.ahmed.miftah.core.AppSettings
import com.ahmed.miftah.core.ChainEntry
import com.ahmed.miftah.core.ProviderDef
import com.ahmed.miftah.core.Providers
import com.ahmed.miftah.databinding.FragmentKeysBinding
import com.ahmed.miftah.databinding.ItemChainBinding
import com.ahmed.miftah.databinding.ItemProviderBinding

class KeysFragment : Fragment() {
    private var _binding: FragmentKeysBinding? = null
    private val binding get() = _binding!!
    private val app: MiftahApp get() = requireActivity().application as MiftahApp

    private inner class ProviderAdapter : RecyclerView.Adapter<ProviderAdapter.Holder>() {
        inner class Holder(val b: ItemProviderBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemProviderBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount(): Int = Providers.all.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val p: ProviderDef = Providers.all[position]
            holder.b.providerName.text = p.displayName
            holder.b.providerSub.text = getString(R.string.keys_keys_count, app.vault.keysFor(p.id).size) +
                " · " + p.models.joinToString { it.displayName }
            holder.b.root.setOnClickListener {
                startActivity(
                    Intent(requireContext(), ProviderDetailActivity::class.java)
                        .putExtra(ProviderDetailActivity.EXTRA_PROVIDER, p.id)
                )
            }
        }
    }

    private inner class ChainAdapter : RecyclerView.Adapter<ChainAdapter.Holder>() {
        var items: List<ChainEntry> = emptyList()
        inner class Holder(val b: ItemChainBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemChainBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = items[position]
            val provider = Providers.byId(e.providerId)
            val model = Providers.model(e.providerId, e.modelId)
            holder.b.chainText.text = "${position + 1}. ${provider?.displayName ?: e.providerId} · ${model?.displayName ?: e.modelId}"
            holder.b.chainUp.setOnClickListener { move(position, -1) }
            holder.b.chainDown.setOnClickListener { move(position, +1) }
            holder.b.chainRemove.setOnClickListener {
                val list = items.toMutableList()
                list.removeAt(position)
                saveChain(list)
            }
        }

        private fun move(position: Int, delta: Int) {
            val target = position + delta
            if (target < 0 || target >= items.size) return
            val list = items.toMutableList()
            val tmp = list[position]
            list[position] = list[target]
            list[target] = tmp
            saveChain(list)
        }
    }

    private val chainAdapter = ChainAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentKeysBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.providersList.layoutManager = LinearLayoutManager(requireContext())
        binding.providersList.adapter = ProviderAdapter()
        binding.chainList.layoutManager = LinearLayoutManager(requireContext())
        binding.chainList.adapter = chainAdapter
        app.vault.getCredential(AppSettings.CRED_GITHUB_TOKEN)?.let {
            binding.githubTokenInput.setText(it)
        }
        binding.githubTokenSave.setOnClickListener {
            val value = binding.githubTokenInput.text?.toString()?.trim().orEmpty()
            if (value.isEmpty()) app.vault.removeCredential(AppSettings.CRED_GITHUB_TOKEN)
            else app.vault.putCredential(AppSettings.CRED_GITHUB_TOKEN, value)
            Toast.makeText(requireContext(), R.string.save, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshChain()
        binding.providersList.adapter?.notifyDataSetChanged()
    }

    private fun refreshChain() {
        chainAdapter.items = app.settings.chain
        chainAdapter.notifyDataSetChanged()
        binding.chainEmptyText.visibility =
            if (chainAdapter.items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun saveChain(list: List<ChainEntry>) {
        app.settings.chain = list
        refreshChain()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
