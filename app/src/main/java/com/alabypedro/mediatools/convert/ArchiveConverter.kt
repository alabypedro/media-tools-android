package com.alabypedro.mediatools.convert

import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Converte entre formatos de arquivo compactado: extrai para uma pasta
 * temporária e compacta de novo (desktop: converters/archive.py).
 * Cada entrada é validada contra a pasta de destino ("zip slip").
 */
object ArchiveConverter {

    fun convert(input: File, output: File, work: File, isCanceled: () -> Boolean) {
        val src = Formats.extensionOf(input.name)
        val dst = Formats.extensionOf(output.name)
        val tmp = File(work, "archive").apply { deleteRecursively(); mkdirs() }
        try {
            extract(input, src, tmp)
        } catch (e: ConversionException) {
            throw e
        } catch (e: Exception) {
            throw ConversionException("\"${input.name}\" não é um arquivo compactado válido (ou está corrompido/protegido por senha).", e)
        }
        if (isCanceled()) throw ConversionCanceled()
        output.parentFile?.mkdirs()
        pack(tmp, dst, output)
    }

    private fun extract(input: File, kind: String, dest: File) {
        when (kind) {
            "zip" -> ZipFile.builder().setFile(input).get().use { zip ->
                for (entry in zip.entries) {
                    val target = safe(dest, entry.name)
                    if (entry.isDirectory) target.mkdirs()
                    else { target.parentFile?.mkdirs(); zip.getInputStream(entry).use { copy(it, target) } }
                }
            }
            "7z" -> SevenZFile.builder().setFile(input).get().use { z ->
                var entry = z.nextEntry
                while (entry != null) {
                    val target = safe(dest, entry.name)
                    if (entry.isDirectory) target.mkdirs()
                    else { target.parentFile?.mkdirs(); z.getInputStream(entry).use { copy(it, target) } }
                    entry = z.nextEntry
                }
            }
            else -> input.inputStream().buffered().use { raw ->
                val stream: InputStream = when (kind) {
                    "tar.gz", "tgz" -> GzipCompressorInputStream(raw, true)
                    "tar.bz2", "tbz2" -> BZip2CompressorInputStream(raw, true)
                    "tar.xz", "txz" -> XZCompressorInputStream(raw, true)
                    else -> raw
                }
                TarArchiveInputStream(stream).use { tar -> extractStream(tar, dest) }
            }
        }
    }

    private fun extractStream(input: ArchiveInputStream<TarArchiveEntry>, dest: File) {
        var entry = input.nextEntry
        while (entry != null) {
            // links simbólicos/dispositivos são ignorados (filter="data" no desktop)
            if (entry.isFile || entry.isDirectory) {
                val target = safe(dest, entry.name)
                if (entry.isDirectory) target.mkdirs()
                else { target.parentFile?.mkdirs(); copy(input, target) }
            }
            entry = input.nextEntry
        }
    }

    private fun pack(srcDir: File, kind: String, output: File) {
        val files = srcDir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
        fun rel(f: File) = f.relativeTo(srcDir).path.replace(File.separatorChar, '/')
        when (kind) {
            "zip" -> ZipArchiveOutputStream(output).use { zip ->
                files.forEach { f ->
                    zip.putArchiveEntry(ZipArchiveEntry(f, rel(f)))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeArchiveEntry()
                }
            }
            "7z" -> SevenZOutputFile(output).use { z ->
                files.forEach { f ->
                    z.putArchiveEntry(z.createArchiveEntry(f, rel(f)))
                    f.inputStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) { val n = input.read(buf); if (n < 0) break; z.write(buf, 0, n) }
                    }
                    z.closeArchiveEntry()
                }
            }
            else -> output.outputStream().buffered().use { raw ->
                val stream: OutputStream = when (kind) {
                    "tar.gz", "tgz" -> GzipCompressorOutputStream(raw)
                    "tar.bz2", "tbz2" -> BZip2CompressorOutputStream(raw)
                    "tar.xz", "txz" -> XZCompressorOutputStream(raw)
                    else -> raw
                }
                TarArchiveOutputStream(stream).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                    files.forEach { f ->
                        tar.putArchiveEntry(TarArchiveEntry(f, rel(f)))
                        f.inputStream().use { it.copyTo(tar) }
                        tar.closeArchiveEntry()
                    }
                }
            }
        }
    }

    /** Recusa entradas como "../../algo" ou caminhos absolutos. */
    fun safe(dest: File, name: String): File {
        val target = File(dest, name)
        val root = dest.canonicalPath + File.separator
        if (!target.canonicalPath.startsWith(root) && target.canonicalPath != dest.canonicalPath) {
            throw ConversionException("O arquivo compactado contém caminhos suspeitos (\"$name\") e foi rejeitado por segurança.")
        }
        return target
    }

    private fun copy(input: InputStream, target: File) = target.outputStream().use { input.copyTo(it) }
}
