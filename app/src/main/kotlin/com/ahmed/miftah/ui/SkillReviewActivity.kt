package com.ahmed.miftah.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ahmed.miftah.MiftahApp
import com.ahmed.miftah.R
import com.ahmed.miftah.databinding.ActivitySkillReviewBinding
import com.ahmed.miftah.skills.SkillParser

/**
 * Mandatory review screen: opening it marks the skill reviewed; only then can
 * it be enabled. Shows source, full SKILL.md content, bundled files, and the
 * honest note that bundled scripts are not executable in this version.
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
        binding.reviewFiles.text = files.joinToString("\n") { path ->
            if (SkillParser.isScriptFile(path)) "$path  ⚠ script — not executable in this version" else path
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
