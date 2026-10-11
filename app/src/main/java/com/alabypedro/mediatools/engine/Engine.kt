package com.alabypedro.mediatools.engine

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

sealed interface EngineState {
    data object Starting : EngineState
    data object Updating : EngineState
    data object Ready : EngineState
    data class Failed(val message: String) : EngineState
}

/** Erro já traduzido para o usuário. */
class EngineException(message: String) : Exception(message)

/**
 * Embrulha a youtubedl-android: Python + yt-dlp + FFmpeg empacotados no APK,
 * executados como processos filhos (o mesmo modelo do worker do app desktop).
 */
class Engine(private val context: Context) {
    private val _state = MutableStateFlow<EngineState>(EngineState.Starting)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    val version: String? get() = YoutubeDL.versionName(context) ?: YoutubeDL.version(context)

    /** Extrai Python/FFmpeg na primeira execução e, se for a hora, atualiza o yt-dlp. */
    suspend fun start(autoUpdate: Boolean, lastUpdateCheck: Long, onUpdateChecked: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            try {
                YoutubeDL.init(context)
                FFmpeg.init(context)
            } catch (e: Exception) {
                Log.e(TAG, "init", e)
                _state.value = EngineState.Failed("Não foi possível preparar o yt-dlp: ${e.message}")
                return@withContext
            }
            // As plataformas mudam o tempo todo: um yt-dlp desatualizado é a causa nº 1 de falhas.
            val now = System.currentTimeMillis()
            if (autoUpdate && now - lastUpdateCheck > UPDATE_INTERVAL_MS) {
                _state.value = EngineState.Updating
                runCatching { YoutubeDL.updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE) }
                    .onSuccess { onUpdateChecked(now) }
                    .onFailure { Log.w(TAG, "auto-update", it) }
            }
            _state.value = EngineState.Ready
        }

    /** Atualização manual (Ajustes). Devolve true se baixou uma versão nova. */
    suspend fun update(): Boolean = withContext(Dispatchers.IO) {
        val previous = _state.value
        _state.value = EngineState.Updating
        try {
            YoutubeDL.updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE) == YoutubeDL.UpdateStatus.DONE
        } catch (e: Exception) {
            throw EngineException("Falha ao atualizar o yt-dlp: ${e.message ?: "sem conexão?"}")
        } finally {
            _state.value = if (previous is EngineState.Failed) previous else EngineState.Ready
        }
    }

    suspend fun analyze(url: String): MediaInfo = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--flat-playlist")
            // link de vídeo dentro de playlist (watch?v=...&list=...): analisa só o vídeo
            addOption("--no-playlist")
            addOption("--no-warnings")
        }
        val response = try {
            YoutubeDL.execute(request, null, null)
        } catch (e: YoutubeDL.CanceledException) {
            throw EngineException("Análise cancelada.")
        } catch (e: Exception) {
            throw EngineException(friendlyError(e.message))
        }
        try {
            MediaInfo.parse(response.out, url)
        } catch (e: Exception) {
            throw EngineException("Resposta inesperada do yt-dlp ao analisar o link.")
        }
    }

    /**
     * Baixa para [outputDir]. [onProgress] recebe progresso 0..1 (ou -1 se
     * desconhecido), a etapa atual e a velocidade.
     */
    suspend fun download(
        jobId: String,
        url: String,
        args: List<String>,
        isPlaylist: Boolean,
        outputDir: File,
        onProgress: (progress: Float, stage: String, speed: String?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        val template = if (isPlaylist) {
            "%(playlist_title,playlist_id)s/%(playlist_index)03d - %(title).100B.%(ext)s"
        } else {
            "%(title).120B [%(id)s].%(ext)s"
        }
        val request = YoutubeDLRequest(url).apply {
            args.chunkedOptions().forEach { (opt, value) ->
                if (value == null) addOption(opt) else addOption(opt, value)
            }
            addOption("-P", outputDir.absolutePath)
            addOption("-o", template)
            addOption(if (isPlaylist) "--yes-playlist" else "--no-playlist")
            if (isPlaylist) addOption("--ignore-errors")
            addOption("--newline")
            // o armazenamento compartilhado do Android recusa os mesmos caracteres que o Windows (: ? " | ...)
            addOption("--windows-filenames")
            addOption("--no-mtime") // o arquivo aparece como "novo" na galeria
            addOption("--no-warnings")
        }
        var stage = "Baixando"
        try {
            YoutubeDL.execute(request, jobId) { progress, _, line ->
                stage = stageFor(line) ?: stage
                val speed = SPEED.find(line)?.groupValues?.get(1)
                val p = if (stage.startsWith("Baixando") && progress >= 0) progress / 100f else -1f
                onProgress(p, stage, speed)
            }
        } catch (e: YoutubeDL.CanceledException) {
            throw e
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            // Playlist com --ignore-errors: alguns itens podem ter falhado e outros não.
            if (isPlaylist && outputDir.walkTopDown().any { it.isFile && !it.isTemporary() }) return@withContext
            throw EngineException(friendlyError(e.message))
        }
    }

    fun cancel(jobId: String) {
        YoutubeDL.destroyProcessById(jobId)
    }

    companion object {
        private const val TAG = "Engine"
        private const val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000
        private val SPEED = Regex("""at\s+~?\s*([\d.]+\s*[KMG]i?B/s)""")
        private val PLAYLIST_ITEM = Regex("""Downloading item (\d+) of (\d+)""")

        fun File.isTemporary() = name.endsWith(".part") || name.endsWith(".ytdl") ||
            name.contains(".part-Frag") || name.endsWith(".temp")

        /** Pares (opção, valor) a partir da lista plana gerada por [Selection.toArgs]. */
        private fun List<String>.chunkedOptions(): List<Pair<String, String?>> {
            val out = mutableListOf<Pair<String, String?>>()
            var i = 0
            while (i < size) {
                val opt = this[i]
                val next = getOrNull(i + 1)
                if (next != null && !next.startsWith("-")) {
                    out += opt to next; i += 2
                } else {
                    out += opt to null; i += 1
                }
            }
            return out
        }

        fun stageFor(line: String): String? {
            PLAYLIST_ITEM.find(line)?.let { return "Baixando item ${it.groupValues[1]} de ${it.groupValues[2]}" }
            return when {
                line.startsWith("[download]") -> null // mantém "Baixando" / "Baixando item X de Y"
                line.startsWith("[Merger]") -> "Juntando áudio e vídeo"
                line.startsWith("[ExtractAudio]") -> "Convertendo áudio"
                line.startsWith("[VideoRemuxer]") -> "Trocando contêiner"
                line.startsWith("[VideoConvertor]") -> "Convertendo vídeo"
                line.startsWith("[Metadata]") -> "Gravando metadados"
                else -> null
            }
        }

        /** Mensagens claras para os erros mais comuns (como core/exceptions.py no desktop). */
        fun friendlyError(raw: String?): String {
            val text = raw.orEmpty()
            val lower = text.lowercase()
            return when {
                "unsupported url" in lower -> "Esse site ou link não é suportado."
                "private video" in lower || "this video is private" in lower -> "O conteúdo é privado."
                "sign in to confirm your age" in lower || "age-restricted" in lower ->
                    "Conteúdo com restrição de idade: a plataforma exige login."
                "sign in to confirm" in lower || "login required" in lower || "log in" in lower ||
                    "requires authentication" in lower || "cookies" in lower ->
                    "A plataforma exigiu login para esse conteúdo."
                "drm" in lower -> "O conteúdo é protegido por DRM e não pode ser baixado."
                "not available in your country" in lower || "geo" in lower && "restrict" in lower ->
                    "O conteúdo não está disponível na sua região."
                "members-only" in lower || "join this channel" in lower || "premium" in lower ->
                    "Conteúdo pago ou exclusivo para membros."
                "http error 404" in lower || "not found" in lower || "video unavailable" in lower ->
                    "Conteúdo não encontrado ou removido."
                "http error 429" in lower || "too many requests" in lower ->
                    "A plataforma limitou as requisições. Tente de novo mais tarde."
                "unable to download webpage" in lower || "failed to resolve" in lower ||
                    "network is unreachable" in lower || "timed out" in lower ->
                    "Falha de conexão. Verifique a internet."
                "no space left" in lower -> "Sem espaço no celular."
                else -> text.lineSequence().lastOrNull { it.startsWith("ERROR:") }
                    ?.removePrefix("ERROR:")?.trim()
                    ?: text.lineSequence().lastOrNull { it.isNotBlank() }?.trim()
                    ?: "Erro desconhecido."
            }
        }
    }
}
