package com.ahmed.miftah.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ahmed.miftah.MiftahApp
import com.ahmed.miftah.R
import com.ahmed.miftah.core.AppSettings
import com.ahmed.miftah.core.ChainEntry
import com.ahmed.miftah.core.Guide
import com.ahmed.miftah.core.ProviderDef
import com.ahmed.miftah.core.Providers
import com.ahmed.miftah.databinding.FragmentKeysBinding
import com.ahmed.miftah.databinding.ItemChainBinding
import com.ahmed.miftah.databinding.ItemProviderBinding
import com.google.android.material.button.MaterialButton

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
        buildGuideButtons()
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
        binding.serviceAddSave.setOnClickListener { addService() }
        refreshServices()
    }

    override fun onResume() {
        super.onResume()
        refreshChain()
        refreshGuide()
        refreshServices()
        binding.providersList.adapter?.notifyDataSetChanged()
    }

    /** Saved generic services: name + base URL + header + key tail, each removable. Keys never shown beyond the last 4. */
    private fun refreshServices() {
        if (_binding == null) return
        val services = app.vault.services()
        binding.servicesEmpty.visibility = if (services.isEmpty()) View.VISIBLE else View.GONE
        val container = binding.servicesList
        container.removeAllViews()
        val density = resources.displayMetrics.density
        for (service in services) {
            val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (10 * density).toInt() }
                radius = 24 * density
            }
            val inner = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
            }
            val title = android.widget.TextView(requireContext()).apply {
                text = service.name
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val sub = android.widget.TextView(requireContext()).apply {
                text = "${service.baseUrl}\n${service.headerName}: ${service.authPrefix}${service.keyTail()}"
                textSize = 13f
                setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        requireContext(), R.color.m3_on_surface_variant
                    )
                )
            }
            val remove = MaterialButton(
                android.view.ContextThemeWrapper(requireContext(), R.style.Widget_Miftah_Button_Text_Small)
            ).apply {
                text = getString(R.string.remove)
                setOnClickListener {
                    app.vault.removeService(service.id)
                    refreshServices()
                }
            }
            inner.addView(title)
            inner.addView(sub)
            inner.addView(remove)
            card.addView(inner)
            container.addView(card)
        }
    }

    private fun addService() {
        val name = binding.serviceNameInput.text?.toString().orEmpty()
        val baseUrl = binding.serviceBaseUrlInput.text?.toString().orEmpty()
        val header = binding.serviceHeaderInput.text?.toString().orEmpty()
        val prefix = binding.servicePrefixInput.text?.toString() ?: ""
        val key = binding.serviceKeyInput.text?.toString().orEmpty()
        when (com.ahmed.miftah.core.ServiceStore.validate(name, baseUrl, header, key)) {
            "name" -> return toast(R.string.services_invalid_name)
            "url" -> return toast(R.string.services_invalid_url)
            "header" -> return toast(R.string.services_invalid_header)
            "key" -> return toast(R.string.services_invalid_key)
        }
        app.vault.addService(name, baseUrl, header, prefix, key)
        binding.serviceNameInput.text?.clear()
        binding.serviceBaseUrlInput.text?.clear()
        binding.serviceKeyInput.text?.clear()
        refreshServices()
        Toast.makeText(requireContext(), R.string.save, Toast.LENGTH_SHORT).show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
    }

    /** One "get a free key" button per provider inside the guide's step 2. */
    private fun buildGuideButtons() {
        val container = binding.guideProviders
        container.removeAllViews()
        for (p in Providers.all) {
            val button = MaterialButton(
                android.view.ContextThemeWrapper(requireContext(), R.style.Widget_Miftah_Button_Tonal)
            )
            button.text = getString(R.string.guide_get_key_for, p.displayName)
            button.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * resources.displayMetrics.density).toInt() }
            button.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.keyUrl)))
            }
            container.addView(button)
        }
    }

    /** The guide shows exactly while no provider has a key, and hides itself once one exists. */
    private fun refreshGuide() {
        binding.guideCard.visibility =
            if (Guide.shouldShow(app.vault.hasAnyKey())) View.VISIBLE else View.GONE
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
