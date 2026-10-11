package com.alabypedro.mediatools.convert

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Conteúdo de um documento, independente do formato: o que sobrevive a qualquer conversão. */
sealed interface Block {
    data class Title(val level: Int, val text: String) : Block
    data class Para(val text: String) : Block
    data class Table(val rows: List<List<String>>, val name: String? = null) : Block
}

/**
 * Documentos/planilhas/apresentações sem LibreOffice: lê o formato de
 * origem para uma lista de [Block] e escreve no formato de destino.
 * Preserva texto, títulos, listas (como "• ") e tabelas.
 */
object Office {

    // ================================================================ leitura

    /** [pdfText] extrai o texto de um PDF (precisa do Android; injetado para os testes). */
    fun read(file: File, pdfText: (File) -> String = { error("PDF sem extrator") }): List<Block> =
        when (val ext = Formats.extensionOf(file.name)) {
            "docx" -> readDocx(file)
            "odt" -> readOdf(file, presentation = false)
            "odp" -> readOdf(file, presentation = true)
            "pptx" -> readPptx(file)
            "xlsx" -> readXlsx(file)
            "ods" -> readOds(file)
            "csv" -> listOf(Block.Table(parseCsv(decodeText(file.readBytes()))))
            "rtf" -> linesToParas(readRtf(file.readBytes().toString(Charsets.ISO_8859_1)))
            "txt" -> linesToParas(decodeText(file.readBytes()))
            "md", "markdown" -> readMarkdown(decodeText(file.readBytes()))
            "pdf" -> linesToParas(pdfText(file))
            else -> throw ConversionException(Formats.unsupportedReason(ext))
        }

    private fun linesToParas(text: String) = text.replace("\r\n", "\n").replace('\r', '\n').trimEnd()
        .split('\n').map { Block.Para(it.trimEnd()) }

    /** UTF-8; se não for UTF-8 válido, Windows-1252 (arquivos antigos do Windows). */
    fun decodeText(bytes: ByteArray): String {
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes, start, bytes.size - start)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    // ---------------------------------------------------------------- DOCX

    private fun readDocx(file: File): List<Block> = zip(file) { z ->
        val headingOf = docxHeadingStyles(z.bytes("word/styles.xml"))
        val body = xml(z.bytes("word/document.xml") ?: throw invalid(file)).first("body") ?: return@zip emptyList()
        val out = mutableListOf<Block>()
        fun walk(parent: Element) {
            for (el in parent.kids()) when (el.localName) {
                "p" -> {
                    val text = docxText(el)
                    val pPr = el.first("pPr")
                    val style = pPr?.first("pStyle")?.attr("val")
                    val level = pPr?.first("outlineLvl")?.attr("val")?.toIntOrNull()?.plus(1) ?: style?.let(headingOf)
                    when {
                        level != null && level in 1..6 && text.isNotBlank() -> out += Block.Title(level, text)
                        pPr?.first("numPr") != null && text.isNotBlank() -> out += Block.Para("• $text")
                        else -> out += Block.Para(text)
                    }
                }
                "tbl" -> out += Block.Table(el.kids("tr").map { tr -> tr.kids("tc").map { tc -> tc.kids("p").joinToString("\n") { docxText(it) } } })
                "sdt" -> el.first("sdtContent")?.let(::walk)
            }
        }
        walk(body)
        out
    }

    /** styleId → nível de título, pelo nome do estilo ("heading 1", "Title") ou outlineLvl. */
    private fun docxHeadingStyles(bytes: ByteArray?): (String) -> Int? {
        val map = mutableMapOf<String, Int>()
        if (bytes != null) runCatching {
            for (s in xml(bytes).kids("style")) {
                val id = s.attr("styleId") ?: continue
                val name = s.first("name")?.attr("val")?.lowercase().orEmpty()
                val lvl = s.first("pPr")?.first("outlineLvl")?.attr("val")?.toIntOrNull()?.plus(1)
                    ?: Regex("""heading (\d)""").find(name)?.groupValues?.get(1)?.toInt()
                    ?: if (name == "title") 1 else null
                if (lvl != null) map[id] = lvl
            }
        }
        return { id -> map[id] ?: Regex("""^Heading(\d)$""").find(id)?.groupValues?.get(1)?.toInt() }
    }

