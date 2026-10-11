package com.alabypedro.mediatools.convert

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.pdf.PdfDocument
import androidx.exifinterface.media.ExifInterface
import androidx.heifwriter.HeifWriter
import java.io.File

/**
 * Imagem → imagem/PDF (desktop: converters/image.py com Pillow).
 *
 * - Decodifica com o ImageDecoder do Android (aplica a rotação EXIF e lê
 *   HEIC/HEIF/AVIF); TIFF, que o Android não lê, passa antes pelo FFmpeg.
 * - JPG/PNG/WEBP são gravados pelo Android; HEIC pelo HeifWriter (precisa
 *   do codificador HEVC do aparelho); BMP/GIF/TIFF/ICO/AVIF pelo FFmpeg.
 * - GIF/WEBP animado → GIF/WEBP continua animado (FFmpeg); para formatos
 *   sem animação fica só o primeiro quadro, como no desktop.
 */
object ImageConverter {
    private val EXIF_TARGETS = setOf("jpg", "jpeg", "png", "webp")
    private val ANDROID_UNREADABLE = setOf("tif", "tiff")

    fun convert(ffmpeg: FFmpegRunner, input: File, output: File, options: ConvertOptions, work: File) {
        val src = Formats.extensionOf(input.name)
        val dst = Formats.extensionOf(output.name)
        output.parentFile?.mkdirs()

        if (dst in setOf("gif", "webp") && src in setOf("gif", "webp") && isAnimated(input)) {
            convertAnimated(ffmpeg, input, output, options)
            return
        }

        val bitmap = decode(ffmpeg, input, work)
        try {
            when (dst) {
                "jpg", "jpeg" -> compress(bitmap.flattenAlpha(), Bitmap.CompressFormat.JPEG, options.quality, output)
                "png" -> compress(bitmap, Bitmap.CompressFormat.PNG, 100, output)
                "webp" -> compress(bitmap, webpFormat(lossless = options.quality >= 100), options.quality, output)
                "heic", "heif" -> writeHeic(bitmap, output, options.quality)
                "pdf" -> writePdf(bitmap, output)
                else -> {
                    val png = File(work, "intermediate.png")
                    compress(bitmap, Bitmap.CompressFormat.PNG, 100, png)
                    encodeWithFfmpeg(ffmpeg, png, output, dst, options.quality)
                }
            }
        } finally {
            bitmap.recycle()
        }
        if (options.keepMetadata && dst in EXIF_TARGETS) copyExif(input, output)
    }

