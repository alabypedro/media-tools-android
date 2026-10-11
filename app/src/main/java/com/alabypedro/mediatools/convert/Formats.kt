package com.alabypedro.mediatools.convert

/**
 * Registro central de formatos e regras de conversão: o mesmo modelo do
 * desktop (umd/convert/core/registry.py). Cada regra diz "estas extensões
 * de origem podem virar estas de destino, com este conversor"; a primeira
 * regra que bate é usada, por isso as mais específicas vêm antes.
 *
 * Diferença do desktop: no celular não existe LibreOffice. Documentos,
 * planilhas e apresentações são lidos e escritos pelo próprio app
 * ([OfficeConverter]): texto, títulos e tabelas são preservados, a
 * formatação visual não. Os formatos binários antigos (.doc, .xls, .ppt)
 * são reconhecidos, mas não têm conversão.
 */
data class Category(val key: String, val label: String, val extensions: Set<String>)

enum class Handler { PDF_TO_IMAGE, PDF_FLATTEN, IMAGE_TO_PDF, TEXT_TO_PDF, MARKDOWN_TO_PDF, MEDIA, IMAGE, ARCHIVE, OFFICE }

data class Rule(val sources: Set<String>, val targets: Set<String>, val handler: Handler)

object Formats {
    val VIDEO = Category("video", "Vídeo", setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "mpg", "mpeg", "3gp", "ts"))
    val AUDIO = Category("audio", "Áudio", setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus", "aiff"))
    val IMAGE = Category("image", "Imagem", setOf("jpg", "jpeg", "png", "bmp", "gif", "webp", "tiff", "tif", "ico", "avif", "heic", "heif"))
    val DOCUMENT = Category("document", "Documento", setOf("doc", "docx", "odt", "rtf"))
    val SPREADSHEET = Category("spreadsheet", "Planilha", setOf("xls", "xlsx", "ods", "csv"))
    val PRESENTATION = Category("presentation", "Apresentação", setOf("ppt", "pptx", "odp"))
    val TEXT = Category("text", "Texto", setOf("txt"))
    val MARKDOWN = Category("markdown", "Markdown", setOf("md", "markdown"))
    val PDF = Category("pdf", "PDF", setOf("pdf"))
    val ARCHIVE = Category("archive", "Compactado", setOf("zip", "7z", "tar", "tar.gz", "tgz", "tar.bz2", "tbz2", "tar.xz", "txz"))

    val ALL = listOf(VIDEO, AUDIO, IMAGE, DOCUMENT, SPREADSHEET, PRESENTATION, TEXT, MARKDOWN, PDF, ARCHIVE)

    /** Formatos binários do Office 97-2003: só o LibreOffice abre direito. */
    val LEGACY_OFFICE = setOf("doc", "xls", "ppt")

    /** PDF → o mesmo PDF achatado; gera "<nome> (achatado).pdf". */
    const val FLATTEN = "flatten"
    const val FLATTEN_SUFFIX = " (achatado)"

    // Documentos que viram texto corrido (títulos, parágrafos e tabelas)...
    private val TEXT_SOURCES = setOf("docx", "odt", "rtf", "txt", "md", "markdown", "pptx", "odp", "pdf")
    private val TEXT_TARGETS = setOf("docx", "odt", "rtf", "txt", "pdf")
    // ...e planilhas, que além disso podem virar outras planilhas
    private val SHEET_SOURCES = setOf("xlsx", "ods", "csv")
    private val SHEET_TARGETS = setOf("xlsx", "ods", "csv") + TEXT_TARGETS

    // Ordem importa: a primeira regra cujo (origem, destino) bate é usada.
    val RULES = listOf(
        Rule(PDF.extensions, setOf("png", "jpg", "jpeg", "webp"), Handler.PDF_TO_IMAGE),
        Rule(PDF.extensions, setOf(FLATTEN), Handler.PDF_FLATTEN),
        Rule(IMAGE.extensions, setOf("pdf"), Handler.IMAGE_TO_PDF),
        Rule(TEXT.extensions, setOf("pdf"), Handler.TEXT_TO_PDF),
        Rule(MARKDOWN.extensions, setOf("pdf"), Handler.MARKDOWN_TO_PDF),
        // vídeo pode virar áudio (extrai a trilha) ou outro vídeo; áudio só vira áudio
        Rule(VIDEO.extensions, VIDEO.extensions + AUDIO.extensions, Handler.MEDIA),
        Rule(AUDIO.extensions, AUDIO.extensions, Handler.MEDIA),
        Rule(IMAGE.extensions, IMAGE.extensions, Handler.IMAGE),
        Rule(ARCHIVE.extensions, ARCHIVE.extensions, Handler.ARCHIVE),
        Rule(SHEET_SOURCES, SHEET_TARGETS, Handler.OFFICE),
        Rule(TEXT_SOURCES, TEXT_TARGETS, Handler.OFFICE),
    )

    /** Extensão do arquivo, reconhecendo as compostas (arquivo.tar.gz → "tar.gz"). */
    fun extensionOf(name: String): String {
        val lower = name.lowercase()
        ARCHIVE.extensions.filter { '.' in it }.sortedByDescending { it.length }
            .firstOrNull { lower.endsWith(".$it") }?.let { return it }
        return lower.substringAfterLast('.', "")
    }

    /** Nome sem a extensão (inclusive as compostas). */
    fun stemOf(name: String): String {
        val ext = extensionOf(name)
        return if (ext.isEmpty()) name else name.dropLast(ext.length + 1)
    }

    fun categoryOf(ext: String): Category? = ALL.firstOrNull { ext in it.extensions }

    fun resolve(source: String, target: String): Handler? =
        RULES.firstOrNull { source in it.sources && target in it.targets }?.handler

    /** Destinos com sentido para essa origem (preenche "Converter para"). */
    fun targetsFor(source: String): Set<String> =
        RULES.filter { source in it.sources }.flatMap { it.targets }.toSet() - source -
            // jpg ↔ jpeg e tif ↔ tiff são o mesmo formato
            setOfNotNull(mapOf("jpg" to "jpeg", "jpeg" to "jpg", "tif" to "tiff", "tiff" to "tif")[source])

    /** Destinos em comum a todos os arquivos da fila (como no desktop). */
    fun commonTargets(sources: Collection<String>): List<String> {
        if (sources.isEmpty()) return emptyList()
        return sources.map(::targetsFor).reduce { acc, s -> acc intersect s }.sortedWith(TARGET_ORDER)
    }

    fun targetLabel(ext: String) = if (ext == FLATTEN) "PDF achatado" else ext.uppercase()

    /** Mensagem para um arquivo que não tem nenhum destino. */
    fun unsupportedReason(ext: String): String = when (ext) {
        in LEGACY_OFFICE -> "Formato antigo do Office (.$ext): no celular não há LibreOffice. " +
            "Salve o arquivo como .${ext}x (ou .odt/.ods) e converta de novo."
        "" -> "Arquivo sem extensão: não dá para saber o formato."
        else -> "Formato .$ext não é suportado."
    }

    fun describe(): String = buildString {
        ALL.forEach { appendLine("${it.label}: ${it.extensions.sorted().joinToString(", ")}") }
        appendLine()
        appendLine("PDF achatado: campos de formulário e anotações viram conteúdo fixo (o PDF deixa de ser editável). Gera \"nome (achatado).pdf\".")
        appendLine()
        appendLine("Documentos, planilhas e apresentações: no celular não há LibreOffice. O app converte entre DOCX, ODT, RTF, TXT, PDF, XLSX, ODS e CSV preservando texto, títulos e tabelas; a formatação visual (fontes, cores, imagens) não é mantida. Apresentações (PPTX/ODP) viram texto ou PDF. Os formatos antigos .doc, .xls e .ppt não são suportados.")
    }.trim()

    // Formatos mais usados primeiro no menu (o resto em ordem alfabética)
    private val PREFERRED = listOf("mp4", "mp3", "jpg", "png", "pdf", "docx", "xlsx", "zip", "webp", "m4a", "txt", "csv")
    private val TARGET_ORDER = compareBy<String>({ PREFERRED.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it })
}
