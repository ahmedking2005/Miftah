package com.ahmed.miftah

import android.app.Application
import android.content.Context
import com.ahmed.miftah.core.AppSettings
import com.ahmed.miftah.core.DispatchingEngine
import com.ahmed.miftah.core.MemoryStore
import com.ahmed.miftah.core.SessionStore
import com.ahmed.miftah.core.KeySource
import com.ahmed.miftah.core.PrefsStringStore
import com.ahmed.miftah.core.RateTracker
import com.ahmed.miftah.core.RequestLog
import com.ahmed.miftah.core.Router
import com.ahmed.miftah.core.StoredKey
import com.ahmed.miftah.core.Vault
import com.ahmed.miftah.skills.SkillStore

class MiftahApp : Application() {
    lateinit var vault: Vault
    lateinit var settings: AppSettings
    lateinit var requestLog: RequestLog
    lateinit var rateTracker: RateTracker
    lateinit var router: Router
    lateinit var skillStore: SkillStore
    lateinit var sessionStore: SessionStore
    lateinit var memoryStore: MemoryStore

    override fun onCreate() {
        super.onCreate()
        vault = Vault(this)
        settings = AppSettings(this)
        requestLog = RequestLog(this)
        rateTracker = RateTracker(
            PrefsStringStore(getSharedPreferences("miftah_rates", Context.MODE_PRIVATE))
        )
        val keySource = object : KeySource {
            override fun keysFor(providerId: String): List<StoredKey> = vault.keysFor(providerId)
        }
        router = Router(
            engine = DispatchingEngine(),
            keySource = keySource,
            tracker = rateTracker,
            chainProvider = { settings.chain },
            log = { requestLog.add(it) }
        )
        skillStore = SkillStore(this)
        sessionStore = SessionStore(
            PrefsStringStore(getSharedPreferences("miftah_sessions", Context.MODE_PRIVATE))
        )
        memoryStore = MemoryStore(
            PrefsStringStore(getSharedPreferences("miftah_memory", Context.MODE_PRIVATE))
        )
    }
}
