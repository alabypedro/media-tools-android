package com.alabypedro.mediatools.data

import android.content.Context
import androidx.core.content.edit
import com.alabypedro.mediatools.engine.Mode
import com.alabypedro.mediatools.engine.Selection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    /** Última escolha na tela Início, usada como padrão da próxima análise. */
    val defaultSelection: Selection = Selection(),
    val maxConcurrent: Int = 2,
    val autoUpdateYtDlp: Boolean = true,
    val lastUpdateCheck: Long = 0,
    /** Procurar versão nova do app ao abrir (GitHub Releases). */
    val checkAppUpdates: Boolean = true,
)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val value get() = _state.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        _state.value = next
        prefs.edit {
            val s = next.defaultSelection
            putString("mode", s.mode.name)
            putString("quality", s.quality)
            putString("container", s.container)
            putString("audioFormat", s.audioFormat)
            putString("audioBitrate", s.audioBitrate)
            putBoolean("embedMetadata", s.embedMetadata)
            putInt("maxConcurrent", next.maxConcurrent)
            putBoolean("autoUpdateYtDlp", next.autoUpdateYtDlp)
            putLong("lastUpdateCheck", next.lastUpdateCheck)
            putBoolean("checkAppUpdates", next.checkAppUpdates)
        }
    }

    private fun load(): AppSettings {
        val d = Selection()
        return AppSettings(
            defaultSelection = Selection(
                mode = runCatching { Mode.valueOf(prefs.getString("mode", null)!!) }.getOrDefault(d.mode),
                quality = prefs.getString("quality", d.quality)!!,
                container = prefs.getString("container", d.container)!!,
                audioFormat = prefs.getString("audioFormat", d.audioFormat)!!,
                audioBitrate = prefs.getString("audioBitrate", d.audioBitrate)!!,
                embedMetadata = prefs.getBoolean("embedMetadata", d.embedMetadata),
            ),
            maxConcurrent = prefs.getInt("maxConcurrent", 2),
            autoUpdateYtDlp = prefs.getBoolean("autoUpdateYtDlp", true),
            lastUpdateCheck = prefs.getLong("lastUpdateCheck", 0),
            checkAppUpdates = prefs.getBoolean("checkAppUpdates", true),
        )
    }
}
