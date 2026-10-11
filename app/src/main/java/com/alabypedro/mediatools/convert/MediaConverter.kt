package com.alabypedro.mediatools.convert

import java.io.File

/**
 * Vídeo/áudio pelo FFmpeg (desktop: converters/media.py). Diferença: no
 * celular recodificar é lento, então vídeo → vídeo tenta antes só trocar o
 * contêiner (-c copy); se o formato de destino não aceitar os codecs da
 * origem, recodifica com codecs e qualidade definidos por formato (os
 * padrões do FFmpeg para AVI/WMV/FLV/MPG usam 200 kb/s, ilegível).
 */
object MediaConverter {

    fun convert(ffmpeg: FFmpegRunner, input: File, output: File, onProgress: (Float) -> Unit) {
        val src = Formats.extensionOf(input.name)
        val dst = Formats.extensionOf(output.name)
        output.parentFile?.mkdirs()

        if (src in Formats.VIDEO.extensions && dst in Formats.VIDEO.extensions) {
            val copy = ffmpeg.run(listOf("-i", input.path, "-map", "0:v:0", "-map", "0:a?", "-c", "copy") + muxerFlags(dst) + output.path, onProgress)
            if (copy.ok && output.length() > 0) return
            output.delete()
        }
        val args = mutableListOf("-i", input.path)
        if (dst in Formats.AUDIO.extensions) {
            args += listOf("-vn") + audioArgs(dst)
        } else {
            args += listOf("-map", "0:v:0", "-map", "0:a?") + videoArgs(dst)
        }
        args += muxerFlags(dst)
        args += output.path
        val result = ffmpeg.run(args, onProgress)
        if (!result.ok || !output.exists() || output.length() == 0L) {
            output.delete()
            throw ConversionException("O FFmpeg não conseguiu converter \"${input.name}\" para ${dst.uppercase()}.\n${result.tail}")
        }
    }

    // x264 exige largura/altura pares em yuv420p
    private const val EVEN = "scale=trunc(iw/2)*2:trunc(ih/2)*2"
    private val X264 = listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p", "-vf", EVEN)
    private val AAC = listOf("-c:a", "aac", "-b:a", "192k")

    fun videoArgs(dst: String): List<String> = when (dst) {
        "mp4", "m4v", "mov", "mkv", "3gp", "ts" -> X264 + AAC
        "webm" -> listOf("-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-row-mt", "1",
            "-crf", "32", "-b:v", "0", "-c:a", "libopus", "-b:a", "128k")
        "avi" -> listOf("-c:v", "mpeg4", "-q:v", "3", "-vf", EVEN, "-c:a", "libmp3lame", "-q:a", "3")
        "wmv" -> listOf("-c:v", "wmv2", "-q:v", "3", "-vf", EVEN, "-c:a", "wmav2", "-b:a", "192k")
        "flv" -> listOf("-c:v", "flv", "-q:v", "3", "-vf", EVEN, "-c:a", "libmp3lame", "-ar", "44100", "-q:a", "3")
        "mpg", "mpeg" -> listOf("-c:v", "mpeg2video", "-q:v", "3", "-vf", EVEN, "-c:a", "mp2", "-b:a", "192k")
        else -> X264 + AAC
    }

    fun audioArgs(dst: String): List<String> = when (dst) {
        "mp3" -> listOf("-c:a", "libmp3lame", "-q:a", "2")
        "aac", "m4a" -> AAC
        "ogg" -> listOf("-c:a", "libvorbis", "-q:a", "5")
        "opus" -> listOf("-c:a", "libopus", "-b:a", "128k")
        "flac" -> listOf("-c:a", "flac")
        "wav" -> listOf("-c:a", "pcm_s16le")
        "aiff" -> listOf("-c:a", "pcm_s16be")
        "wma" -> listOf("-c:a", "wmav2", "-b:a", "192k")
        else -> emptyList()
    }

    private fun muxerFlags(dst: String): List<String> = when (dst) {
        // moov no início: o vídeo começa a tocar antes de baixar inteiro
        "mp4", "m4v", "mov", "m4a" -> listOf("-movflags", "+faststart")
        else -> emptyList()
    }
}
