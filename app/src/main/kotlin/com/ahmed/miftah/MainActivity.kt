package com.ahmed.miftah

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.ahmed.miftah.databinding.ActivityMainBinding
import com.ahmed.miftah.ui.ChatFragment
import com.ahmed.miftah.ui.KeysFragment
import com.ahmed.miftah.ui.LogFragment
import com.ahmed.miftah.ui.SettingsFragment
import com.ahmed.miftah.ui.SkillsFragment

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (savedInstanceState == null) show(ChatFragment())
        binding.bottomNav.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_keys -> KeysFragment()
                R.id.nav_skills -> SkillsFragment()
                R.id.nav_log -> LogFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> ChatFragment()
            }
            show(fragment)
            true
        }
    }

    private fun show(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.fragment_enter, R.anim.fragment_exit,
                R.anim.fragment_pop_enter, R.anim.fragment_pop_exit
            )
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}
