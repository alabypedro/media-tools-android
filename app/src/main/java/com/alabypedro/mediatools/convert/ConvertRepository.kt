package com.alabypedro.mediatools.convert

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import com.alabypedro.mediatools.download.DownloadService
import com.alabypedro.mediatools.download.Notifications
import com.alabypedro.mediatools.download.SavedFile
import com.alabypedro.mediatools.engine.Engine
import com.alabypedro.mediatools.engine.EngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class ConvStatus(val label: String) {
    WAITING("Aguardando"), RUNNING("Convertendo"), DONE("Concluído"), FAILED("Erro"), CANCELED("Cancelado"), SKIPPED("Pulado")
}

data class ConvertEntry(
    val id: String = UUID.randomUUID().toString(),
    val uri: String,
    val name: String,
    val size: Long,
    /** Subpasta de origem (ao adicionar uma pasta), recriada no destino se "manter subpastas". */
    val subfolder: String = "",
    val status: ConvStatus = ConvStatus.WAITING,
    val progress: Float = -1f,
    val target: String? = null,
    val message: String? = null,
    val outputs: List<SavedFile> = emptyList(),
) {
    val ext get() = Formats.extensionOf(name)
}

data class HistoryItem(val time: Long, val source: String, val target: String, val ok: Boolean, val message: String, val outputs: List<String>)

data class ConverterState(
    val entries: List<ConvertEntry> = emptyList(),
    val running: Boolean = false,
    val pausing: Boolean = false,
)

data class ConverterSettings(
    val options: ConvertOptions = ConvertOptions(),
    /** Pasta escolhida (SAF); null = Download/Universal Media Tools/Convertidos. */
    val treeUri: String? = null,
    val treeLabel: String? = null,
    val lastTarget: String? = null,
)

/**
 * Fila do conversor (desktop: convert/core/queue.py): um arquivo por vez,
 * Pausar (termina o atual e para), Cancelar (interrompe o atual), histórico.
 */