    private fun docxText(p: Element): String = buildString {
        fun walk(e: Element) {
            for (k in e.kids()) when (k.localName) {
                "t" -> append(k.textContent)
                "tab" -> append('\t')
                "br", "cr" -> append('\n')
                "del", "instrText", "rPr", "pPr" -> Unit
                else -> walk(k)
            }
        }
        walk(p)
    }

    // ---------------------------------------------------------------- ODT/ODP

    private fun readOdf(file: File, presentation: Boolean): List<Block> = zip(file) { z ->
        val content = xml(z.bytes("content.xml") ?: throw invalid(file))
        val body = content.first("body")?.kids()?.firstOrNull() ?: return@zip emptyList()
        val out = mutableListOf<Block>()
        if (presentation) {
            body.kids("page").forEachIndexed { i, page ->
                out += Block.Title(2, page.attr("name")?.takeIf { !it.startsWith("page") } ?: "Slide ${i + 1}")
                page.all("p").map(::odfText).filter { it.isNotBlank() }.forEach { out += Block.Para(it) }
            }
            return@zip out
        }
        fun walk(parent: Element, bullet: Boolean) {
            for (el in parent.kids()) when (el.localName) {
                "h" -> out += Block.Title(el.attr("outline-level")?.toIntOrNull()?.coerceIn(1, 6) ?: 1, odfText(el))
                "p" -> out += Block.Para(if (bullet) "• ${odfText(el)}" else odfText(el))
                "list" -> el.kids("list-item").forEach { walk(it, true) }
                "section" -> walk(el, bullet)
                "table" -> out += Block.Table(odfRows(el), el.attr("name"))
            }
        }
        walk(body, false)
        out
    }

    private fun odfText(e: Element): String = buildString {
        fun walk(n: org.w3c.dom.Node) {
            for (i in 0 until n.childNodes.length) {
                val k = n.childNodes.item(i)
                if (k.nodeType == org.w3c.dom.Node.TEXT_NODE) { append(k.nodeValue); continue }
                if (k !is Element) continue
                when (k.localName) {
                    "s" -> repeat(k.attr("c")?.toIntOrNull() ?: 1) { append(' ') }
                    "tab" -> append('\t')
                    "line-break" -> append('\n')
                    "note", "annotation" -> Unit
                    else -> walk(k)
                }
            }
        }
        walk(e)
    }

