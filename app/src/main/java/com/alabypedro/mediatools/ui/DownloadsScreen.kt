package com.alabypedro.mediatools.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.alabypedro.mediatools.download.DownloadJob
import com.alabypedro.mediatools.download.DownloadRepository
import com.alabypedro.mediatools.download.JobStatus
import com.alabypedro.mediatools.download.MediaStoreSaver
import com.alabypedro.mediatools.download.SavedFile

@Composable
fun DownloadsScreen(repo: DownloadRepository, onConvert: (List<SavedFile>) -> Unit) {
    val jobs by repo.jobs.collectAsStateWithLifecycle()
    val context = LocalContext.current

    if (jobs.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "Nenhum download ainda.\nOs arquivos vão para Download/${MediaStoreSaver.FOLDER}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (jobs.any { !it.isActive }) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Salvos em Download/${MediaStoreSaver.FOLDER}", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = repo::clearFinished) { Text("Limpar histórico") }
                }
            }
        }
        items(jobs, key = { it.id }) { job ->
            JobCard(
                job,
                onCancel = { repo.cancel(job.id) },
                onRetry = { repo.retry(job.id) },
                onRemove = { repo.remove(job.id) },
                onOpen = { openFile(context, it) },
                onShare = { shareFiles(context, job.files) },
                onConvert = { onConvert(job.files) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JobCard(
    job: DownloadJob,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onOpen: (SavedFile) -> Unit,
    onShare: () -> Unit,
    onConvert: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(
                    model = job.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(8.dp)),
                )
                Column(Modifier.weight(1f)) {
                    Text(job.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(job.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            when (job.status) {
                JobStatus.QUEUED, JobStatus.RUNNING -> {
                    if (job.progress >= 0) {
                        LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    val pct = if (job.progress >= 0) "${(job.progress * 100).toInt()}%" else null
                    Text(listOfNotNull(job.stage, pct, job.speed).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                JobStatus.DONE -> {
                    val size = humanSize(job.files.sumOf { it.size })
                    val what = if (job.files.size == 1) job.files[0].name else "${job.files.size} arquivos"
                    Text(listOfNotNull("✓ $what", size).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                JobStatus.FAILED -> Text(job.error ?: "Falhou", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                JobStatus.CANCELED -> Text("Cancelado", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                when (job.status) {
                    JobStatus.QUEUED, JobStatus.RUNNING -> TextButton(onClick = onCancel) { Text("Cancelar") }
                    JobStatus.DONE -> {
                        TextButton(onClick = onRemove) { Text("Remover") }
                        TextButton(onClick = onConvert) { Text("Converter") }
                        TextButton(onClick = onShare) { Text("Compartilhar") }
                        TextButton(onClick = { onOpen(job.files.first()) }) {
                            Text(if (job.files.size == 1) "Abrir" else "Abrir primeiro")
                        }
                    }
                    JobStatus.FAILED, JobStatus.CANCELED -> {
                        TextButton(onClick = onRemove) { Text("Remover") }
                        TextButton(onClick = onRetry) { Text("Tentar de novo") }
                    }
                }
            }
        }
    }
}

internal fun openFile(context: Context, file: SavedFile) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(file.uri), file.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, null))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Nenhum app abre esse tipo de arquivo.", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        // o arquivo foi apagado/movido por outro app
        Toast.makeText(context, "Arquivo não encontrado. Foi apagado?", Toast.LENGTH_SHORT).show()
    }
}

internal fun shareFiles(context: Context, files: List<SavedFile>) {
    if (files.isEmpty()) return
    val uris = ArrayList(files.map { Uri.parse(it.uri) })
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).setType(files[0].mime).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, null))
}
