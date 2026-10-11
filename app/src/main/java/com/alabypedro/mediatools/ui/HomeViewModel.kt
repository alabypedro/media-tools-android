package com.alabypedro.mediatools.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alabypedro.mediatools.UmtApp
import com.alabypedro.mediatools.engine.EngineState
import com.alabypedro.mediatools.engine.MediaInfo
import com.alabypedro.mediatools.engine.Mode
import com.alabypedro.mediatools.engine.Selection
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    val url: String = "",
    val analyzing: Boolean = false,
    val info: MediaInfo? = null,
    val error: String? = null,
    val selection: Selection = Selection(),
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val umt = app as UmtApp
    private val _state = MutableStateFlow(HomeState(selection = umt.settings.value.defaultSelection))
    val state = _state.asStateFlow()
    val engineState = umt.engine.state
    private var analysis: Job? = null

    fun setUrl(url: String) = _state.update { it.copy(url = url, error = null) }

    fun analyze(url: String = _state.value.url) {
        val clean = extractUrl(url) ?: url.trim()
        if (clean.isBlank()) return
        analysis?.cancel()
        _state.update { it.copy(url = clean, analyzing = true, info = null, error = null) }
        analysis = viewModelScope.launch {
            // na primeira abertura o Python ainda pode estar sendo extraído / o yt-dlp atualizando
            val engine = umt.engine.state.first { it is EngineState.Ready || it is EngineState.Failed }
            if (engine is EngineState.Failed) {
                _state.update { it.copy(analyzing = false, error = engine.message) }
                return@launch
            }
            runCatching { umt.engine.analyze(clean) }
                .onSuccess { info ->
                    _state.update { it.copy(analyzing = false, info = info, selection = fit(it.selection, info)) }
                }
                .onFailure { e ->
                    _state.update { it.copy(analyzing = false, error = e.message ?: "Falha ao analisar o link.") }
                }
        }
    }

    fun select(transform: (Selection) -> Selection) = _state.update { it.copy(selection = transform(it.selection)) }

    /** Põe na fila e guarda a escolha como padrão. Devolve false se não havia o que baixar. */
    fun download(): Boolean {
        val s = _state.value
        val info = s.info ?: return false
        umt.downloads.enqueue(info, s.selection)
        umt.settings.update { it.copy(defaultSelection = s.selection) }
        _state.update { HomeState(selection = s.selection) }
        return true
    }

    fun clear() {
        analysis?.cancel()
        _state.update { HomeState(selection = it.selection) }
    }

    /** Ajusta a escolha padrão ao que esta mídia oferece de verdade. */
    private fun fit(selection: Selection, info: MediaInfo): Selection {
        var s = selection
        if (!info.hasVideo) s = s.copy(mode = Mode.AUDIO)
        if (!info.hasAudio && info.hasVideo) s = s.copy(mode = Mode.VIDEO)
        val heights = info.videoHeights
        val wanted = s.quality.toIntOrNull()
        if (wanted != null && heights.isNotEmpty() && wanted !in heights) {
            s = s.copy(quality = heights.firstOrNull { it <= wanted }?.toString() ?: "best")
        }
        if (s.audioFormat == "flac" && !info.hasLosslessAudio) s = s.copy(audioFormat = "mp3")
        return s
    }
}
