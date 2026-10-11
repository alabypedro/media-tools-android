package com.alabypedro.mediatools.download

import android.content.Context
import android.util.Log
import com.alabypedro.mediatools.data.Settings
import com.alabypedro.mediatools.engine.Engine
import com.alabypedro.mediatools.engine.EngineException
import com.alabypedro.mediatools.engine.MediaInfo
import com.alabypedro.mediatools.engine.Selection
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.UUID

enum class JobStatus { QUEUED, RUNNING, DONE, FAILED, CANCELED }

data class DownloadJob(
    val id: String,
    val url: String,
    val title: String,
    val thumbnail: String?,
    /** "1080p · MP4", "MP3 · 320 kbps"... */
    val label: String,
    /** Argumentos do yt-dlp (guardados para "Tentar de novo"). */
    val args: List<String>,
    val isPlaylist: Boolean,
    val status: JobStatus = JobStatus.QUEUED,
    /** 0..1, ou -1 quando não dá para saber (juntando, convertendo...). */
    val progress: Float = -1f,
    val stage: String = "Na fila",
    val speed: String? = null,
    val error: String? = null,
    val files: List<SavedFile> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
) {
    val isActive get() = status == JobStatus.QUEUED || status == JobStatus.RUNNING
}

/**
 * Fila de downloads: N simultâneos (Ajustes), histórico salvo em JSON.
 * Os downloads rodam no escopo do processo; o [DownloadService] só mantém o
 * processo vivo em primeiro plano enquanto houver algo ativo.
 */
