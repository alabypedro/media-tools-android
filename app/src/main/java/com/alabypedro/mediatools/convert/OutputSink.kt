package com.alabypedro.mediatools.convert

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.alabypedro.mediatools.download.MediaStoreSaver
import com.alabypedro.mediatools.download.SavedFile
import java.io.File

/**
 * Para onde vão os convertidos ("Salvar em" do desktop): por padrão
 * Download/Universal Media Tools/Convertidos (MediaStore, sem permissão);
 * ou uma pasta escolhida pelo usuário (Storage Access Framework).
 */
class OutputSink(private val context: Context) {

    /** Grava [file] no destino. Devolve null se a política for "pular" e o arquivo já existir. */
    fun save(file: File, treeUri: String?, subfolder: String, conflict: ConflictPolicy): SavedFile? =
        if (treeUri == null) saveMediaStore(file, subfolder, conflict) else saveTree(file, Uri.parse(treeUri), subfolder, conflict)

    private fun saveMediaStore(file: File, subfolder: String, conflict: ConflictPolicy): SavedFile? {
        val relative = buildString {
            append(Environment.DIRECTORY_DOWNLOADS).append('/').append(MediaStoreSaver.FOLDER).append("/").append(DEFAULT_SUBFOLDER)
            if (subfolder.isNotEmpty()) append('/').append(subfolder)
        } + "/"
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        if (conflict != ConflictPolicy.RENAME) {
            // só enxerga os arquivos criados pelo próprio app (suficiente: são os convertidos anteriores)
            val existing = resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(file.name, relative), null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            if (existing != null) {
                if (conflict == ConflictPolicy.SKIP) return null
                resolver.delete(ContentUris.withAppendedId(collection, existing), null, null)
            }
        }
        val mime = mimeOf(file.name)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw ConversionException("Não foi possível criar ${file.name} em Downloads.")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw ConversionException("Falha ao salvar ${file.name}: ${e.message}", e)
        }
        val finalName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: file.name
        return SavedFile(uri.toString(), finalName, mime, file.length())
    }

    private fun saveTree(file: File, tree: Uri, subfolder: String, conflict: ConflictPolicy): SavedFile? {
        var dir = DocumentFile.fromTreeUri(context, tree)?.takeIf { it.canWrite() }
            ?: throw ConversionException("Sem permissão para gravar na pasta escolhida. Escolha a pasta de novo em \"Salvar em\".")
        subfolder.split('/').filter { it.isNotEmpty() }.forEach { part ->
            dir = dir.findFile(part)?.takeIf { it.isDirectory } ?: dir.createDirectory(part)
                ?: throw ConversionException("Não foi possível criar a pasta \"$part\".")
        }
        var name = file.name
        dir.findFile(name)?.let { existing ->
            when (conflict) {
                ConflictPolicy.SKIP -> return null
                ConflictPolicy.OVERWRITE -> existing.delete()
                ConflictPolicy.RENAME -> name = uniqueName(name) { dir.findFile(it) != null }
            }
        }
        val mime = mimeOf(name)
        val doc = dir.createFile(mime, name) ?: throw ConversionException("Não foi possível criar \"$name\" na pasta escolhida.")
        try {
            context.contentResolver.openOutputStream(doc.uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        } catch (e: Exception) {
            doc.delete()
            throw ConversionException("Falha ao salvar $name: ${e.message}", e)
        }
        return SavedFile(doc.uri.toString(), doc.name ?: name, mime, file.length())
    }

    companion object {
        const val DEFAULT_SUBFOLDER = "Convertidos"

        fun mimeOf(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"

        /** "foto.jpg" → "foto (1).jpg", "foto (2).jpg"... (como o desktop). */
        fun uniqueName(name: String, exists: (String) -> Boolean): String {
            val ext = Formats.extensionOf(name)
            val stem = Formats.stemOf(name)
            var n = 1
            while (true) {
                val candidate = if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext"
                if (!exists(candidate)) return candidate
                n++
            }
        }
    }
}