    /** Linhas de uma table:table, expandindo as repetições (com limite: o ODS repete células vazias até a coluna 1024). */
    private fun odfRows(table: Element): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        for (row in table.all("table-row")) {
            val cells = mutableListOf<String>()
            for (cell in row.kids().filter { it.localName == "table-cell" || it.localName == "covered-table-cell" }) {
                val text = cell.kids("p").joinToString("\n") { odfText(it) }
                val rep = (cell.attr("number-columns-repeated")?.toIntOrNull() ?: 1).coerceAtMost(if (text.isEmpty()) 64 else 1024)
                repeat(rep) { cells += text }
            }
            val rep = (row.attr("number-rows-repeated")?.toIntOrNull() ?: 1).coerceAtMost(if (cells.all { it.isEmpty() }) 1 else 10_000)
            repeat(rep) { rows += cells }
        }
        return trimTable(rows)
    }

    private fun readOds(file: File): List<Block> = zip(file) { z ->
        val content = xml(z.bytes("content.xml") ?: throw invalid(file))
        content.all("table").filter { it.parentNode is Element && (it.parentNode as Element).localName == "spreadsheet" }
            .map { Block.Table(odfRows(it), it.attr("name")) }
    }

    // ---------------------------------------------------------------- PPTX

    private fun readPptx(file: File): List<Block> = zip(file) { z ->
        val slides = z.names.mapNotNull { n -> Regex("""^ppt/slides/slide(\d+)\.xml$""").find(n)?.let { it.groupValues[1].toInt() to n } }
            .sortedBy { it.first }
        if (slides.isEmpty()) throw invalid(file)
        slides.flatMap { (n, name) ->
            val root = xml(z.bytes(name)!!)
            listOf(Block.Title(2, "Slide $n")) + root.all("p").map { p ->
                buildString { p.all("*").forEach { if (it.localName == "t") append(it.textContent) else if (it.localName == "br") append('\n') } }
            }.filter { it.isNotBlank() }.map { Block.Para(it) }
        }
    }

    // ---------------------------------------------------------------- XLSX

    private fun readXlsx(file: File): List<Block> = zip(file) { z ->
        val shared = z.bytes("xl/sharedStrings.xml")?.let { b ->
            xml(b).kids("si").map { si -> si.all("t").filter { (it.parentNode as? Element)?.localName != "rPh" }.joinToString("") { it.textContent } }
        }.orEmpty()
        val workbook = xml(z.bytes("xl/workbook.xml") ?: throw invalid(file))
        val rels = z.bytes("xl/_rels/workbook.xml.rels")?.let { b ->
            xml(b).kids("Relationship").associate { (it.attr("Id") ?: "") to (it.attr("Target") ?: "") }
        }.orEmpty()
        workbook.first("sheets")?.kids("sheet").orEmpty().mapNotNull { sheet ->
            val target = rels[sheet.attrNs("id")] ?: return@mapNotNull null
            val path = if (target.startsWith("/")) target.drop(1) else "xl/" + target.removePrefix("./")
            val data = z.bytes(path)?.let(::xml) ?: return@mapNotNull null
            val rows = sortedMapOf<Int, MutableMap<Int, String>>()
            data.all("row").forEachIndexed { ri, row ->
                val r = row.attr("r")?.toIntOrNull() ?: (ri + 1)
                row.kids("c").forEachIndexed { ci, c ->
                    val col = c.attr("r")?.let(::columnIndex) ?: ci
                    val v = c.first("v")?.textContent
                    val value = when (c.attr("t")) {
                        "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) }
                        "inlineStr" -> c.all("t").joinToString("") { it.textContent }
                        "b" -> if (v == "1") "VERDADEIRO" else "FALSO"
                        else -> v?.let(::cleanNumber)
                    }.orEmpty()
                    if (value.isNotEmpty()) rows.getOrPut(r) { mutableMapOf() }[col] = value
                }
            }
            if (rows.isEmpty()) return@mapNotNull Block.Table(emptyList(), sheet.attr("name"))
            val maxCol = rows.values.maxOf { it.keys.maxOrNull() ?: 0 }
            val grid = (1..rows.lastKey()).map { r -> (0..maxCol).map { c -> rows[r]?.get(c).orEmpty() } }
            Block.Table(trimTable(grid), sheet.attr("name"))
        }
    }

    /** "AB12" → 27 (0-based). */
    fun columnIndex(ref: String): Int = ref.takeWhile { it.isLetter() }.uppercase().fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1

    private fun cleanNumber(v: String): String =
        v.toDoubleOrNull()?.let { d -> if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString() } ?: v

    private fun trimTable(rows: List<List<String>>): List<List<String>> {
        val nonEmpty = rows.dropLastWhile { r -> r.all { it.isEmpty() } }
        val width = nonEmpty.maxOfOrNull { r -> r.indexOfLast { it.isNotEmpty() } + 1 } ?: 0
        return nonEmpty.map { r -> (0 until width).map { r.getOrElse(it) { "" } } }
    }

    // ---------------------------------------------------------------- CSV

    fun parseCsv(text: String): List<List<String>> {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val sep = listOf(',', ';', '\t').maxByOrNull { c -> firstLine.count { it == c } } ?: ','
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                ch == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
                !quoted && ch == sep -> { row += cell.toString(); cell.setLength(0) }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += cell.toString(); cell.setLength(0); rows += row; row = mutableListOf()
                }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return trimTable(rows)
    }

    // ---------------------------------------------------------------- RTF

    /** Texto de um RTF: ignora tabelas de fonte/cor/estilo, imagens e campos ocultos. */
    fun readRtf(rtf: String): String {
        val out = StringBuilder()
        val skipStack = ArrayDeque<Boolean>()
        var skip = false
        var ucSkip = 1
        var pendingSkip = 0
        var i = 0
        val destinations = setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "headerl",
            "headerr", "footerl", "footerr", "listtable", "listoverridetable", "rsidtbl", "generator", "xmlnstbl", "themedata",
            "colorschememapping", "latentstyles", "datastore", "fldinst", "object", "filetbl", "revtbl")
        while (i < rtf.length) {
            val ch = rtf[i]
            when (ch) {
                '{' -> { skipStack.addLast(skip); i++ }
                '}' -> { skip = skipStack.removeLastOrNull() ?: false; i++ }
                '\\' -> {
                    val next = rtf.getOrNull(i + 1) ?: break
                    when {
                        next == '\\' || next == '{' || next == '}' -> { if (!skip) out.append(next); i += 2 }
                        next == '*' -> { skip = true; i += 2 }
                        next == '\'' -> {
                            val hex = rtf.substring(i + 2, minOf(i + 4, rtf.length))
                            if (pendingSkip > 0) pendingSkip-- else if (!skip) hex.toIntOrNull(16)?.let { out.append(String(byteArrayOf(it.toByte()), Charset.forName("windows-1252"))) }
                            i += 4
                        }
                        next == '~' -> { if (!skip) out.append('\u00A0'); i += 2 }
                        next == '\n' || next == '\r' -> { if (!skip) out.append('\n'); i += 2 }
                        next.isLetter() -> {
                            var j = i + 1
                            while (j < rtf.length && rtf[j].isLetter()) j++
                            val word = rtf.substring(i + 1, j)
                            var k = j
                            if (k < rtf.length && (rtf[k] == '-' || rtf[k].isDigit())) { k++; while (k < rtf.length && rtf[k].isDigit()) k++ }
                            val param = rtf.substring(j, k).toIntOrNull()
                            if (k < rtf.length && rtf[k] == ' ') k++
                            i = k
                            if (word in destinations) { skip = true; continue }
                            if (skip) continue
                            when (word) {
                                "par", "line", "sect", "page", "row" -> out.append('\n')
                                "tab", "cell" -> out.append('\t')
                                "uc" -> ucSkip = param ?: 1
                                "u" -> { param?.let { out.append((if (it < 0) it + 65536 else it).toChar()) }; pendingSkip = ucSkip }
                                "emdash" -> out.append('—'); "endash" -> out.append('–'); "bullet" -> out.append('•')
                                "lquote" -> out.append('‘'); "rquote" -> out.append('’'); "ldblquote" -> out.append('“'); "rdblquote" -> out.append('”')
                            }
                        }
                        else -> i += 2
                    }
                }
                '\r', '\n' -> i++
                else -> {
                    if (pendingSkip > 0) pendingSkip-- else if (!skip) out.append(ch)
                    i++
                }
            }
        }
        return out.toString().replace(Regex("\t\n"), "\n")
    }

    // ---------------------------------------------------------------- Markdown

    private fun readMarkdown(md: String): List<Block> {
        val out = mutableListOf<Block>()
        fun inline(node: Node): String = buildString {
            node.accept(object : AbstractVisitor() {
                override fun visit(text: Text) { append(text.literal) }
                override fun visit(code: Code) { append(code.literal) }
                override fun visit(softLineBreak: SoftLineBreak) { append(' ') }
                override fun visit(hardLineBreak: HardLineBreak) { append('\n') }
            })
        }
        fun block(node: Node, prefix: String) {
            var n = node.firstChild
            var number = (node as? OrderedList)?.markerStartNumber ?: 1
            while (n != null) {
                when (n) {
                    is Heading -> out += Block.Title(n.level, inline(n))
                    is Paragraph -> out += Block.Para(prefix + inline(n))
                    is FencedCodeBlock -> n.literal.trimEnd().lines().forEach { out += Block.Para(it) }
                    is IndentedCodeBlock -> n.literal.trimEnd().lines().forEach { out += Block.Para(it) }
                    is BulletList -> block(n, "")
                    is OrderedList -> block(n, "")
                    is ListItem -> block(n, if (node is OrderedList) "${number++}. " else "• ")
                    is BlockQuote -> block(n, "> ")
                    else -> Unit
                }
                n = n.next
            }
        }
        block(Parser.builder().build().parse(md), "")
        return out
    }

    // ================================================================ escrita

    fun write(blocks: List<Block>, output: File, options: ConvertOptions) {
        output.parentFile?.mkdirs()
        when (val ext = Formats.extensionOf(output.name)) {
            "txt" -> output.writeText(toText(blocks))
            "csv" -> output.writeText("\uFEFF" + toCsv(firstTable(blocks)))
            "rtf" -> output.writeText(toRtf(blocks), Charsets.US_ASCII)
            "docx" -> writeZip(output, docxParts(blocks))
            "odt" -> writeZip(output, odfParts(odtContent(blocks), "text"), odfMime = "application/vnd.oasis.opendocument.text")
            "xlsx" -> writeZip(output, xlsxParts(tables(blocks)))
            "ods" -> writeZip(output, odfParts(odsContent(tables(blocks)), "spreadsheet"), odfMime = "application/vnd.oasis.opendocument.spreadsheet")
            "pdf" -> TextPdf.fromHtml(toHtml(blocks), output, options)
            else -> throw ConversionException("Não sei gravar documentos em .$ext.")
        }
    }

    fun toText(blocks: List<Block>): String = buildString {
        blocks.forEach { b ->
            when (b) {
                is Block.Title -> { if (isNotEmpty()) append('\n'); append(b.text).append('\n') }
                is Block.Para -> append(b.text).append('\n')
                is Block.Table -> {
                    b.name?.let { append(it).append('\n') }
                    b.rows.forEach { append(it.joinToString("\t")).append('\n') }
                    append('\n')
                }
            }
        }
    }.trimEnd() + "\n"

    private fun firstTable(blocks: List<Block>): List<List<String>> =
        blocks.filterIsInstance<Block.Table>().firstOrNull()?.rows
            ?: blocks.mapNotNull { (it as? Block.Para)?.text ?: (it as? Block.Title)?.text }.map { listOf(it) }

    /** Tabelas para planilhas; um documento sem tabelas vira uma coluna com os parágrafos. */
    private fun tables(blocks: List<Block>): List<Block.Table> =
        blocks.filterIsInstance<Block.Table>().ifEmpty { listOf(Block.Table(firstTable(blocks))) }

    fun toCsv(rows: List<List<String>>): String = rows.joinToString("\r\n") { r ->
        r.joinToString(",") { c -> if (c.any { it == ',' || it == '"' || it == '\n' || it == '\r' || it == ';' }) "\"" + c.replace("\"", "\"\"") + "\"" else c }
    } + "\r\n"

    private fun toHtml(blocks: List<Block>): String = buildString {
        blocks.forEach { b ->
            when (b) {
                is Block.Title -> append("<h${b.level}>").append(esc(b.text)).append("</h${b.level}>")
                is Block.Para -> append("<p>").append(esc(b.text).replace("\n", "<br>").ifEmpty { "&nbsp;" }).append("</p>")
                is Block.Table -> {
                    b.name?.let { append("<h3>").append(esc(it)).append("</h3>") }
                    append("<p>")
                    b.rows.forEach { r -> append(r.joinToString(" &nbsp;|&nbsp; ") { esc(it) }).append("<br>") }
                    append("</p>")
                }
            }
        }
    }

    // ---------------------------------------------------------------- RTF

    private fun toRtf(blocks: List<Block>): String = buildString {
        append("{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0\\fswiss Arial;}}\\f0\\fs22\n")
        blocks.forEach { b ->
            when (b) {
                is Block.Title -> append("{\\pard\\b\\fs").append(listOf(36, 32, 28, 26, 24, 22)[b.level - 1]).append(' ')
                    .append(rtfEsc(b.text)).append("\\par}\n")
                is Block.Para -> append("{\\pard ").append(rtfEsc(b.text)).append("\\par}\n")
                is Block.Table -> b.rows.forEach { r ->
                    append("\\trowd\\trgaph108")
                    r.indices.forEach { c -> append("\\clbrdrt\\brdrs\\clbrdrl\\brdrs\\clbrdrb\\brdrs\\clbrdrr\\brdrs\\cellx").append((c + 1) * 2000) }
                    append('\n')
                    r.forEach { append("\\pard\\intbl ").append(rtfEsc(it)).append("\\cell ") }
                    append("\\row\n")
                }
            }
        }
        append("}")
    }

    private fun rtfEsc(s: String): String = buildString {
        s.forEach { c ->
            when {
                c == '\\' || c == '{' || c == '}' -> append('\\').append(c)
                c == '\n' -> append("\\line ")
                c == '\t' -> append("\\tab ")
                c.code < 128 -> append(c)
                else -> append("\\u").append(c.code.toShort().toInt()).append('?')
            }
        }
    }

    // ---------------------------------------------------------------- DOCX

    private fun docxParts(blocks: List<Block>): List<Pair<String, String>> {
        val body = buildString {
            blocks.forEach { b ->
                when (b) {
                    is Block.Title -> append("<w:p><w:pPr><w:pStyle w:val=\"Heading${b.level}\"/></w:pPr>").append(docxRuns(b.text)).append("</w:p>")
                    is Block.Para -> append("<w:p>").append(docxRuns(b.text)).append("</w:p>")
                    is Block.Table -> {
                        if (b.rows.isEmpty()) return@forEach
                        b.name?.let { append("<w:p><w:pPr><w:pStyle w:val=\"Heading3\"/></w:pPr>").append(docxRuns(it)).append("</w:p>") }
                        append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr>")
                        b.rows.forEach { r ->
                            append("<w:tr>")
                            r.forEach { c -> append("<w:tc><w:p>").append(docxRuns(c)).append("</w:p></w:tc>") }
                            append("</w:tr>")
                        }
                        append("</w:tbl><w:p/>")
                    }
                }
            }
        }
        val headings = (1..6).joinToString("") { l ->
            val size = listOf(36, 32, 28, 26, 24, 22)[l - 1]
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading$l\"><w:name w:val=\"heading $l\"/><w:basedOn w:val=\"Normal\"/>" +
                "<w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"80\"/><w:outlineLvl w:val=\"${l - 1}\"/></w:pPr>" +
                "<w:rPr><w:b/><w:sz w:val=\"$size\"/></w:rPr></w:style>"
        }
        return listOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/_rels/document.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""",
            "word/styles.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:lang w:val="pt-BR"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>$headings<w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/><w:tblPr><w:tblBorders><w:top w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:left w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:bottom w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:right w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:insideH w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:insideV w:val="single" w:sz="4" w:space="0" w:color="auto"/></w:tblBorders></w:tblPr></w:style></w:styles>""",
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1417" w:right="1701" w:bottom="1417" w:left="1701" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>""",
        )
    }

    private fun docxRuns(text: String): String = if (text.isEmpty()) "" else buildString {
        append("<w:r>")
        text.split('\n').forEachIndexed { i, line ->
            if (i > 0) append("<w:br/>")
            line.split('\t').forEachIndexed { j, part ->
                if (j > 0) append("<w:tab/>")
                if (part.isNotEmpty()) append("<w:t xml:space=\"preserve\">").append(esc(part)).append("</w:t>")
            }
        }
        append("</w:r>")
    }

    // ---------------------------------------------------------------- ODT/ODS

    private const val ODF_NS = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" " +
        "xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\" xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" " +
        "xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\" office:version=\"1.3\""

    private fun odtContent(blocks: List<Block>): String {
        var tableNo = 0
        val body = buildString {
            blocks.forEach { b ->
                when (b) {
                    is Block.Title -> append("<text:h text:style-name=\"Heading_20_${b.level}\" text:outline-level=\"${b.level}\">").append(odfInline(b.text)).append("</text:h>")
                    is Block.Para -> append("<text:p>").append(odfInline(b.text)).append("</text:p>")
                    is Block.Table -> {
                        if (b.rows.isEmpty()) return@forEach
                        val cols = b.rows.maxOf { it.size }
                        append("<table:table table:name=\"").append(esc(b.name ?: "Tabela${++tableNo}")).append("\">")
                        append("<table:table-column table:number-columns-repeated=\"$cols\"/>")
                        b.rows.forEach { r ->
                            append("<table:table-row>")
                            (0 until cols).forEach { c -> append("<table:table-cell office:value-type=\"string\"><text:p>").append(odfInline(r.getOrElse(c) { "" })).append("</text:p></table:table-cell>") }
                            append("</table:table-row>")
                        }
                        append("</table:table>")
                    }
                }
            }
        }
        return """<?xml version="1.0" encoding="UTF-8"?><office:document-content $ODF_NS><office:body><office:text>$body</office:text></office:body></office:document-content>"""
    }

    private fun odsContent(tables: List<Block.Table>): String {
        val body = buildString {
            tables.forEachIndexed { i, t ->
                append("<table:table table:name=\"").append(esc(t.name ?: "Planilha${i + 1}")).append("\">")
                append("<table:table-column table:number-columns-repeated=\"${t.rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1}\"/>")
                t.rows.forEach { r ->
                    append("<table:table-row>")
                    r.forEach { c ->
                        val num = c.toDoubleOrNull()?.takeIf { NUMBER.matches(c) }
                        if (num != null) append("<table:table-cell office:value-type=\"float\" office:value=\"$c\"><text:p>$c</text:p></table:table-cell>")
                        else append("<table:table-cell office:value-type=\"string\"><text:p>").append(odfInline(c)).append("</text:p></table:table-cell>")
                    }
                    append("</table:table-row>")
                }
                append("</table:table>")
            }
        }
        return """<?xml version="1.0" encoding="UTF-8"?><office:document-content $ODF_NS><office:body><office:spreadsheet>$body</office:spreadsheet></office:body></office:document-content>"""
    }

    private fun odfInline(text: String): String = esc(text)
        .replace("\n", "<text:line-break/>").replace("\t", "<text:tab/>")
        .replace(Regex(" {2,}")) { m -> " <text:s text:c=\"${m.value.length - 1}\"/>" }

    private fun odfParts(content: String, kind: String): List<Pair<String, String>> {
        val headings = (1..6).joinToString("") { l ->
            val size = listOf(18, 16, 14, 13, 12, 11)[l - 1]
            "<style:style style:name=\"Heading_20_$l\" style:display-name=\"Heading $l\" style:family=\"paragraph\" style:default-outline-level=\"$l\">" +
                "<style:paragraph-properties fo:margin-top=\"0.42cm\" fo:margin-bottom=\"0.14cm\" fo:keep-with-next=\"always\"/>" +
                "<style:text-properties fo:font-size=\"${size}pt\" fo:font-weight=\"bold\"/></style:style>"
        }
        val mime = "application/vnd.oasis.opendocument.$kind"
        return listOf(
            "META-INF/manifest.xml" to """<?xml version="1.0" encoding="UTF-8"?><manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.3"><manifest:file-entry manifest:full-path="/" manifest:media-type="$mime"/><manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/><manifest:file-entry manifest:full-path="styles.xml" manifest:media-type="text/xml"/></manifest:manifest>""",
            "styles.xml" to """<?xml version="1.0" encoding="UTF-8"?><office:document-styles $ODF_NS><office:styles><style:default-style style:family="paragraph"><style:paragraph-properties fo:margin-bottom="0.21cm"/><style:text-properties fo:font-size="11pt" fo:language="pt" fo:country="BR"/></style:default-style>$headings</office:styles></office:document-styles>""",
            "content.xml" to content,
        )
    }

    // ---------------------------------------------------------------- XLSX

    private fun xlsxParts(tables: List<Block.Table>): List<Pair<String, String>> {
        val names = mutableSetOf<String>()
        val sheetNames = tables.mapIndexed { i, t ->
            // nome de aba: até 31 caracteres, sem : \ / ? * [ ], único
            var n = (t.name ?: "Planilha${i + 1}").replace(Regex("""[:\\/?*\[\]]"""), " ").take(31).ifBlank { "Planilha${i + 1}" }
            var k = 2
            while (!names.add(n.lowercase())) n = n.take(28) + " ${k++}"
            n
        }
        val sheets = tables.mapIndexed { i, t ->
            val rows = t.rows.mapIndexed { r, row ->
                "<row r=\"${r + 1}\">" + row.mapIndexedNotNull { c, v ->
                    if (v.isEmpty()) null
                    else if (NUMBER.matches(v)) "<c r=\"${columnName(c)}${r + 1}\"><v>$v</v></c>"
                    else "<c r=\"${columnName(c)}${r + 1}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>"
                }.joinToString("") + "</row>"
            }.joinToString("")
            "xl/worksheets/sheet${i + 1}.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$rows</sheetData></worksheet>"""
        }
        val overrides = tables.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" }
        val sheetEls = sheetNames.mapIndexed { i, n -> "<sheet name=\"${esc(n)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("")
        val rels = tables.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" }
        return listOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>$overrides</Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>$sheetEls</sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$rels</Relationships>""",
        ) + sheets
    }

    /** 0 → "A", 27 → "AB". */
    fun columnName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.append('A' + r); n = (n - 1) / 26 }
        return sb.reverse().toString()
    }

    private val NUMBER = Regex("""^-?(0|[1-9]\d{0,14})(\.\d+)?$""")

    // ================================================================ utilidades

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")

    private fun invalid(file: File) = ConversionException("\"${file.name}\" não é um arquivo válido (corrompido ou de outro formato).")

    private class Zip(private val zip: ZipFile) {
        val names: List<String> = zip.entries().toList().map { it.name }
        fun bytes(name: String): ByteArray? = zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
    }

    private fun <T> zip(file: File, block: (Zip) -> T): T {
        val z = try {
            ZipFile(file)
        } catch (e: Exception) {
            throw ConversionException("\"${file.name}\" não é um arquivo válido (corrompido, protegido por senha ou de outro formato).", e)
        }
        return z.use { block(Zip(it)) }
    }

    private fun writeZip(output: File, parts: List<Pair<String, String>>, odfMime: String? = null) {
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            // ODF: "mimetype" tem que ser a primeira entrada, sem compressão
            if (odfMime != null) {
                val bytes = odfMime.toByteArray(Charsets.US_ASCII)
                zip.putNextEntry(ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                    crc = CRC32().apply { update(bytes) }.value
                })
                zip.write(bytes)
                zip.closeEntry()
            }
            parts.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun xml(bytes: ByteArray?): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        // sem DTD/entidades externas (XXE)
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes ?: ByteArray(0))).documentElement
    }

    private fun Element.kids(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private fun Element.kids(local: String) = kids().filter { it.localName == local }
    private fun Element.first(local: String) = kids().firstOrNull { it.localName == local }
    private fun Element.all(local: String): List<Element> {
        val list = getElementsByTagNameNS("*", local)
        return (0 until list.length).map { list.item(it) as Element }
    }

    /** Atributo pelo nome local, com qualquer namespace (w:val, text:outline-level...). */
    private fun Element.attr(local: String): String? {
        for (i in 0 until attributes.length) {
            val a = attributes.item(i)
            if ((a.localName ?: a.nodeName.substringAfter(':')) == local) return a.nodeValue
        }
        return null
    }

    /** r:id do workbook (namespace de relationships). */
    private fun Element.attrNs(local: String): String? {
        for (i in 0 until attributes.length) {
            val a = attributes.item(i)
            if ((a.localName ?: a.nodeName.substringAfter(':')) == local && (a.namespaceURI?.contains("relationships") == true || a.nodeName.startsWith("r:"))) return a.nodeValue
        }
        return null
    }

}