class ConvertRepository(private val context: Context, private val engine: Engine) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("converter", Context.MODE_PRIVATE)
    private val historyFile = File(context.filesDir, "conversions.json")
    private val sink = OutputSink(context)
    private val ffmpeg = FFmpegRunner(context)
    private var worker: Job? = null
    @Volatile private var cancelRequested = false
    @Volatile private var pauseRequested = false

    private val _state = MutableStateFlow(ConverterState())
    val state: StateFlow<ConverterState> = _state.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<ConverterSettings> = _settings.asStateFlow()

    private val _history = MutableStateFlow(loadHistory())
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()

    // ------------------------------------------------------------ fila

    /** Arquivos escolhidos/compartilhados. Devolve os que não têm conversão (com o motivo). */
    fun addUris(uris: List<Uri>): List<String> {
        val rejected = mutableListOf<String>()
        val added = uris.mapNotNull { uri ->
            val (name, size) = queryNameSize(uri)
            if (Formats.targetsFor(Formats.extensionOf(name)).isEmpty()) {
                rejected += "$name: ${Formats.unsupportedReason(Formats.extensionOf(name))}"; null
            } else ConvertEntry(uri = uri.toString(), name = name, size = size)
        }
        appendEntries(added)
        return rejected
    }

    /** "Adicionar pasta": todos os arquivos convertíveis, com as subpastas. */
    fun addTree(tree: Uri): Int {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return 0
        val found = mutableListOf<ConvertEntry>()
        fun walk(dir: DocumentFile, rel: String) {
            for (f in dir.listFiles()) {
                val name = f.name ?: continue
                if (f.isDirectory) walk(f, if (rel.isEmpty()) name else "$rel/$name")
                else if (Formats.targetsFor(Formats.extensionOf(name)).isNotEmpty()) {
                    found += ConvertEntry(uri = f.uri.toString(), name = name, size = f.length(), subfolder = rel)
                }
            }
        }
        walk(root, "")
        appendEntries(found.sortedBy { "${it.subfolder}/${it.name}".lowercase() })
        return found.size
    }

    private fun appendEntries(list: List<ConvertEntry>) {
        if (list.isEmpty()) return
        val known = _state.value.entries.filter { it.status == ConvStatus.WAITING }.map { it.uri }.toSet()
        _state.update { s -> s.copy(entries = s.entries + list.filter { it.uri !in known }) }
    }

    fun remove(ids: Set<String>) = _state.update { s ->
        s.copy(entries = s.entries.filterNot { it.id in ids && it.status != ConvStatus.RUNNING })
    }

    fun clear() = _state.update { s -> s.copy(entries = s.entries.filter { it.status == ConvStatus.RUNNING }) }

    fun clearFinished() = _state.update { s ->
        s.copy(entries = s.entries.filter { it.status == ConvStatus.WAITING || it.status == ConvStatus.RUNNING })
    }

    /** Destinos em comum aos arquivos que ainda vão ser convertidos. */
    fun availableTargets(): List<String> {
        val pending = _state.value.entries.filter { it.status == ConvStatus.WAITING }
            .ifEmpty { _state.value.entries }
        return Formats.commonTargets(pending.map { it.ext }.toSet())
    }

    // ------------------------------------------------------------ execução

    /** Converte os itens aguardando para [target]. */
    fun start(target: String?) {
        if (target != null) updateSettings { it.copy(lastTarget = target) }
        _state.update { s ->
            s.copy(entries = s.entries.map { e ->
                if (e.status == ConvStatus.WAITING) e.copy(target = target) else e
            })
        }
        if (worker?.isActive == true) return
        cancelRequested = false
        pauseRequested = false
        _state.update { it.copy(running = true, pausing = false) }
        DownloadService.start(context)
        worker = scope.launch {
            var done = 0
            var failed = 0
            try {
                while (!cancelRequested && !pauseRequested) {
                    val next = _state.value.entries.firstOrNull { it.status == ConvStatus.WAITING && it.target != null } ?: break
                    when (process(next)) {
                        ConvStatus.DONE -> done++
                        ConvStatus.FAILED -> failed++
                        else -> Unit
                    }
                }
            } finally {
                _state.update { it.copy(running = false, pausing = false) }
                if (done + failed > 0 && !cancelRequested) Notifications.conversionsFinished(context, done, failed)
            }
        }
    }

    /** Termina o arquivo atual e para (não dá para pausar um FFmpeg no meio). */
    fun pause() {
        pauseRequested = true
        _state.update { it.copy(pausing = true) }
    }

    fun cancel() {
        cancelRequested = true
        ffmpeg.cancel()
    }

    private suspend fun process(entry: ConvertEntry): ConvStatus {
        val target = entry.target ?: return ConvStatus.WAITING
        edit(entry.id) { it.copy(status = ConvStatus.RUNNING, progress = -1f, message = "Preparando...") }
        val work = File(context.cacheDir, "convert/${entry.id}").apply { deleteRecursively(); mkdirs() }
        val options = _settings.value.options
        val tree = _settings.value.treeUri
        val sub = if (options.preserveFolders) entry.subfolder else ""
        var status: ConvStatus
        var message: String
        var outputs = emptyList<SavedFile>()
        try {
            val produced = runOne(entry, target, options, work)
            edit(entry.id) { it.copy(progress = -1f, message = "Salvando...") }
            outputs = produced.mapNotNull { sink.save(it, tree, sub, options.conflict) }
            if (outputs.isEmpty()) {
                status = ConvStatus.SKIPPED; message = "Já existia no destino (pulado)."
            } else {
                status = ConvStatus.DONE
                message = if (outputs.size == 1) outputs[0].name else "${outputs.size} arquivos"
            }
        } catch (e: ConversionCanceled) {
            status = ConvStatus.CANCELED; message = "Cancelado."
        } catch (e: ConversionException) {
            status = ConvStatus.FAILED; message = e.message ?: "Falhou."
        } catch (e: OutOfMemoryError) {
            status = ConvStatus.FAILED; message = "Arquivo grande demais para a memória do aparelho."
        } catch (e: Exception) {
            Log.w(TAG, "convert ${entry.name}", e)
            status = if (cancelRequested) ConvStatus.CANCELED else ConvStatus.FAILED
            message = if (cancelRequested) "Cancelado." else "Falha inesperada: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            work.deleteRecursively()
        }
        if (cancelRequested && status != ConvStatus.DONE) status = ConvStatus.CANCELED
        edit(entry.id) { it.copy(status = status, progress = if (status == ConvStatus.DONE) 1f else -1f, message = message, outputs = outputs) }
        if (status != ConvStatus.CANCELED) {
            addHistory(HistoryItem(System.currentTimeMillis(), entry.name, Formats.targetLabel(target),
                status == ConvStatus.DONE || status == ConvStatus.SKIPPED, message, outputs.map { it.name }))
        }
        return status
    }

    private suspend fun runOne(entry: ConvertEntry, target: String, options: ConvertOptions, work: File): List<File> {
        val handler = Formats.resolve(entry.ext, target)
            ?: throw ConversionException("Não há como converter .${entry.ext} para ${Formats.targetLabel(target)}.")
        if (handler in Converters.NEEDS_FFMPEG) awaitFfmpeg()
        val input = copyIn(Uri.parse(entry.uri), entry.name, entry.size, work)
        edit(entry.id) { it.copy(message = null) }
        return Converters.run(context, ffmpeg, input, target, options, work, { cancelRequested }) { p ->
            edit(entry.id) { it.copy(progress = p, message = null) }
        }
    }

    /** O FFmpeg é extraído junto com o yt-dlp na abertura do app. */
    private suspend fun awaitFfmpeg() {
        val s = engine.state.first { it !is EngineState.Starting }
        if (s is EngineState.Failed) throw ConversionException("O FFmpeg não pôde ser preparado: ${s.message}")
    }

    /** Copia a origem para a pasta de trabalho (o FFmpeg e os leitores precisam de um arquivo de verdade). */
    private fun copyIn(uri: Uri, name: String, size: Long, dir: File): File {
        if (size > 0 && context.cacheDir.usableSpace < size * 2 + 50_000_000) {
            throw ConversionException("Sem espaço no aparelho para converter \"$name\".")
        }
        val safeName = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
        val file = File(dir, safeName)
        try {
            context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        } catch (e: Exception) {
            throw ConversionException("Não foi possível ler \"$name\" (o arquivo foi movido ou apagado?).", e)
        }
        return file
    }

    private fun queryNameSize(uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }.onFailure { Log.w(TAG, "query $uri", it) }
        var finalName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "arquivo"
        // alguns apps compartilham sem nome (ou sem extensão): deduz pelo tipo MIME
        if (Formats.extensionOf(finalName).let { it.isEmpty() || Formats.categoryOf(it) == null && it !in Formats.ARCHIVE.extensions }) {
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            val ext = mime?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?: MIME_FALLBACK[mime]
            if (ext != null) finalName = "$finalName.$ext"
        }
        return finalName to size
    }

    private fun edit(id: String, transform: (ConvertEntry) -> ConvertEntry) = _state.update { s ->
        s.copy(entries = s.entries.map { if (it.id == id) transform(it) else it })
    }

    // ------------------------------------------------------------ configurações e histórico

    fun updateSettings(transform: (ConverterSettings) -> ConverterSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        val o = next.options
        prefs.edit {
            putInt("quality", o.quality)
            putBoolean("keepMetadata", o.keepMetadata); putString("font", o.font.name); putInt("fontSize", o.fontSize)
            putFloat("marginMm", o.marginMm); putString("conflict", o.conflict.name); putBoolean("preserveFolders", o.preserveFolders)
            putString("treeUri", next.treeUri); putString("treeLabel", next.treeLabel); putString("lastTarget", next.lastTarget)
        }
    }

    /** Pasta escolhida em "Salvar em": guarda a permissão para os próximos usos. */
    fun setTree(uri: Uri?) {
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        val label = uri?.let { DocumentFile.fromTreeUri(context, it)?.name }
        updateSettings { it.copy(treeUri = uri?.toString(), treeLabel = label) }
    }

    private fun loadSettings(): ConverterSettings {
        val d = ConvertOptions()
        return ConverterSettings(
            options = ConvertOptions(
                quality = prefs.getInt("quality", d.quality),
                keepMetadata = prefs.getBoolean("keepMetadata", d.keepMetadata),
                font = runCatching { PdfFont.valueOf(prefs.getString("font", null)!!) }.getOrDefault(d.font),
                fontSize = prefs.getInt("fontSize", d.fontSize),
                marginMm = prefs.getFloat("marginMm", d.marginMm),
                conflict = runCatching { ConflictPolicy.valueOf(prefs.getString("conflict", null)!!) }.getOrDefault(d.conflict),
                preserveFolders = prefs.getBoolean("preserveFolders", d.preserveFolders),
            ),
            treeUri = prefs.getString("treeUri", null),
            treeLabel = prefs.getString("treeLabel", null),
            lastTarget = prefs.getString("lastTarget", null),
        )
    }

    private fun addHistory(item: HistoryItem) {
        _history.update { (listOf(item) + it).take(300) }
        runCatching {
            val arr = JSONArray()
            _history.value.forEach { h ->
                arr.put(JSONObject().put("time", h.time).put("source", h.source).put("target", h.target)
                    .put("ok", h.ok).put("message", h.message).put("outputs", JSONArray(h.outputs)))
            }
            historyFile.writeText(arr.toString())
        }
    }

    fun clearHistory() {
        _history.value = emptyList()
        historyFile.delete()
    }

    private fun loadHistory(): List<HistoryItem> = runCatching {
        if (!historyFile.exists()) return emptyList()
        val arr = JSONArray(historyFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val outs = o.optJSONArray("outputs") ?: JSONArray()
            HistoryItem(o.getLong("time"), o.getString("source"), o.getString("target"), o.getBoolean("ok"),
                o.optString("message"), (0 until outs.length()).map { outs.getString(it) })
        }
    }.getOrElse { emptyList() }

    companion object {
        private const val TAG = "Converter"
        // tipos que o MimeTypeMap do Android não conhece
        private val MIME_FALLBACK = mapOf(
            "text/markdown" to "md", "image/heic" to "heic", "image/heif" to "heif", "image/avif" to "avif",
            "application/x-7z-compressed" to "7z", "application/gzip" to "tar.gz",
        )
    }
}
