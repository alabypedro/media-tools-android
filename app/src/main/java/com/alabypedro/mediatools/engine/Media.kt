package com.alabypedro.mediatools.engine

import org.json.JSONArray
import org.json.JSONObject

/** Um formato oferecido pela plataforma (uma linha do `yt-dlp -F`). */
data class MediaFormat(
    val id: String,
    val ext: String,
    val vcodec: String?,
    val acodec: String?,
    val height: Int?,
    val fps: Double?,
    val tbr: Double?,
    val abr: Double?,
    val filesize: Long?,
) {
    val hasVideo get() = vcodec != null && vcodec != "none" && ext != "mhtml"
    val hasAudio get() = acodec != null && acodec != "none"

    /** Tamanho real, aproximado ou estimado pelo bitrate (kbps) x duração. */
    fun size(duration: Double?): Long? =
        filesize ?: (tbr ?: abr)?.let { kbps -> duration?.let { (kbps * 125 * it).toLong() } }
}

data class PlaylistEntry(val title: String, val url: String?, val duration: Double?)

/** Resultado da análise de uma URL (sem baixar nada). */
data class MediaInfo(
    val url: String,
    val title: String,
    val uploader: String?,
    val duration: Double?,
    val thumbnail: String?,
    val extractor: String?,
    val viewCount: Long?,
    val isLive: Boolean,
    val formats: List<MediaFormat>,
    val entries: List<PlaylistEntry>,
    val playlistCount: Int,
) {
    val isPlaylist get() = playlistCount > 0 || entries.isNotEmpty()

    // Sem lista de formatos (playlist ou extrator simples) assumimos que há os dois:
    // o yt-dlp escolhe na hora do download.
    val hasVideo get() = formats.isEmpty() || formats.any { it.hasVideo }
    val hasAudio get() = formats.isEmpty() || formats.any { it.hasAudio }

    /** Resoluções que realmente existem, da maior para a menor. */
    val videoHeights: List<Int>
        get() = formats.filter { it.hasVideo }.mapNotNull { it.height?.takeIf { h -> h > 0 } }
            .distinct().sortedDescending()

    /** FLAC só faz sentido se a fonte tiver áudio sem perdas. */
    val hasLosslessAudio: Boolean
        get() = formats.any { f ->
            val c = f.acodec?.lowercase() ?: return@any false
            LOSSLESS.any { c.startsWith(it) }
        }

    val bestAudioBitrate: Int?
        get() = formats.filter { it.hasAudio && !it.hasVideo }.mapNotNull { it.abr ?: it.tbr }
            .maxOrNull()?.toInt()

    private fun bestAudio(): MediaFormat? =
        formats.filter { it.hasAudio && !it.hasVideo }.maxByOrNull { it.abr ?: it.tbr ?: 0.0 }

    private fun bestVideo(maxHeight: Int?): MediaFormat? =
        formats.filter { it.hasVideo && (maxHeight == null || (it.height ?: 0) <= maxHeight) }
            .maxWithOrNull(compareBy<MediaFormat>({ it.height ?: 0 }, { it.fps ?: 0.0 }, { it.tbr ?: 0.0 }))

    /** Tamanho estimado de "vídeo até [maxHeight]" (vídeo + melhor áudio, se separados). */
    fun estimateVideoSize(maxHeight: Int?): Long? {
        val video = bestVideo(maxHeight) ?: return null
        val v = video.size(duration) ?: return null
        if (video.hasAudio) return v
        return v + (bestAudio()?.size(duration) ?: 0)
    }

    fun estimateAudioSize(format: String, bitrate: String): Long? {
        val d = duration ?: return null
        val kbps = when {
            format == "wav" -> 1411.0
            format == "flac" -> 900.0
            bitrate == "original" || format == "original" -> bestAudio()?.let { it.abr ?: it.tbr } ?: return null
            else -> bitrate.toDoubleOrNull() ?: return null
        }
        return (kbps * 125 * d).toLong()
    }

    companion object {
        private val LOSSLESS = listOf("flac", "alac", "pcm", "wav")

        /** Lê a saída de `yt-dlp --dump-single-json --flat-playlist`. */
        fun parse(json: String, sourceUrl: String): MediaInfo {
            val o = JSONObject(json)
            val formats = o.optJSONArray("formats")?.let(::parseFormats).orEmpty().ifEmpty {
                // extrator sem lista de formatos: o próprio item é o formato
                if (o.has("ext") && o.optString("_type", "video") != "playlist") listOf(parseFormat(o)) else emptyList()
            }
            val entries = o.optJSONArray("entries")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { e ->
                        PlaylistEntry(
                            title = e.str("title") ?: e.str("id") ?: "Item ${i + 1}",
                            url = e.str("url") ?: e.str("webpage_url"),
                            duration = e.num("duration"),
                        )
                    }
                }
            }.orEmpty()
            val isPlaylist = o.optString("_type") == "playlist"
            return MediaInfo(
                url = o.str("webpage_url") ?: sourceUrl,
                title = o.str("title") ?: o.str("id") ?: sourceUrl,
                uploader = o.str("uploader") ?: o.str("channel") ?: o.str("creator"),
                duration = o.num("duration"),
                thumbnail = o.str("thumbnail") ?: o.optJSONArray("thumbnails")?.let(::lastThumbnail),
                extractor = o.str("extractor_key") ?: o.str("extractor"),
                viewCount = o.num("view_count")?.toLong(),
                isLive = o.optBoolean("is_live", false),
                formats = formats,
                entries = entries,
                playlistCount = if (isPlaylist) (o.num("playlist_count")?.toInt() ?: entries.size) else 0,
            )
        }

        private fun parseFormats(arr: JSONArray): List<MediaFormat> =
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parseFormat) }

        private fun parseFormat(f: JSONObject) = MediaFormat(
            id = f.str("format_id") ?: "",
            ext = f.str("ext") ?: "",
            vcodec = f.str("vcodec"),
            acodec = f.str("acodec"),
            height = f.num("height")?.toInt(),
            fps = f.num("fps"),
            tbr = f.num("tbr"),
            abr = f.num("abr"),
            filesize = (f.num("filesize") ?: f.num("filesize_approx"))?.toLong(),
        )

        private fun lastThumbnail(arr: JSONArray): String? =
            (arr.length() - 1 downTo 0).firstNotNullOfOrNull { arr.optJSONObject(it)?.str("url") }

        private fun JSONObject.str(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

        private fun JSONObject.num(key: String): Double? =
            if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }
    }
}
