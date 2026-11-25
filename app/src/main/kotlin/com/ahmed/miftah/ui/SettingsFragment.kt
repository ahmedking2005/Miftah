package com.ahmed.miftah.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.ahmed.miftah.MiftahApp
import com.ahmed.miftah.R
import com.ahmed.miftah.core.UpdateCheckResult
import com.ahmed.miftah.core.UpdateChecker
import com.ahmed.miftah.databinding.FragmentSettingsBinding
import com.ahmed.miftah.server.ServerNet
import com.ahmed.miftah.server.ServerService
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val app: MiftahApp get() = requireActivity().application as MiftahApp

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        startServer()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.unifiedKeyText.text = app.settings.unifiedKey(app.vault)
        binding.portInput.setText(app.settings.serverPort.toString())
        binding.serverSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) ensurePermissionThenStart() else ServerService.stop(requireContext())
            refreshServerStatus()
        }
        binding.copyKeyButton.setOnClickListener {
            copyToClipboard("Miftah unified key", app.settings.unifiedKey(app.vault))
        }
        binding.regenKeyButton.setOnClickListener {
            binding.unifiedKeyText.text = app.settings.regenerateUnifiedKey(app.vault)
        }
        binding.copyLocalUrlButton.setOnClickListener {
            copyToClipboard("Miftah base URL", binding.connectLocalUrl.text.toString())
        }
        binding.copyLanUrlButton.setOnClickListener {
            copyToClipboard("Miftah base URL", binding.connectLanUrl.text.toString())
        }
        binding.copyTsUrlButton.setOnClickListener {
            copyToClipboard("Miftah base URL", binding.connectTsUrl.text.toString())
        }
        binding.networkModeSwitch.setOnCheckedChangeListener { _, checked ->
            app.settings.serverNetworkMode = checked
            if (ServerService.running) {
                // Re-starting the service rebinds the socket with the new reach
                // immediately; the state labels below reflect the live mode.
                ServerService.start(requireContext())
                Toast.makeText(requireContext(), R.string.settings_network_applied, Toast.LENGTH_SHORT).show()
            }
            refreshServerStatus()
        }
        binding.memoryOpenButton.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), MemoryActivity::class.java))
        }
        binding.logOpenButton.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), LogActivity::class.java))
        }
        binding.advancedToggle.setOnClickListener {
            binding.advancedSection.visibility =
                if (binding.advancedSection.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        binding.portSave.setOnClickListener {
            val port = binding.portInput.text?.toString()?.toIntOrNull()
            if (port != null && port in 1024..65535) {
                app.settings.serverPort = port
                if (ServerService.running) ServerService.start(requireContext())
                Toast.makeText(requireContext(), R.string.save, Toast.LENGTH_SHORT).show()
                refreshServerStatus()
            }
        }
        binding.versionText.text = getString(
            R.string.settings_version,
            UpdateUi.installedVersionName(requireContext()),
            UpdateUi.installedVersionCode(requireContext())
        )
        binding.updateCheckButton.setOnClickListener { checkForUpdates() }
        binding.updateDownloadButton.setOnClickListener {
            pendingApkUrl?.let { UpdateUi.openDownload(requireContext(), it) }
        }
        refreshServerStatus()
    }

    /** APK URL of the newest release, set when a manual check finds an update. */
    private var pendingApkUrl: String? = null

    /** Manual update check: reports every outcome honestly (available / up to date / failed). */
    private fun checkForUpdates() {
        binding.updateCheckButton.isEnabled = false
        binding.updateDownloadButton.visibility = View.GONE
        binding.updateStatus.visibility = View.VISIBLE
        binding.updateStatus.text = getString(R.string.update_checking)
        val current = UpdateUi.installedVersionCode(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val result = UpdateChecker.check(current)
            app.settings.lastUpdateCheckMs = System.currentTimeMillis()
            if (_binding == null) return@launch
            binding.updateCheckButton.isEnabled = true
            when (result) {
                is UpdateCheckResult.UpdateAvailable -> {
                    pendingApkUrl = result.release.apkUrl
                    binding.updateStatus.text =
                        getString(R.string.update_available_msg, result.release.versionName)
                    binding.updateDownloadButton.visibility = View.VISIBLE
                }
                UpdateCheckResult.UpToDate ->
                    binding.updateStatus.text = getString(R.string.update_up_to_date)
                is UpdateCheckResult.Failed ->
                    binding.updateStatus.text = getString(R.string.update_check_failed)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshServerStatus()
    }

    private fun copyToClipboard(label: String, value: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun ensurePermissionThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startServer()
        }
    }

    private fun startServer() {
        ServerService.start(requireContext())
        refreshServerStatus()
    }

    private fun refreshServerStatus() {
        if (_binding == null) return
        val running = ServerService.running
        binding.serverSwitch.setOnCheckedChangeListener(null)
        binding.serverSwitch.isChecked = running
        binding.serverSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) ensurePermissionThenStart() else ServerService.stop(requireContext())
            refreshServerStatus()
        }
        val networkPref = app.settings.serverNetworkMode
        binding.networkModeSwitch.setOnCheckedChangeListener(null)
        binding.networkModeSwitch.isChecked = networkPref
        binding.networkModeSwitch.setOnCheckedChangeListener { _, checked ->
            app.settings.serverNetworkMode = checked
            if (ServerService.running) {
                ServerService.start(requireContext())
                Toast.makeText(requireContext(), R.string.settings_network_applied, Toast.LENGTH_SHORT).show()
            }
            refreshServerStatus()
        }
        // Effective reach: what the live server bound with, else the saved preference.
        val effectiveNetwork = if (running) ServerService.runningNetworkMode else networkPref
        binding.serverStatus.text = when {
            !running -> getString(R.string.settings_server_stopped)
            effectiveNetwork -> getString(R.string.settings_server_running_network, app.settings.serverPort)
            else -> getString(R.string.settings_server_running, app.settings.serverPort)
        }
        binding.networkModeState.text = getString(
            if (effectiveNetwork) R.string.settings_network_state_on else R.string.settings_network_state_off
        )
        refreshConnectSection(running, effectiveNetwork)
    }

    private fun refreshConnectSection(running: Boolean, networkMode: Boolean) {
        val port = app.settings.serverPort
        binding.connectState.text = getString(
            if (running) R.string.settings_connect_running else R.string.settings_connect_start_first
        )
        binding.connectLocalUrl.text = ServerNet.localBaseUrl(port)
        if (!networkMode) {
            binding.connectNetworkOffHint.visibility = View.VISIBLE
            binding.connectNoAddress.visibility = View.GONE
            binding.connectLanBlock.visibility = View.GONE
            binding.connectTsBlock.visibility = View.GONE
            return
        }
        binding.connectNetworkOffHint.visibility = View.GONE
        val addresses = ServerNet.detectAdvertised()
        val lan = addresses.firstOrNull { it.kind == ServerNet.AddressKind.LAN }
        val tailscale = addresses.firstOrNull { it.kind == ServerNet.AddressKind.TAILSCALE }
        binding.connectLanBlock.visibility = if (lan != null) View.VISIBLE else View.GONE
        if (lan != null) binding.connectLanUrl.text = ServerNet.baseUrl(lan.ip, port)
        binding.connectTsBlock.visibility = if (tailscale != null) View.VISIBLE else View.GONE
        if (tailscale != null) binding.connectTsUrl.text = ServerNet.baseUrl(tailscale.ip, port)
        binding.connectNoAddress.visibility =
            if (lan == null && tailscale == null) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
