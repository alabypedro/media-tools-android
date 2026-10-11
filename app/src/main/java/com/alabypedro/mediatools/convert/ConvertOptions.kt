package com.alabypedro.mediatools.convert

/** Erro já traduzido para o usuário (como ConversionError no desktop). */
class ConversionException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ConversionCanceled : Exception("Conversão cancelada.")

enum class ConflictPolicy(val label: String) {
    RENAME("Criar cópia: \"nome (1)\""),
    OVERWRITE("Substituir"),
    SKIP("Pular"),
}

enum class PdfFont(val label: String) { SANS("Sem serifa (Helvetica)"), SERIF("Com serifa (Times)"), MONO("Monoespaçada (Courier)") }

/** "Opções avançadas" + padrões das "Configurações de conversão". */
data class ConvertOptions(
    // PDF → imagem
    val pages: String = "",
    val dpi: Int = 200,
    // imagens e PDF → imagem (JPG/WEBP/AVIF)
    val quality: Int = 90,
    val keepMetadata: Boolean = true,
    // Texto/Markdown → PDF (e documentos → PDF)
    val font: PdfFont = PdfFont.SANS,
    val fontSize: Int = 11,
    val marginMm: Float = 20f,
    // arquivo de saída já existe
    val conflict: ConflictPolicy = ConflictPolicy.RENAME,
    /** Ao adicionar uma pasta, recria as subpastas no destino. */
    val preserveFolders: Boolean = true,
)

/** "1-5,10,20-25" → índices 0-based válidos, em ordem; vazio = todas. */
fun pageRangeToIndices(spec: String, total: Int): List<Int> {
    if (spec.isBlank()) return (0 until total).toList()
    val out = sortedSetOf<Int>()
    spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
        val bounds = part.split('-').map { it.trim() }
        val start = bounds[0].toIntOrNull() ?: throw ConversionException("Intervalo de páginas inválido: \"$part\"")
        val end = if (bounds.size > 1) (bounds[1].toIntOrNull() ?: total) else start
        for (p in start..minOf(end, total)) if (p >= 1) out += p - 1
    }
    return out.toList()
}
