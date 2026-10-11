package com.alabypedro.mediatools.convert

import android.graphics.Color
import android.graphics.Paint
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