class DownloadRepository(
    private val context: Context,
    private val engine: Engine,
    private val settings: Settings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = File(context.filesDir, "downloads.json")
    private val canceled = Collections.synchronizedSet(mutableSetOf<String>())
    private val running = Collections.synchronizedSet(mutableSetOf<String>())

    private val _jobs = MutableStateFlow(load())
    /** Mais recentes primeiro. */
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    fun enqueue(info: MediaInfo, selection: Selection): DownloadJob {
        val job = DownloadJob(
            id = UUID.randomUUID().toString(),
            url = info.url,
            title = info.title,
            thumbnail = info.thumbnail,
            label = selection.label + if (info.isPlaylist) " · playlist (${info.playlistCount})" else "",
            args = selection.toArgs(info.isLive),
            isPlaylist = info.isPlaylist,
        )
        _jobs.update { listOf(job) + it }
        persist()
        DownloadService.start(context)
        pump()
        return job
    }

    fun cancel(id: String) {
        val job = find(id) ?: return
        when (job.status) {
            JobStatus.QUEUED -> {
                edit(id) { it.copy(status = JobStatus.CANCELED, stage = "Cancelado", finishedAt = now()) }
                persist()
            }
            JobStatus.RUNNING -> {
                canceled += id
                edit(id) { it.copy(stage = "Cancelando...") }
                engine.cancel(id)
            }
            else -> Unit
        }
    }

    fun retry(id: String) {
        val job = find(id) ?: return
        if (job.isActive) return
        edit(id) {
            it.copy(status = JobStatus.QUEUED, stage = "Na fila", progress = -1f, error = null,
                files = emptyList(), finishedAt = null)
        }
        persist()
        DownloadService.start(context)
        pump()
    }

    fun remove(id: String) {
        _jobs.update { list -> list.filterNot { it.id == id && !it.isActive } }
        persist()
    }

    fun clearFinished() {
        _jobs.update { list -> list.filter { it.isActive } }
        persist()
    }

    @Synchronized
    private fun pump() {
        val limit = settings.value.maxConcurrent.coerceIn(1, 5)
        val queued = _jobs.value.filter { it.status == JobStatus.QUEUED && it.id !in running }.reversed()
        for (job in queued) {
            if (running.size >= limit) break
            running += job.id
            edit(job.id) { it.copy(status = JobStatus.RUNNING, stage = "Iniciando", progress = -1f) }
            scope.launch { run(job) }
        }
    }

    private suspend fun run(job: DownloadJob) {
        val dir = File(context.cacheDir, "jobs/${job.id}")
        dir.deleteRecursively()
        var lastEmit = 0L
        try {
            engine.download(job.id, job.url, job.args, job.isPlaylist, dir) { progress, stage, speed ->
                // a saída do yt-dlp chega várias vezes por segundo: limita as atualizações da tela
                val t = System.currentTimeMillis()
                if (t - lastEmit < 250 && progress in 0f..0.999f) return@download
                lastEmit = t
                if (job.id !in canceled) edit(job.id) { it.copy(progress = progress, stage = stage, speed = speed) }
            }
            if (job.id in canceled) throw YoutubeDL.CanceledException()
            edit(job.id) { it.copy(progress = -1f, stage = "Salvando em Downloads", speed = null) }
            val files = MediaStoreSaver.saveAll(context, dir)
            if (files.isEmpty()) throw EngineException("Nenhum arquivo foi gerado.")
            val done = edit(job.id) {
                it.copy(status = JobStatus.DONE, stage = "Concluído", progress = 1f, files = files, finishedAt = now())
            }
            done?.let { Notifications.finished(context, it) }
        } catch (e: Exception) {
            if (job.id in canceled || e is YoutubeDL.CanceledException) {
                edit(job.id) { it.copy(status = JobStatus.CANCELED, stage = "Cancelado", speed = null, finishedAt = now()) }
            } else {
                Log.w(TAG, "download ${job.url}", e)
                val message = (e as? EngineException)?.message ?: Engine.friendlyError(e.message)
                val failed = edit(job.id) {
                    it.copy(status = JobStatus.FAILED, stage = "Falhou", error = message, speed = null, finishedAt = now())
                }
                failed?.let { Notifications.failed(context, it) }
            }
        } finally {
            dir.deleteRecursively()
            canceled -= job.id
            running -= job.id
            persist()
            pump()
        }
    }

    private fun find(id: String) = _jobs.value.firstOrNull { it.id == id }

    private fun edit(id: String, transform: (DownloadJob) -> DownloadJob): DownloadJob? {
        var result: DownloadJob? = null
        _jobs.update { list -> list.map { if (it.id == id) transform(it).also { j -> result = j } else it } }
        return result
    }

    private fun now() = System.currentTimeMillis()

    // ------------------------------------------------------------ persistência

    @Synchronized
    private fun persist() {
        val arr = JSONArray()
        _jobs.value.forEach { arr.put(it.toJson()) }
        runCatching { store.writeText(arr.toString()) }.onFailure { Log.w(TAG, "persist", it) }
    }

    private fun load(): List<DownloadJob> = runCatching {
        if (!store.exists()) return emptyList()
        val arr = JSONArray(store.readText())
        (0 until arr.length()).map { arr.getJSONObject(it).toJob() }.map {
            // o processo morreu no meio: não dá para retomar o yt-dlp, só tentar de novo
            if (it.isActive) it.copy(status = JobStatus.FAILED, stage = "Falhou",
                error = "Interrompido (o app foi fechado).", finishedAt = now()) else it
        }
    }.getOrElse { Log.w(TAG, "load", it); emptyList() }

    private fun DownloadJob.toJson() = JSONObject().apply {
        put("id", id); put("url", url); put("title", title); put("thumbnail", thumbnail)
        put("label", label); put("args", JSONArray(args)); put("isPlaylist", isPlaylist)
        put("status", status.name); put("stage", stage); put("error", error)
        put("createdAt", createdAt); put("finishedAt", finishedAt)
        put("files", JSONArray().apply {
            files.forEach { put(JSONObject().put("uri", it.uri).put("name", it.name).put("mime", it.mime).put("size", it.size)) }
        })
    }

    private fun JSONObject.toJob(): DownloadJob {
        val args = getJSONArray("args")
        val files = optJSONArray("files") ?: JSONArray()
        return DownloadJob(
            id = getString("id"), url = getString("url"), title = getString("title"),
            thumbnail = optStr("thumbnail"), label = getString("label"),
            args = (0 until args.length()).map { args.getString(it) },
            isPlaylist = optBoolean("isPlaylist"),
            status = JobStatus.valueOf(getString("status")),
            progress = if (getString("status") == JobStatus.DONE.name) 1f else -1f,
            stage = optString("stage"), error = optStr("error"),
            files = (0 until files.length()).map { i ->
                files.getJSONObject(i).let { SavedFile(it.getString("uri"), it.getString("name"), it.getString("mime"), it.getLong("size")) }
            },
            createdAt = getLong("createdAt"),
            finishedAt = if (isNull("finishedAt")) null else getLong("finishedAt"),
        )
    }

    private fun JSONObject.optStr(key: String) = if (isNull(key)) null else getString(key)

    companion object {
        private const val TAG = "Downloads"
    }
}
