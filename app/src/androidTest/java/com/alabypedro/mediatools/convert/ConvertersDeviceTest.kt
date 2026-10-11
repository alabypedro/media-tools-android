package com.alabypedro.mediatools.convert

import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Passa cada amostra (androidTest/assets/samples) por TODOS os destinos que
 * o app oferece para ela, no aparelho de verdade, e confere se o resultado
 * abre. Rodar com: ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class ConvertersDeviceTest {

    companion object {
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
        private lateinit var samples: File

        @BeforeClass @JvmStatic
        fun setUp() {
            YoutubeDL.init(context)
            FFmpeg.init(context)
            PDFBoxResourceLoader.init(context)
            samples = File(context.cacheDir, "test-samples").apply { deleteRecursively(); mkdirs() }
            val assets = InstrumentationRegistry.getInstrumentation().context.assets
            assets.list("samples")!!.forEach { name ->
                assets.open("samples/$name").use { input -> File(samples, name).outputStream().use { input.copyTo(it) } }
            }
        }

        /** Destinos que podem falhar por limitação do aparelho (não do app). */
        private val DEVICE_DEPENDENT = setOf("heic", "heif")
    }

    private val ffmpeg = FFmpegRunner(context)

    @Test
    fun everySampleToEveryTarget() {
        val failures = mutableListOf<String>()
        var ok = 0
        for (file in samples.listFiles()!!.sortedBy { it.name }) {
            for (target in Formats.targetsFor(Formats.extensionOf(file.name)).sorted()) {
                val work = File(context.cacheDir, "test-work").apply { deleteRecursively(); mkdirs() }
                val label = "${file.name} → $target"
                val started = System.currentTimeMillis()
                try {
                    val outputs = Converters.run(context, ffmpeg, file, target, ConvertOptions(dpi = 72), work)
                    assertTrue("$label: nada gerado", outputs.isNotEmpty())
                    outputs.forEach { verify(it, target) }
                    ok++
                    Log.i("ConvTest", "OK   $label (${System.currentTimeMillis() - started} ms) ${outputs.joinToString { "${it.name}=${it.length()}B" }}")
                } catch (e: Throwable) {
                    val msg = "$label: ${e.javaClass.simpleName}: ${e.message?.lines()?.take(3)?.joinToString(" | ")}"
                    if (target in DEVICE_DEPENDENT) Log.w("ConvTest", "SKIP $msg") else { failures += msg; Log.e("ConvTest", "FAIL $msg", e) }
                }
            }
        }
        Log.i("ConvTest", "RESUMO: $ok ok, ${failures.size} falhas")
        assertTrue("Falharam ${failures.size}:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /** O arquivo gerado abre como o formato que diz ser? */
    private fun verify(out: File, target: String) {
        assertTrue("${out.name} vazio", out.length() > 0)
        val ext = Formats.extensionOf(out.name)
        when {
            ext == "pdf" -> ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { assertTrue("${out.name} sem páginas", it.pageCount > 0) }
            }
            ext in setOf("tif", "tiff", "bmp", "gif", "ico", "avif") || ext in Formats.VIDEO.extensions || ext in Formats.AUDIO.extensions -> {
                val r = ffmpeg.run(listOf("-v", "error", "-i", out.path, "-f", "null", "-"))
                assertTrue("${out.name} não abre no FFmpeg: ${r.tail}", r.ok)
                if (ext in Formats.AUDIO.extensions) assertTrue("${out.name} sem áudio", "Audio:" in ffmpeg.run(listOf("-i", out.path)).log)
            }
            ext in Formats.IMAGE.extensions -> {
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(out))
                assertTrue(bmp.width > 0 && bmp.height > 0)
            }
            ext in Formats.ARCHIVE.extensions -> {
                val back = File(out.parentFile!!.parentFile, "check.zip")
                ArchiveConverter.convert(out, back, File(out.parentFile!!.parentFile, "check"), { false })
                java.util.zip.ZipFile(back).use { assertTrue("${out.name} vazio", it.size() > 0) }
            }
            else -> assertTrue("${out.name} sem conteúdo", Office.read(out).isNotEmpty())
        }
    }

    @Test
    fun jpgKeepsExifAndAppliesRotation() {
        val work = File(context.cacheDir, "test-exif").apply { deleteRecursively(); mkdirs() }
        val out = Converters.run(context, ffmpeg, File(samples, "foto.jpg"), "webp", ConvertOptions(), work).single()
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(out))
        // a amostra é 400x200 com orientação 6 (90°): os pixels saem girados
        assertEquals(200, bmp.width)
        assertEquals(400, bmp.height)
        assertEquals("CameraTeste", ExifInterface(out).getAttribute(ExifInterface.TAG_MAKE))
    }

    @Test
    fun animatedGifStaysAnimatedAsWebp() {
        val work = File(context.cacheDir, "test-anim").apply { deleteRecursively(); mkdirs() }
        val out = Converters.run(context, ffmpeg, File(samples, "anim.gif"), "webp", ConvertOptions(), work).single()
        assertTrue(ImageDecoder.decodeDrawable(ImageDecoder.createSource(out)) is android.graphics.drawable.AnimatedImageDrawable)
    }

    @Test
    fun flattenBakesFormAndAnnotations() {
        val work = File(context.cacheDir, "test-flat").apply { deleteRecursively(); mkdirs() }
        val out = Converters.run(context, ffmpeg, File(samples, "formulario.pdf"), Formats.FLATTEN, ConvertOptions(), work).single()
        assertEquals("formulario (achatado).pdf", out.name)
        PDDocument.load(out).use { doc ->
            assertTrue("ainda tem campos", doc.documentCatalog.acroForm?.fields.isNullOrEmpty())
            assertTrue("ainda tem anotações", doc.pages.all { p -> p.annotations.all { it.subtype in setOf("Link", "Popup") } })
            val text = PDFTextStripper().getText(doc)
            assertTrue(text, "Pedro Preenchido" in text)
        }
    }

    @Test
    fun pdfToImagesHonorsPages() {
        val work = File(context.cacheDir, "test-pages").apply { deleteRecursively(); mkdirs() }
        val outs = Converters.run(context, ffmpeg, File(samples, "formulario.pdf"), "png", ConvertOptions(pages = "2", dpi = 100), work)
        assertEquals(listOf("formulario_pagina_002.png"), outs.map { it.name })
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(outs[0]))
        assertEquals((595 * 100 / 72f).toInt(), bmp.width) // A4 a 100 DPI
    }

    @Test
    fun docxKeepsHeadingsAndTable() {
        val blocks = Office.read(File(samples, "relatorio.docx"))
        assertEquals(Block.Title(1, "Relatório Anual"), blocks[0])
        assertEquals(Block.Para("Texto com negrito e acentuação."), blocks[1])
        assertEquals(Block.Para("• Item de lista"), blocks[2])
        assertEquals(listOf(listOf("Produto", "Preço"), listOf("Café", "12,50")), (blocks[3] as Block.Table).rows)
        assertEquals(listOf(listOf("Mês", "Total"), listOf("Janeiro", "1500.5")),
            (Office.read(File(samples, "planilha.xlsx")).single() as Block.Table).rows)
    }

    @Test
    fun mergeImagesIntoPdf() {
        val work = File(context.cacheDir, "test-merge").apply { deleteRecursively(); mkdirs() }
        val out = File(work, "junto.pdf")
        ImagesToPdf.merge(ffmpeg, listOf("foto.jpg", "logo.png", "anim.gif").map { File(samples, it) }, out,
            MergeOptions(PageSize.A4, landscape = true, fit = FitMode.CONTAIN), work) {}
        ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                assertEquals(3, r.pageCount)
                r.openPage(0).use { assertTrue("paisagem", it.width > it.height) }
            }
        }
    }
}
