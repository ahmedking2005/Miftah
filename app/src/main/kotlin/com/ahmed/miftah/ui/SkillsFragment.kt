package com.ahmed.miftah.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ahmed.miftah.MiftahApp
import com.ahmed.miftah.R
import com.ahmed.miftah.databinding.FragmentSkillsBinding
import com.ahmed.miftah.databinding.ItemSkillBinding
import com.ahmed.miftah.skills.Skill
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File

class SkillsFragment : Fragment() {
    private var _binding: FragmentSkillsBinding? = null
    private val binding get() = _binding!!
    private val app: MiftahApp get() = requireActivity().application as MiftahApp

    private inner class SkillsAdapter : RecyclerView.Adapter<SkillsAdapter.Holder>() {
        var items: List<Skill> = emptyList()
        inner class Holder(val b: ItemSkillBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemSkillBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val s = items[position]
            holder.b.skillName.text = s.name
            holder.b.skillDesc.text = s.description +
                "\n" + getString(if (s.enabled) R.string.skills_enabled else R.string.skills_disabled) +
                " · " + s.source
            holder.b.skillSwitch.setOnCheckedChangeListener(null)
            holder.b.skillSwitch.isChecked = s.enabled
            holder.b.skillSwitch.setOnCheckedChangeListener { _, checked ->
                if (checked && !s.reviewed) {
                    // Mandatory review before first enable.
                    holder.b.skillSwitch.isChecked = false
                    openReview(s)
                } else {
                    app.skillStore.setEnabled(s.id, checked)
                    refresh()
                }
            }
            holder.b.skillReview.setOnClickListener { openReview(s) }
            holder.b.skillDelete.setOnClickListener {
                app.skillStore.delete(s.id)
                refresh()
            }
        }
    }

    private val adapter = SkillsAdapter()

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFromUri(uri)
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) importFromTree(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSkillsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.skillsList.layoutManager = LinearLayoutManager(requireContext())
        binding.skillsList.adapter = adapter
        binding.importFileButton.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setItems(arrayOf("ZIP", "Folder")) { _, which ->
                    if (which == 0) pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    else pickFolder.launch(null)
                }
                .show()
        }
        binding.importGithubButton.setOnClickListener { promptGitHub() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (_binding == null) return
        adapter.items = app.skillStore.all()
        adapter.notifyDataSetChanged()
        binding.skillsEmpty.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openReview(skill: Skill) {
        startActivity(
            Intent(requireContext(), SkillReviewActivity::class.java)
                .putExtra(SkillReviewActivity.EXTRA_SKILL, skill.id)
        )
    }

    private fun importFromUri(uri: Uri) {
        setStatus(getString(R.string.skills_importing))
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val imported = requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    app.skillStore.importZip(input, uri.lastPathSegment ?: "file")
                } ?: emptyList()
                onImported(imported.size)
            } catch (e: Exception) {
                setStatus(getString(R.string.skills_import_failed, e.message ?: ""))
            }
        }
    }

    private fun importFromTree(uri: Uri) {
        setStatus(getString(R.string.skills_importing))
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val temp = File(requireContext().cacheDir, "skill_import_" + System.currentTimeMillis())
                temp.mkdirs()
                copyTree(uri, temp)
                val imported = app.skillStore.importDirectory(temp, "folder")
                temp.deleteRecursively()
                onImported(imported.size)
            } catch (e: Exception) {
                setStatus(getString(R.string.skills_import_failed, e.message ?: ""))
            }
        }
    }

    private fun copyTree(treeUri: Uri, destDir: File) {
        val treeId = androidx.documentfile.provider.DocumentFile.fromTreeUri(requireContext(), treeUri) ?: return
        copyDocument(treeId, destDir)
    }

    private fun copyDocument(doc: androidx.documentfile.provider.DocumentFile, destDir: File) {
        if (doc.isDirectory) {
            val sub = if (destDir.name == doc.name) destDir else File(destDir, doc.name ?: "dir")
            sub.mkdirs()
            doc.listFiles().forEach { copyDocument(it, sub) }
        } else {
            val out = File(destDir, doc.name ?: "file")
            requireContext().contentResolver.openInputStream(doc.uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
    }

    private fun promptGitHub() {
        val input = EditText(requireContext()).apply { hint = getString(R.string.skills_github_hint) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.skills_import_github)
            .setView(input)
            .setPositiveButton(R.string.skills_import) { _, _ ->
                val url = input.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                setStatus(getString(R.string.skills_importing))
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val imported = app.skillStore.importFromGitHub(url)
                        onImported(imported.size)
                    } catch (e: Exception) {
                        setStatus(getString(R.string.skills_import_failed, e.message ?: ""))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onImported(count: Int) {
        setStatus(getString(R.string.skills_imported, count))
        Toast.makeText(requireContext(), getString(R.string.skills_imported, count), Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun setStatus(text: String) {
        if (_binding != null) {
            binding.skillsStatus.text = text
            binding.skillsProgress.visibility =
                if (text == getString(R.string.skills_importing)) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
