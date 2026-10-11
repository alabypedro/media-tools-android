package com.alabypedro.mediatools

import android.app.Application
import com.alabypedro.mediatools.data.Settings
import com.alabypedro.mediatools.download.DownloadRepository
import com.alabypedro.mediatools.download.Notifications
import com.alabypedro.mediatools.engine.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class UmtApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settings: Settings private set
    lateinit var engine: Engine private set
    lateinit var downloads: DownloadRepository private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        engine = Engine(this)
        downloads = DownloadRepository(this, engine, settings)
        Notifications.createChannels(this)
        appScope.launch {
            val s = settings.value
            engine.start(s.autoUpdateYtDlp, s.lastUpdateCheck) { checkedAt ->
                settings.update { it.copy(lastUpdateCheck = checkedAt) }
            }
        }
    }
}