    fun decode(ffmpeg: FFmpegRunner, input: File, work: File): Bitmap {
        val src = Formats.extensionOf(input.name)
        val readable = if (src in ANDROID_UNREADABLE) {
            val png = File(work, "decoded.png")
            val r = ffmpeg.run(listOf("-i", input.path, "-frames:v", "1", png.path))
            if (!r.ok) throw ConversionException("Não foi possível ler a imagem \"${input.name}\".\n${r.tail}")
            png
        } else input
        return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(readable)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                // imagens gigantes (ex.: 200 MP) estouram a memória: limita a ~50 MP
                val pixels = info.size.width.toLong() * info.size.height
                if (pixels > MAX_PIXELS) {
                    val scale = Math.sqrt(MAX_PIXELS.toDouble() / pixels)
                    decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                }
            }
        } catch (e: Exception) {
            throw ConversionException("Não foi possível ler a imagem \"${input.name}\" (arquivo corrompido ou formato não suportado pelo aparelho).", e)
        }
    }

    private fun isAnimated(file: File): Boolean = runCatching {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) is AnimatedImageDrawable
    }.getOrDefault(false)

    private fun convertAnimated(ffmpeg: FFmpegRunner, input: File, output: File, options: ConvertOptions) {
        val dst = Formats.extensionOf(output.name)
        val args = mutableListOf("-i", input.path)
        args += if (dst == "gif") {
            listOf("-filter_complex", "[0:v]split[a][b];[a]palettegen[p];[b][p]paletteuse", "-loop", "0")
        } else {
            listOf("-c:v", "libwebp_anim", "-loop", "0", "-quality", options.quality.toString(), "-lossless", "0")
        }
        args += output.path
        val r = ffmpeg.run(args)
        if (!r.ok || output.length() == 0L) {
            output.delete()
            throw ConversionException("Não foi possível converter a animação \"${input.name}\".\n${r.tail}")
        }
    }

    private fun encodeWithFfmpeg(ffmpeg: FFmpegRunner, png: File, output: File, dst: String, quality: Int) {
        val args = mutableListOf("-i", png.path)
        args += when (dst) {
            "gif" -> listOf("-filter_complex", "[0:v]split[a][b];[a]palettegen[p];[b][p]paletteuse")
            "tif", "tiff" -> listOf("-c:v", "tiff", "-compression_algo", "deflate")
            // ICO: no máximo 256x256
            "ico" -> listOf("-vf", "scale='min(256,iw)':'min(256,ih)':force_original_aspect_ratio=decrease")
            "avif" -> listOf("-c:v", "libaom-av1", "-still-picture", "1", "-cpu-used", "6",
                "-crf", (50 - quality * 0.4).toInt().coerceIn(0, 63).toString(), "-pix_fmt", "yuv420p")
            else -> emptyList() // bmp
        }
        args += listOf("-frames:v", "1", "-update", "1", output.path)
        val r = ffmpeg.run(args)
        if (!r.ok || output.length() == 0L) {
            output.delete()
            throw ConversionException("Não foi possível gravar a imagem em ${dst.uppercase()}.\n${r.tail}")
        }
    }

    /** WEBP_LOSSY/LOSSLESS só existem a partir do Android 11; no 10, WEBP (qualidade 100 = sem perdas). */
    @Suppress("DEPRECATION")
    fun webpFormat(lossless: Boolean): Bitmap.CompressFormat = when {
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP
        lossless -> Bitmap.CompressFormat.WEBP_LOSSLESS
        else -> Bitmap.CompressFormat.WEBP_LOSSY
    }

    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int, output: File) {
        output.outputStream().buffered().use { out ->
            if (!bitmap.compress(format, quality.coerceIn(1, 100), out)) {
                throw ConversionException("Não foi possível gravar ${output.name}.")
            }
        }
    }

    private fun writeHeic(bitmap: Bitmap, output: File, quality: Int) {
        try {
            HeifWriter.Builder(output.path, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP)
                .setQuality(quality.coerceIn(1, 100))
                .build().use { writer ->
                    writer.start()
                    writer.addBitmap(bitmap)
                    writer.stop(30_000)
                }
        } catch (e: Exception) {
            output.delete()
            throw ConversionException("Este aparelho não consegue gravar HEIC (falta o codificador HEVC). Use JPG, WEBP ou AVIF.", e)
        }
    }

    /** Uma imagem = uma página (1 px = 1 pt, como o Pillow faz no desktop). */
    private fun writePdf(bitmap: Bitmap, output: File) {
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create())
            page.canvas.drawBitmap(bitmap, 0f, 0f, null)
            doc.finishPage(page)
            output.outputStream().buffered().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    /** JPEG não tem transparência: fundo branco (o Pillow faria convert("RGB")). */
    private fun Bitmap.flattenAlpha(): Bitmap {
        if (!hasAlpha()) return this
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(this@flattenAlpha, 0f, 0f, null) }
        return out
    }

    private val EXIF_TAGS = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    )

    /** Copia data, câmera e GPS. A rotação já foi aplicada nos pixels, então não é copiada. */
    private fun copyExif(input: File, output: File) = runCatching {
        val from = ExifInterface(input)
        val to = ExifInterface(output)
        var any = false
        EXIF_TAGS.forEach { tag -> from.getAttribute(tag)?.let { to.setAttribute(tag, it); any = true } }
        if (any) to.saveAttributes()
    }

    private const val MAX_PIXELS = 50_000_000L
}
