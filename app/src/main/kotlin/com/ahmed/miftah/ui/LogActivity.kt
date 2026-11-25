package com.ahmed.miftah.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ahmed.miftah.R
import com.ahmed.miftah.databinding.ActivityLogBinding

/**
 * Hosts the request/agent audit log as its own screen, opened from Settings.
 * The log used to be a bottom-nav tab, but Material's BottomNavigationView
 * hard-caps its menu at 5 items — with 6 tabs the 6th add throws
 * IllegalArgumentException during MainActivity inflation and the app died
 * on the splash screen (v1.0.0 launch crash). The bottom bar now holds the
 * 5 primary sections; the log lives here, one tap from Settings.
 */
class LogActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.log_title)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.log_container, LogFragment())
                .commit()
        }
    }
}
