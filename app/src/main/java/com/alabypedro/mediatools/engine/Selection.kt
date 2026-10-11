package com.alabypedro.mediatools.engine

enum class Mode { VIDEO, AUDIO }

/** O que o usuário escolheu na tela Início. */
data class Selection(
    val mode: Mode = Mode.VIDEO,
    /** "best" ou a altura máxima ("1080", "720"...). */
    val quality: String = "best",
    /** mp4 | mkv | webm | original */
    val container: String = "mp4",
    /** mp3 | m4a | opus | wav | flac | original */
    val audioFormat: String = "mp3",
    /** 320 | 256 | 192 | 128 | original */
    val audioBitrate: String = "320",
    val embedMetadata: Boolean = true,
) {
    val label: String
        get() = when (mode) {
            Mode.AUDIO -> buildString {
                append(if (audioFormat == "original") "Áudio original" else audioFormat.uppercase())
                if (audioFormat !in setOf("original", "wav", "flac")) {
                    append(" · ").append(if (audioBitrate == "original") "VBR" else "$audioBitrate kbps")
                }
            }
            Mode.VIDEO -> buildString {
                append(if (quality == "best") "Melhor qualidade" else "${quality}p")
                append(" · ").append(if (container == "original") "Original" else container.uppercase())
            }
        }

    /**
     * Traduz a escolha em argumentos do yt-dlp. Mesma lógica do app desktop
     * (providers/yt_dlp_provider.py → build_format_params): prefere streams
     * compatíveis e só troca o contêiner, sem recomprimir.
     */
    fun toArgs(isLive: Boolean = false): List<String> {
        val args = mutableListOf<String>()
        when (mode) {
            Mode.AUDIO -> {
                args += listOf("-f", "ba/b", "-x")
                if (audioFormat == "original") {
                    args += listOf("--audio-format", "best")
                } else {
                    args += listOf("--audio-format", audioFormat)
                    args += listOf("--audio-quality", if (audioBitrate == "original") "0" else "${audioBitrate}K")
                    // AAC na fonte: extrai sem recodificar
                    if (audioFormat == "m4a") args += listOf("-S", "acodec:aac")
                }
            }
            Mode.VIDEO -> {
                args += listOf("-f", "bv*+ba/b")
                val sort = mutableListOf(if (quality.toIntOrNull() != null) "res:$quality" else "res", "fps")
                when (container) {
                    "mp4" -> {
                        sort += "ext:mp4:m4a"
                        args += listOf("--merge-output-format", "mp4", "--remux-video", "mp4")
                    }
                    "mkv" -> args += listOf("--merge-output-format", "mkv", "--remux-video", "mkv")
                    // webm exige VP8/VP9/AV1 + Opus/Vorbis: recodifica só se a fonte não tiver
                    "webm" -> {
                        sort += "ext:webm:webm"
                        args += listOf("--merge-output-format", "webm", "--recode-video", "webm")
                    }
                }
                args += listOf("-S", sort.joinToString(","))
            }
        }
        if (embedMetadata && !isLive) args += listOf("--embed-metadata", "--embed-chapters")
        return args
    }
}
