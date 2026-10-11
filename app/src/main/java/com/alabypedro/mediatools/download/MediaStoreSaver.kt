package com.alabypedro.mediatools.download

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.alabypedro.mediatools.engine.Engine.Companion.isTemporary
import java.io.File

data class SavedFile(val uri: String, val name: String, val mime: String, val size: Long)

/**
 * Copia os arquivos baixados (na pasta temporária do app) para
 * Download/Universal Media Tools pelo MediaStore: aparecem no app Arquivos,
 * na galeria e nos players sem pedir permissão de armazenamento.
 */
object MediaStoreSaver {
    const val FOLDER = "Universal Media Tools"

    fun saveAll(context: Context, dir: File): List<SavedFile> =
        dir.walkTopDown()
            .filter { it.isFile && !it.isTemporary() }
            .sortedBy { it.path }
            .map { file ->
                val sub = file.parentFile!!.relativeTo(dir).path.replace(File.separatorChar, '/')
                save(context, file, sub)
            }
            .toList()

    private fun save(context: Context, file: File, subfolder: String): SavedFile {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"
        val relative = buildString {
            append(Environment.DIRECTORY_DOWNLOADS).append('/').append(FOLDER)
            if (subfolder.isNotEmpty()) append('/').append(subfolder)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Não foi possível criar ${file.name} em Downloads")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        // O MediaStore pode renomear (ex.: "nome (1).mp4" se já existir): lê o nome final.
        val finalName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: file.name
        return SavedFile(uri.toString(), finalName, mime, file.length())
    }
}
