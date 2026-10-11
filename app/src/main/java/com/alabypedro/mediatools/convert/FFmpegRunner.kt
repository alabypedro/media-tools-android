package com.alabypedro.mediatools.convert

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.io.InputStreamReader

/**
 * Roda o FFmpeg que a youtubedl-android empacota (libffmpeg.so em
 * nativeLibraryDir, bibliotecas extraídas em no_backup/youtubedl-android).
 * Mesmo ambiente que a biblioteca usa para o yt-dlp.
 */
class FFmpegRunner(private val context: Context) {
    @Volatile private var process: Process? = null
    @Volatile private var canceled = false

    private val binary get() = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")

    private fun env(): Map<String, String> {
        val packages = File(context.noBackupFilesDir, "${YoutubeDL.baseName}/packages")
        return mapOf(
            "LD_LIBRARY_PATH" to listOf("ffmpeg", "python").joinToString(":") { File(packages, "$it/usr/lib").absolutePath },
            "TMPDIR" to context.cacheDir.absolutePath,
        )
    }

    /**
     * Executa `ffmpeg -hide_banner -y <args>`. [onProgress] recebe 0..1 quando
     * dá para calcular (pela duração que o próprio FFmpeg informa).
     * Lança [ConversionCanceled] se [cancel] foi chamado.
     */
    fun run(args: List<String>, onProgress: ((Float) -> Unit)? = null): Result {
        FFmpeg.init(context)
        canceled = false
        val cmd = listOf(binary.absolutePath, "-hide_banner", "-nostdin", "-y") + args
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().putAll(env())
        val p = pb.start()
        process = p
        val log = StringBuilder()
        var duration = 0.0
        try {
            InputStreamReader(p.inputStream, Charsets.UTF_8).use { reader ->
                val line = StringBuilder()
                while (true) {
                    val c = reader.read()
                    if (c < 0) break
                    if (c == '\r'.code || c == '\n'.code) {
                        val text = line.toString()
                        line.setLength(0)
                        if (text.isEmpty()) continue
                        if (log.length < 64_000) log.appendLine(text) else { log.delete(0, 16_000); log.appendLine(text) }
                        if (duration == 0.0) DURATION.find(text)?.let { duration = seconds(it) }
                        if (duration > 0 && onProgress != null) {
                            TIME.find(text)?.let { onProgress((seconds(it) / duration).toFloat().coerceIn(0f, 1f)) }
                        }
                    } else {
                        line.append(c.toChar())
                    }
                }
            }
            val code = p.waitFor()
            if (canceled) throw ConversionCanceled()
            return Result(code, log.toString())
        } finally {
            process = null
        }
    }

    fun cancel() {
        canceled = true
        process?.destroy()
    }

    data class Result(val exitCode: Int, val log: String) {
        val ok get() = exitCode == 0
        /** Últimas linhas úteis do log, para a mensagem de erro. */
        val tail: String get() = log.lineSequence().filter { it.isNotBlank() && !it.startsWith("  ") }.toList().takeLast(3).joinToString("\n")
    }

    companion object {
        private val DURATION = Regex("""Duration: (\d+):(\d+):(\d+(?:\.\d+)?)""")
        private val TIME = Regex("""time=(\d+):(\d+):(\d+(?:\.\d+)?)""")
        private fun seconds(m: MatchResult) =
            m.groupValues[1].toDouble() * 3600 + m.groupValues[2].toDouble() * 60 + m.groupValues[3].toDouble()
    }
}
