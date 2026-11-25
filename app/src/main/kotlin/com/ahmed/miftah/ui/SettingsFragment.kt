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
import com.ahmed.miftah.MiftahApp
import com.ahmed.miftah.R
import com.ahmed.miftah.databinding.FragmentSettingsBinding
import com.ahmed.miftah.server.ServerService

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
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Miftah unified key", app.settings.unifiedKey(app.vault)))
            Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT).show()
        }
        binding.regenKeyButton.setOnClickListener {
            binding.unifiedKeyText.text = app.settings.regenerateUnifiedKey(app.vault)
        }
        binding.advancedToggle.setOnClickListener {
            binding.advancedSection.visibility =
                if (binding.advancedSection.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        binding.portSave.setOnClickListener {
            val port = binding.portInput.text?.toString()?.toIntOrNull()
            if (port != null && port in 1024..65535) {
                app.settings.serverPort = port
                Toast.makeText(requireContext(), R.string.save, Toast.LENGTH_SHORT).show()
                refreshServerStatus()
            }
        }
        refreshServerStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshServerStatus()
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
        binding.serverStatus.text = if (running) {
            getString(R.string.settings_server_running, app.settings.serverPort)
        } else {
            getString(R.string.settings_server_stopped)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
