package com.alabypedro.mediatools.convert

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Html
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.io.File

/**
 * Texto, Markdown e documentos → PDF (desktop: text_to_pdf.py e
 * markdown_to_pdf.py com reportlab). Paginação de um StaticLayout em
 * páginas A4 do PdfDocument do Android.
 */
object TextPdf {
    private const val A4_W = 595
    private const val A4_H = 842

    fun fromPlainText(text: String, output: File, options: ConvertOptions) =
        render(text, output, options)

    fun fromMarkdown(markdown: String, output: File, options: ConvertOptions) =
        fromHtml(markdownToHtml(markdown), output, options)

    fun fromHtml(html: String, output: File, options: ConvertOptions) =
        render(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY), output, options)

    fun markdownToHtml(markdown: String): String {
        val doc = Parser.builder().build().parse(markdown)
        return HtmlRenderer.builder().build().render(doc)
            // o Html do Android não conhece <pre>/<code>: vira <tt> (monoespaçado)
            .replace(Regex("<pre><code[^>]*>([\\s\\S]*?)</code></pre>")) { m ->
                "<p><tt>" + m.groupValues[1].trimEnd('\n').replace("\n", "<br>").replace(" ", "&nbsp;") + "</tt></p>"
            }
            .replace("<code>", "<tt>").replace("</code>", "</tt>")
    }

    private fun render(text: CharSequence, output: File, options: ConvertOptions) {
        val margin = options.marginMm * 72f / 25.4f
        val width = (A4_W - 2 * margin).toInt().coerceAtLeast(100)
        val height = A4_H - 2 * margin
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = options.fontSize.toFloat()
            typeface = when (options.font) {
                PdfFont.SANS -> Typeface.SANS_SERIF
                PdfFont.SERIF -> Typeface.SERIF
                PdfFont.MONO -> Typeface.MONOSPACE
            }
        }
        val body = if (text is Spanned) text else text.toString().replace("\t", "    ")
        val layout = StaticLayout.Builder.obtain(body, 0, body.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()

        val doc = PdfDocument()
        try {
            var line = 0
            var pageNo = 1
            do {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(A4_W, A4_H, pageNo++).create())
                if (layout.lineCount > 0) {
                    val top = layout.getLineTop(line)
                    var end = line
                    while (end < layout.lineCount && layout.getLineBottom(end) - top <= height) end++
                    if (end == line) end = line + 1 // linha mais alta que a página
                    val bottom = layout.getLineBottom(end - 1)
                    page.canvas.apply {
                        save()
                        translate(margin, margin - top)
                        clipRect(0f, top.toFloat(), width.toFloat(), bottom.toFloat())
                        layout.draw(this)
                        restore()
                    }
                    line = end
                }
                doc.finishPage(page)
            } while (line < layout.lineCount)
            output.parentFile?.mkdirs()
            output.outputStream().buffered().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }
}

enum class PageSize(val label: String, val mm: Pair<Float, Float>?) {
    A4("A4", 210f to 297f), LETTER("Carta", 215.9f to 279.4f), A3("A3", 297f to 420f), ORIGINAL("Tamanho da imagem", null)
}

enum class FitMode(val label: String) { CONTAIN("Caber inteira"), COVER("Preencher (corta)"), STRETCH("Esticar") }

data class MergeOptions(
    val pageSize: PageSize = PageSize.A4,
    val landscape: Boolean = false,
    val fit: FitMode = FitMode.CONTAIN,
    val marginMm: Float = 10f,
)

/** Várias imagens → um PDF, uma página por imagem (desktop: image_to_pdf.py). */
object ImagesToPdf {
    fun merge(ffmpeg: FFmpegRunner, images: List<File>, output: File, options: MergeOptions, work: File, onProgress: (Float) -> Unit) {
        if (images.isEmpty()) throw ConversionException("Nenhuma imagem selecionada para gerar o PDF.")
        val doc = PdfDocument()
        try {
            images.forEachIndexed { i, file ->
                val bmp = ImageConverter.decode(ffmpeg, file, work)
                val (pw, ph) = pageSize(options, bmp)
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                val m = if (options.pageSize == PageSize.ORIGINAL) 0f else options.marginMm * 72f / 25.4f
                val box = RectF(m, m, pw - m, ph - m)
                val canvas = page.canvas
                canvas.save()
                canvas.clipRect(box)
                val dest = target(bmp, box, options.fit)
                // o PDF guarda a imagem sem perdas: acima de ~200 DPI na página só aumenta o arquivo
                val maxW = (dest.width() * 200 / 72).toInt().coerceAtLeast(1)
                val draw = if (options.pageSize != PageSize.ORIGINAL && bmp.width > maxW) {
                    Bitmap.createScaledBitmap(bmp, maxW, (bmp.height.toLong() * maxW / bmp.width).toInt().coerceAtLeast(1), true)
                } else bmp
                canvas.drawBitmap(draw, null, dest, Paint(Paint.FILTER_BITMAP_FLAG))
                canvas.restore()
                doc.finishPage(page)
                if (draw !== bmp) draw.recycle()
                bmp.recycle()
                onProgress((i + 1f) / images.size)
            }
            output.parentFile?.mkdirs()
            output.outputStream().buffered().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    private fun pageSize(o: MergeOptions, bmp: Bitmap): Pair<Int, Int> {
        val mm = o.pageSize.mm ?: return bmp.width to bmp.height
        val w = (mm.first * 72 / 25.4f).toInt()
        val h = (mm.second * 72 / 25.4f).toInt()
        return if (o.landscape) h to w else w to h
    }

    private fun target(bmp: Bitmap, box: RectF, fit: FitMode): RectF {
        if (fit == FitMode.STRETCH) return box
        val scale = if (fit == FitMode.CONTAIN) minOf(box.width() / bmp.width, box.height() / bmp.height)
        else maxOf(box.width() / bmp.width, box.height() / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        return RectF(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2)
    }
}
