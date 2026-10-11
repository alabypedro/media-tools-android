package com.alabypedro.mediatools.convert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

object PdfConverters {

    /**
     * PDF → PNG/JPG/WEBP, uma imagem por página (desktop: pdf_to_image.py).
     * Gera "<nome>_pagina_001.<ext>" em [outDir].
     */
    fun toImages(input: File, outDir: File, target: String, options: ConvertOptions, onProgress: (Float) -> Unit): List<File> {
        val pfd = try {
            ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            throw ConversionException("Não foi possível abrir \"${input.name}\".", e)
        }
        val renderer = try {
            PdfRenderer(pfd)
        } catch (e: SecurityException) {
            pfd.close()
            throw ConversionException("\"${input.name}\" é protegido por senha.", e)
        } catch (e: Exception) {
            pfd.close()
            throw ConversionException("Não foi possível abrir \"${input.name}\" como PDF (arquivo corrompido?).", e)
        }
        renderer.use {
            val total = renderer.pageCount
            val indices = pageRangeToIndices(options.pages, total)
            if (indices.isEmpty()) {
                throw ConversionException("Nenhuma das páginas pedidas existe em \"${input.name}\" ($total página(s)).")
            }
            val digits = maxOf(3, total.toString().length)
            val stem = Formats.stemOf(input.name)
            val format = when (target) {
                "png" -> Bitmap.CompressFormat.PNG
                "webp" -> ImageConverter.webpFormat(lossless = false)
                else -> Bitmap.CompressFormat.JPEG
            }
            outDir.mkdirs()
            return indices.mapIndexed { n, idx ->
                renderer.openPage(idx).use { page ->
                    var scale = options.dpi / 72f
                    // páginas enormes em DPI alto estouram a memória: limita a ~40 MP
                    val pixels = page.width * scale * page.height * scale
                    if (pixels > MAX_PIXELS) scale *= Math.sqrt(MAX_PIXELS / pixels.toDouble()).toFloat()
                    val bmp = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    val file = File(outDir, "${stem}_pagina_${(idx + 1).toString().padStart(digits, '0')}.$target")
                    file.outputStream().buffered().use { bmp.compress(format, options.quality.coerceIn(1, 100), it) }
                    bmp.recycle()
                    onProgress((n + 1f) / indices.size)
                    file
                }
            }
        }
    }

    /**
     * Achata o PDF (desktop: pdf_flatten.py): campos de formulário e anotações
     * (comentários, carimbos, destaques...) viram conteúdo fixo da página.
     * Links e popups continuam; ícones de nota ("post-it") somem.
     */
    fun flatten(context: Context, input: File, output: File) {
        load(context, input).use { doc ->
            doc.documentCatalog.acroForm?.let { form ->
                runCatching { form.refreshAppearances() }
                form.flatten()
            }
            for (page in doc.pages) {
                val keep = mutableListOf<PDAnnotation>()
                val annotations = page.annotations
                if (annotations.isEmpty()) continue
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (a in annotations) {
                        when (a.subtype) {
                            "Link", "Popup" -> keep += a
                            "Text" -> Unit // ícone de nota: sem aparência impressa relevante
                            else -> if (!a.isHidden && !a.isNoView) drawAppearance(cs, a)
                        }
                    }
                }
                page.annotations = keep
            }
            output.parentFile?.mkdirs()
            doc.save(output)
        }
    }

    /** Aparência normal da anotação desenhada no retângulo dela (PDF 32000-1, 12.5.5). */
    private fun drawAppearance(cs: PDPageContentStream, a: PDAnnotation) {
        val ap = a.normalAppearanceStream ?: return
        val rect = a.rectangle ?: return
        val bbox = ap.bBox ?: return
        if (rect.width <= 0 || rect.height <= 0 || bbox.width <= 0 || bbox.height <= 0) return
        // caixa da aparência depois da /Matrix dela
        val m = ap.matrix
        val pts = listOf(bbox.lowerLeftX to bbox.lowerLeftY, bbox.upperRightX to bbox.lowerLeftY,
            bbox.upperRightX to bbox.upperRightY, bbox.lowerLeftX to bbox.upperRightY)
            .map { (x, y) -> m.transformPoint(x, y) }
        val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
        if (maxX - minX <= 0 || maxY - minY <= 0) return
        val sx = rect.width / (maxX - minX)
        val sy = rect.height / (maxY - minY)
        cs.saveGraphicsState()
        cs.transform(Matrix(sx, 0f, 0f, sy, rect.lowerLeftX - minX * sx, rect.lowerLeftY - minY * sy))
        cs.drawForm(ap)
        cs.restoreGraphicsState()
    }

    /** Texto do PDF (para PDF → TXT/DOCX/ODT/RTF). */
    fun extractText(context: Context, input: File): String = load(context, input).use { doc ->
        PDFTextStripper().getText(doc)
    }

    private fun load(context: Context, input: File): PDDocument {
        PDFBoxResourceLoader.init(context.applicationContext)
        return try {
            PDDocument.load(input)
        } catch (e: InvalidPasswordException) {
            throw ConversionException("\"${input.name}\" é protegido por senha.", e)
        } catch (e: Exception) {
            throw ConversionException("Não foi possível abrir \"${input.name}\" como PDF (arquivo corrompido?).", e)
        }
    }

    private const val MAX_PIXELS = 40_000_000f
}
