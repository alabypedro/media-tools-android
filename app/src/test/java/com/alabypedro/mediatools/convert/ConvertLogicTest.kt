package com.alabypedro.mediatools.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class ConvertLogicTest {
    @get:Rule val tmp = TemporaryFolder()

    // ------------------------------------------------------------ regras

    @Test
    fun compoundExtensions() {
        assertEquals("tar.gz", Formats.extensionOf("backup.TAR.GZ"))
        assertEquals("backup", Formats.stemOf("backup.tar.gz"))
        assertEquals("mp4", Formats.extensionOf("meu.video.mp4"))
        assertEquals("", Formats.extensionOf("LEIAME"))
    }

    @Test
    fun rulesMatchDesktop() {
        assertEquals(Handler.PDF_TO_IMAGE, Formats.resolve("pdf", "png"))
        assertEquals(Handler.PDF_FLATTEN, Formats.resolve("pdf", Formats.FLATTEN))
        assertEquals(Handler.IMAGE_TO_PDF, Formats.resolve("heic", "pdf"))
        assertEquals(Handler.TEXT_TO_PDF, Formats.resolve("txt", "pdf"))
        assertEquals(Handler.MARKDOWN_TO_PDF, Formats.resolve("md", "pdf"))
        assertEquals(Handler.MEDIA, Formats.resolve("mkv", "mp3"))
        assertEquals(Handler.ARCHIVE, Formats.resolve("tar.gz", "zip"))
        assertEquals(Handler.OFFICE, Formats.resolve("docx", "odt"))
        assertEquals(Handler.OFFICE, Formats.resolve("xlsx", "csv"))
        assertEquals(Handler.OFFICE, Formats.resolve("pdf", "docx"))
        // áudio não vira vídeo; legado do Office não tem conversão
        assertNull(Formats.resolve("mp3", "mp4"))
        assertTrue(Formats.targetsFor("doc").isEmpty())
        assertFalse("jpeg" in Formats.targetsFor("jpg"))
        assertFalse("mp4" in Formats.targetsFor("mp4"))
    }

    @Test
    fun commonTargetsIsIntersection() {
        val t = Formats.commonTargets(setOf("mp4", "mkv"))
        assertTrue("mp3" in t && "webm" in t)
        assertEquals("mp4", Formats.commonTargets(setOf("mkv", "avi")).first()) // mais usado primeiro
        assertTrue(Formats.commonTargets(setOf("mp3", "jpg")).isEmpty())
        assertTrue("pdf" in Formats.commonTargets(setOf("jpg", "png", "heic")))
    }

    @Test
    fun pageRanges() {
        assertEquals(listOf(0, 1, 2, 4), pageRangeToIndices("1-3, 5", 10))
        assertEquals(listOf(8, 9), pageRangeToIndices("9-", 10))
        assertEquals(listOf(0, 1, 2), pageRangeToIndices("", 3))
        assertEquals(emptyList<Int>(), pageRangeToIndices("20", 3))
    }

    @Test
    fun uniqueNames() {
        val existing = setOf("foto (1).jpg", "foto (2).jpg")
        assertEquals("foto (3).jpg", OutputSink.uniqueName("foto.jpg") { it in existing })
        assertEquals("a (1).tar.gz", OutputSink.uniqueName("a.tar.gz") { false })
    }

    // ------------------------------------------------------------ documentos

    private val sample = listOf(
        Block.Title(1, "Relatório & Notas"),
        Block.Para("Primeiro parágrafo com acentuação: ção, ü, €."),
        Block.Para("Linha 1\nLinha 2"),
        Block.Title(2, "Tabela"),
        Block.Table(listOf(listOf("Nome", "Valor"), listOf("Café", "12.5"), listOf("Pão; \"francês\"", "3"))),
    )

    private fun roundTrip(ext: String): List<Block> {
        val f = File(tmp.root, "doc.$ext")
        Office.write(sample, f, ConvertOptions())
        return Office.read(f)
    }

    @Test
    fun docxRoundTrip() {
        val back = roundTrip("docx")
        assertEquals(Block.Title(1, "Relatório & Notas"), back[0])
        assertEquals(Block.Para("Linha 1\nLinha 2"), back[2])
        assertEquals(Block.Title(2, "Tabela"), back[3])
        assertEquals(sample[4], back.filterIsInstance<Block.Table>().single())
        ZipFile(File(tmp.root, "doc.docx")).use { assertTrue(it.getEntry("word/document.xml") != null) }
    }

    @Test
    fun odtRoundTrip() {
        val back = roundTrip("odt")
        assertEquals(sample[0], back[0])
        assertEquals(sample[1], back[1])
        assertEquals(Block.Para("Linha 1\nLinha 2"), back[2])
        assertEquals(sample[4].let { (it as Block.Table).rows }, back.filterIsInstance<Block.Table>().single().rows)
        ZipFile(File(tmp.root, "doc.odt")).use { z ->
            val first = z.entries().nextElement()
            assertEquals("mimetype", first.name)
            assertEquals(java.util.zip.ZipEntry.STORED, first.method)
        }
    }

    @Test
    fun spreadsheetRoundTrips() {
        for (ext in listOf("xlsx", "ods")) {
            val table = roundTrip(ext).filterIsInstance<Block.Table>().single()
            assertEquals(ext, (sample[4] as Block.Table).rows, table.rows)
        }
    }

    @Test
    fun csvRoundTrip() {
        val f = File(tmp.root, "t.csv")
        Office.write(sample, f, ConvertOptions())
        assertTrue(f.readText().startsWith("﻿Nome,Valor"))
        assertEquals((sample[4] as Block.Table).rows, (Office.read(f).single() as Block.Table).rows)
        // ; como separador (Excel em português)
        assertEquals(listOf(listOf("a", "b c", "d"), listOf("1", "2", "")), Office.parseCsv("a;\"b c\";d\r\n1;2;\r\n"))
    }

    @Test
    fun rtfRoundTrip() {
        val text = Office.toText(roundTrip("rtf"))
        assertTrue(text, "Relatório & Notas" in text)
        assertTrue(text, "acentuação: ção, ü, €" in text)
        assertTrue(text, "Café\t12.5" in text)
    }

    @Test
    fun rtfReaderSkipsTables() {
        val bs = '\\'
        val rtf = "{${bs}rtf1${bs}ansi{${bs}fonttbl{${bs}f0 Arial;}}{${bs}colortbl;${bs}red0${bs}green0${bs}blue0;}${bs}f0 Ol${bs}'e1 " +
            "{${bs}b mundo}${bs}par Segunda ${bs}u8364? linha${bs}par}"
        assertEquals("Olá mundo\nSegunda € linha\n", Office.readRtf(rtf))
    }

    @Test
    fun markdownToBlocks() {
        val f = File(tmp.root, "n.md").apply { writeText("# Título\n\nTexto *com* `código`.\n\n- um\n- dois\n\n1. a\n2. b\n") }
        val blocks = Office.read(f)
        assertEquals(Block.Title(1, "Título"), blocks[0])
        assertEquals(Block.Para("Texto com código."), blocks[1])
        assertEquals(listOf("• um", "• dois", "1. a", "2. b"), blocks.drop(2).map { (it as Block.Para).text })
    }

    @Test
    fun textDecodingFallsBackToWindows1252() {
        assertEquals("ação", Office.decodeText("ação".toByteArray(Charsets.UTF_8)))
        assertEquals("ação", Office.decodeText("ação".toByteArray(charset("windows-1252"))))
    }

    @Test
    fun columns() {
        assertEquals("A", Office.columnName(0)); assertEquals("Z", Office.columnName(25)); assertEquals("AB", Office.columnName(27))
        assertEquals(27, Office.columnIndex("AB12"))
    }

    // ------------------------------------------------------------ compactados

    @Test(expected = ConversionException::class)
    fun zipSlipIsRejected() {
        ArchiveConverter.safe(tmp.root, "../../etc/passwd")
    }

    @Test
    fun archiveRoundTrip() {
        val src = tmp.newFolder("src")
        File(src, "a.txt").writeText("olá")
        File(src, "sub").mkdirs(); File(src, "sub/b.txt").writeText("b")
        val work = tmp.newFolder("work")
        // monta um zip, converte zip → tar.xz → 7z → zip e confere o conteúdo
        val zip = File(tmp.root, "x.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
            src.walkTopDown().filter { it.isFile }.forEach { f ->
                z.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(src).path.replace('\\', '/'))); z.write(f.readBytes()); z.closeEntry()
            }
        }
        val txz = File(tmp.root, "x.tar.xz"); ArchiveConverter.convert(zip, txz, work) { false }
        val sz = File(tmp.root, "x.7z"); ArchiveConverter.convert(txz, sz, work) { false }
        val back = File(tmp.root, "y.zip"); ArchiveConverter.convert(sz, back, work) { false }
        ZipFile(back).use { z ->
            assertEquals(setOf("a.txt", "sub/b.txt"), z.entries().toList().map { it.name }.toSet())
            assertEquals("olá", z.getInputStream(z.getEntry("a.txt")).readBytes().toString(Charsets.UTF_8))
        }
    }
}
