package com.ahmed.miftah.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ahmed.miftah.MiftahApp
import com.ahmed.miftah.R
import com.ahmed.miftah.databinding.ActivitySkillReviewBinding
import com.ahmed.miftah.skills.SkillParser

/**
 * Mandatory review screen: opening it marks the skill reviewed; only then can
 * it be enabled. Shows source, full SKILL.md content, bundled files, and —
 * separately, with a warning — the bundled executable scripts (Stage 3:
 * enabling lets the agent propose running them; every run still needs the
 * user's approval, and scripts may use the network).
 */
class SkillReviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySkillReviewBinding
    private val app: MiftahApp get() = application as MiftahApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val skillId = intent.getStringExtra(EXTRA_SKILL)
        val skill = skillId?.let { app.skillStore.byId(it) } ?: run { finish(); return }
        binding = ActivitySkillReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.skills_review_title)

        app.skillStore.markReviewed(skill.id)

        binding.reviewName.text = skill.name
        binding.reviewSource.text = getString(R.string.skills_source, skill.source)
        binding.reviewDesc.text = skill.description
        val files = app.skillStore.filesOf(skill)
        binding.reviewFiles.text = files.filterNot { SkillParser.isScriptFile(it) }.joinToString("\n")
        // Scripts get their own warning card: enabling lets the agent propose
        // running them (each run still needs approval; scripts may use the
        // network). No scripts -> no card.
        val scripts = app.skillStore.allScriptFilesOf(skill)
        if (scripts.isEmpty()) {
            binding.reviewScriptsCard.visibility = android.view.View.GONE
        } else {
            binding.reviewScriptsCard.visibility = android.view.View.VISIBLE
            binding.reviewScriptsList.text = scripts.joinToString("\n")
        }
        binding.reviewBody.text = app.skillStore.fullTextOf(skill)
        binding.reviewEnableSwitch.setOnCheckedChangeListener(null)
        binding.reviewEnableSwitch.isChecked = skill.enabled
        binding.reviewEnableSwitch.setOnCheckedChangeListener { _, checked ->
            app.skillStore.setEnabled(skill.id, checked)
        }
    }

    companion object {
        const val EXTRA_SKILL = "skill_id"
    }
}
