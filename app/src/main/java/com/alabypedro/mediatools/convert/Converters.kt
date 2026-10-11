package com.alabypedro.mediatools.convert

import android.content.Context
import java.io.File

/** Escolhe e executa o conversor de um arquivo (usado pela fila e pelos testes no aparelho). */
object Converters {
    val NEEDS_FFMPEG = setOf(Handler.MEDIA, Handler.IMAGE, Handler.IMAGE_TO_PDF)

    /** Converte [input] para [target] dentro de [work]; devolve os arquivos gerados. */
    fun run(
        context: Context,
        ffmpeg: FFmpegRunner,
        input: File,
        target: String,
        options: ConvertOptions,
        work: File,
        isCanceled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
    ): List<File> {
        val ext = Formats.extensionOf(input.name)
        val handler = Formats.resolve(ext, target)
            ?: throw ConversionException("Não há como converter .$ext para ${Formats.targetLabel(target)}.")
        val outDir = File(work, "out").apply { mkdirs() }
        val stem = Formats.stemOf(input.name)
        val output = File(outDir, if (target == Formats.FLATTEN) "$stem${Formats.FLATTEN_SUFFIX}.pdf" else "$stem.$target")
        when (handler) {
            Handler.MEDIA -> MediaConverter.convert(ffmpeg, input, output, onProgress)
            Handler.IMAGE, Handler.IMAGE_TO_PDF -> ImageConverter.convert(ffmpeg, input, output, options, work)
            Handler.PDF_TO_IMAGE -> return PdfConverters.toImages(input, outDir, target, options, onProgress)
            Handler.PDF_FLATTEN -> PdfConverters.flatten(context, input, output)
            Handler.TEXT_TO_PDF -> TextPdf.fromPlainText(Office.decodeText(input.readBytes()), output, options)
            Handler.MARKDOWN_TO_PDF -> TextPdf.fromMarkdown(Office.decodeText(input.readBytes()), output, options)
            Handler.ARCHIVE -> ArchiveConverter.convert(input, output, work, isCanceled)
            Handler.OFFICE -> Office.write(Office.read(input) { PdfConverters.extractText(context, it) }, output, options)
        }
        if (isCanceled()) throw ConversionCanceled()
        if (!output.exists() || output.length() == 0L) throw ConversionException("A conversão não gerou nenhum arquivo.")
        return listOf(output)
    }
}
