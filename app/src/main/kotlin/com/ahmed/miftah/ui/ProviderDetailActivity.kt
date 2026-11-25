package com.ahmed.miftah.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ahmed.miftah.MiftahApp
import com.ahmed.miftah.R
import com.ahmed.miftah.core.ChainEntry
import com.ahmed.miftah.core.KeyTestFailure
import com.ahmed.miftah.core.KeyTestResult
import com.ahmed.miftah.core.KeyTester
import com.ahmed.miftah.core.LogEntry
import com.ahmed.miftah.core.ModelDef
import com.ahmed.miftah.core.ProviderDef
import com.ahmed.miftah.core.Providers
import com.ahmed.miftah.core.StoredKey
import com.ahmed.miftah.databinding.ActivityProviderBinding
import com.ahmed.miftah.databinding.ItemKeyBinding
import com.ahmed.miftah.databinding.ItemModelBinding
import kotlinx.coroutines.launch

class ProviderDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityProviderBinding
    private lateinit var provider: ProviderDef
    private val app: MiftahApp get() = application as MiftahApp
    private val keyTester = KeyTester()

    /** Display-only test outcomes per key id; a failed test never touches the stored key. */
    private val testResults = HashMap<String, String>()
    private val testRunning = HashSet<String>()

    private inner class KeysAdapter : RecyclerView.Adapter<KeysAdapter.Holder>() {
        var items: List<StoredKey> = emptyList()
        inner class Holder(val b: ItemKeyBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemKeyBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val k = items[position]
            val label = k.label.ifEmpty { "Key" }
            holder.b.keyText.text = "$label · …${k.secret.takeLast(4)} · ${app.rateTracker.usesToday(k.id)} today"
            holder.b.keyDelete.setOnClickListener {
                app.vault.removeKey(provider.id, k.id)
                testResults.remove(k.id)
                refresh()
            }
            val running = k.id in testRunning
            holder.b.keyTest.isEnabled = !running
            holder.b.keyTest.text = getString(if (running) R.string.keytest_testing else R.string.provider_test_key)
            holder.b.keyTest.setOnClickListener { testKey(k) }
            val result = testResults[k.id]
            holder.b.keyTestResult.visibility = if (result == null) View.GONE else View.VISIBLE
            holder.b.keyTestResult.text = result ?: ""
        }
    }

    /**
     * Runs the cheapest real validation call for this key and shows the honest
     * outcome. The secret is used in memory only; the audit log records the
     * provider and the result class — never the key.
     */
    private fun testKey(key: StoredKey) {
        if (!testRunning.add(key.id)) return
        notifyKeyChanged(key.id)
        lifecycleScope.launch {
            val result = keyTester.test(provider, key.secret)
            testRunning.remove(key.id)
            testResults[key.id] = when (result) {
                is KeyTestResult.Success ->
                    if (result.sampleModels.isEmpty()) getString(R.string.keytest_ok, result.modelCount)
                    else getString(R.string.keytest_ok_samples, result.modelCount, result.sampleModels.joinToString(", "))
                is KeyTestResult.Failure -> when (result.kind) {
                    KeyTestFailure.INVALID_KEY -> getString(R.string.keytest_invalid, result.httpCode)
                    KeyTestFailure.RATE_LIMITED -> getString(R.string.keytest_rate_limited)
                    KeyTestFailure.SERVER_ERROR -> getString(R.string.keytest_server, result.httpCode)
                    KeyTestFailure.NETWORK -> getString(R.string.keytest_network)
                    KeyTestFailure.OTHER -> getString(R.string.keytest_failed, result.httpCode)
                }
            }
            app.requestLog.add(
                LogEntry(
                    timeMs = System.currentTimeMillis(),
                    kind = "keytest",
                    providerId = provider.id,
                    modelId = "",
                    success = result is KeyTestResult.Success,
                    detail = when (result) {
                        is KeyTestResult.Success -> "key test ok · ${result.modelCount} models"
                        is KeyTestResult.Failure -> "key test failed · ${result.kind} · HTTP ${result.httpCode}"
                    }
                )
            )
            notifyKeyChanged(key.id)
        }
    }

    private fun notifyKeyChanged(keyId: String) {
        val idx = keysAdapter.items.indexOfFirst { it.id == keyId }
        if (idx >= 0) keysAdapter.notifyItemChanged(idx)
    }

    private inner class ModelsAdapter : RecyclerView.Adapter<ModelsAdapter.Holder>() {
        inner class Holder(val b: ItemModelBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemModelBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount(): Int = provider.models.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val m: ModelDef = provider.models[position]
            val inChain = app.settings.chain.any { it.providerId == provider.id && it.modelId == m.id }
            holder.b.modelText.text = "${m.displayName}\n${m.rpm}/min · ${m.rpd}/day"
            holder.b.modelAddChain.text = getString(if (inChain) R.string.provider_in_chain else R.string.provider_add_to_chain)
            holder.b.modelAddChain.isEnabled = !inChain
            holder.b.modelAddChain.setOnClickListener {
                app.settings.chain = app.settings.chain + ChainEntry(provider.id, m.id)
                notifyDataSetChanged()
            }
        }
    }

    private val keysAdapter = KeysAdapter()
    private val modelsAdapter = ModelsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val providerId = intent.getStringExtra(EXTRA_PROVIDER) ?: finish().let { return }
        provider = Providers.byId(providerId) ?: finish().let { return }
        binding = ActivityProviderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = provider.displayName
        binding.providerTitle.text = provider.displayName
        val first = provider.models.firstOrNull()
        binding.providerLimits.text = getString(R.string.provider_limits, first?.rpm ?: 0, first?.rpd ?: 0)
        binding.getKeyButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.keyUrl)))
        }
        binding.keysList.layoutManager = LinearLayoutManager(this)
        binding.keysList.adapter = keysAdapter
        binding.modelsList.layoutManager = LinearLayoutManager(this)
        binding.modelsList.adapter = modelsAdapter
        binding.newKeyValue.hint = provider.keyHint
        binding.addKeyButton.setOnClickListener {
            val value = binding.newKeyValue.text?.toString()?.trim().orEmpty()
            if (value.isEmpty()) {
                Toast.makeText(this, provider.keyHint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            app.vault.addKey(provider.id, binding.newKeyLabel.text?.toString()?.trim().orEmpty(), value)
            binding.newKeyValue.text?.clear()
            binding.newKeyLabel.text?.clear()
            refresh()
        }
        refresh()
    }

    private fun refresh() {
        keysAdapter.items = app.vault.keysFor(provider.id)
        keysAdapter.notifyDataSetChanged()
        modelsAdapter.notifyDataSetChanged()
    }

    companion object {
        const val EXTRA_PROVIDER = "provider_id"
    }
}
