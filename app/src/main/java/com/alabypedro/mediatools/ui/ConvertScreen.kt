package com.alabypedro.mediatools.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alabypedro.mediatools.convert.ConflictPolicy
import com.alabypedro.mediatools.convert.ConvStatus
import com.alabypedro.mediatools.convert.ConvertEntry
import com.alabypedro.mediatools.convert.ConvertRepository
import com.alabypedro.mediatools.convert.FitMode
import com.alabypedro.mediatools.convert.Formats
import com.alabypedro.mediatools.convert.MergeOptions
import com.alabypedro.mediatools.convert.OutputSink
import com.alabypedro.mediatools.convert.PageSize
import com.alabypedro.mediatools.convert.PdfFont
import com.alabypedro.mediatools.download.MediaStoreSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConvertScreen(repo: ConvertRepository) {
    val state by repo.state.collectAsStateWithLifecycle()
    val settings by repo.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var mergeImages by remember { mutableStateOf<List<Uri>?>(null) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { keepReadPermission(context, it) }
        val rejected = repo.addUris(uris)
        if (rejected.isNotEmpty()) Toast.makeText(context, rejected.joinToString("\n"), Toast.LENGTH_LONG).show()
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        keepReadPermission(context, uri)
        val n = repo.addTree(uri)
        Toast.makeText(context, if (n == 0) "Nenhum arquivo convertível nessa pasta." else "$n arquivo(s) adicionados.", Toast.LENGTH_SHORT).show()
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { uris.forEach { keepReadPermission(context, it) }; mergeImages = uris }
    }
    val pickOutput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) repo.setTree(uri)
    }

    val targets = remember(state.entries) { repo.availableTargets() }
    var target by remember { mutableStateOf<String?>(null) }
    val chosen = target?.takeIf { it in targets }
        ?: settings.lastTarget?.takeIf { it in targets }
        ?: targets.firstOrNull()
    val waiting = state.entries.count { it.status == ConvStatus.WAITING && !it.isMerge }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    "Converta vídeos, áudios, imagens, PDFs, documentos e arquivos compactados. Os originais nunca são alterados.",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Mais opções") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Formatos suportados") }, onClick = { menu = false; dialog = "formats" })
                        DropdownMenuItem(text = { Text("Histórico de conversões") }, onClick = { menu = false; dialog = "history" })
                        DropdownMenuItem(text = { Text("Configurações de conversão") }, onClick = { menu = false; dialog = "settings" })
                    }
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickFiles.launch(arrayOf("*/*")) }) { Text("Adicionar arquivos") }
                OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Adicionar pasta") }
                OutlinedButton(onClick = { pickImages.launch(arrayOf("image/*")) }) { Text("Juntar imagens em um PDF") }
            }
        }

        if (state.entries.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "A fila está vazia. Adicione arquivos, uma pasta, ou compartilhe arquivos de outro app (Arquivos, Galeria...) com o Universal Media Tools.",
                        Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${state.entries.size} na fila", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    if (state.entries.any { it.status != ConvStatus.WAITING && it.status != ConvStatus.RUNNING }) {
                        TextButton(onClick = repo::clearFinished) { Text("Remover concluídos") }
                    }
                    TextButton(onClick = repo::clear, enabled = !state.running) { Text("Limpar fila") }
                }
            }
            items(state.entries, key = { it.id }) { entry ->
                EntryCard(entry, chosen, onRemove = { repo.remove(setOf(entry.id)) })
            }
        }

        item { HorizontalDivider() }
        item { TargetPicker(targets, chosen, enabled = !state.running) { target = it } }
        if (state.entries.any { !it.isMerge } && targets.isEmpty()) {
            item {
                Text("Os arquivos da fila não têm um formato de saída em comum. Converta tipos diferentes separadamente.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Salvar em", style = MaterialTheme.typography.labelLarge)
                Text(
                    settings.treeLabel?.let { "Pasta escolhida: $it" }
                        ?: "Download/${MediaStoreSaver.FOLDER}/${OutputSink.DEFAULT_SUBFOLDER}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickOutput.launch(null) }) { Text("Escolher...") }
                    if (settings.treeUri != null) TextButton(onClick = { repo.setTree(null) }) { Text("Usar a pasta padrão") }
                    OutlinedButton(onClick = { dialog = "advanced" }) { Text("Opções avançadas...") }
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { chosen?.let(repo::start) },
                    enabled = !state.running && waiting > 0 && chosen != null,
                ) { Text(if (waiting > 1) "Converter ($waiting)" else "Converter") }
                OutlinedButton(onClick = repo::pause, enabled = state.running && !state.pausing) {
                    Text(if (state.pausing) "Pausando..." else "Pausar")
                }
                OutlinedButton(onClick = repo::cancel, enabled = state.running) {
                    Text("Cancelar", color = if (state.running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                }
            }
        }
        if (state.running) {
            item {
                val total = state.entries.count { it.target != null }
                val finished = state.entries.count { it.target != null && it.status != ConvStatus.WAITING && it.status != ConvStatus.RUNNING }
                val current = state.entries.firstOrNull { it.status == ConvStatus.RUNNING }
                val fraction = if (total == 0) 0f else (finished + (current?.progress?.coerceAtLeast(0f) ?: 0f)) / total
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text("${minOf(finished + 1, total)} de $total" + (current?.let { " · ${it.name}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    when (dialog) {
        "formats" -> InfoDialog("Formatos suportados", Formats.describe()) { dialog = null }
        "history" -> HistoryDialog(repo) { dialog = null }
        "settings" -> SettingsDialog(repo) { dialog = null }
        "advanced" -> AdvancedDialog(repo) { dialog = null }
    }
    mergeImages?.let { uris ->
        MergeDialog(context, uris, onDismiss = { mergeImages = null }) { ordered, name, options ->
            mergeImages = null
            repo.enqueueMerge(ordered, name, options)
        }
    }
}

/** Mantém o acesso ao arquivo escolhido até a conversão (a fila pode rodar depois). */
private fun keepReadPermission(context: Context, uri: Uri) {
    runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
}

@Composable
private fun EntryCard(entry: ConvertEntry, chosen: String?, onRemove: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumbnail(entry)
                Column(Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val type = if (entry.isMerge) "${entry.mergeSources.size} imagens → PDF"
                    else listOfNotNull(
                        Formats.categoryOf(entry.ext)?.label,
                        humanSize(entry.size),
                        entry.subfolder.takeIf { it.isNotEmpty() }?.let { "$it/" },
                    ).joinToString(" · ")
                    Text(type, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val dest = entry.target ?: chosen
                    if (entry.status == ConvStatus.WAITING && dest != null && !entry.isMerge) {
                        Text("→ ${destinationName(entry.name, dest)}", style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (entry.status != ConvStatus.RUNNING) {
                    IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Remover da fila") }
                }
            }
            when (entry.status) {
                ConvStatus.RUNNING -> {
                    if (entry.progress >= 0) LinearProgressIndicator(progress = { entry.progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(listOfNotNull(entry.message ?: "Convertendo", entry.progress.takeIf { it >= 0 }?.let { "${(it * 100).toInt()}%" })
                        .joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                ConvStatus.DONE -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✓ ${entry.message}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { shareFiles(context, entry.outputs) }) { Text("Compartilhar") }
                    TextButton(onClick = { openFile(context, entry.outputs.first()) }) { Text("Abrir") }
                }
                ConvStatus.FAILED -> Text(entry.message ?: "Erro", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                ConvStatus.CANCELED, ConvStatus.SKIPPED -> Text(entry.message ?: entry.status.label, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ConvStatus.WAITING -> Unit
            }
        }
    }
}

private fun destinationName(name: String, target: String): String {
    val stem = Formats.stemOf(name)
    return when {
        target == Formats.FLATTEN -> "$stem${Formats.FLATTEN_SUFFIX}.pdf"
        Formats.extensionOf(name) == "pdf" && target in setOf("png", "jpg", "jpeg", "webp") -> "${stem}_pagina_001.$target ..."
        else -> "$stem.$target"
    }
}

/** Prévia: miniatura do sistema (imagens, vídeos, PDFs) ou a extensão. */
@Composable
private fun Thumbnail(entry: ConvertEntry) {
    val context = LocalContext.current
    val uri = if (entry.isMerge) entry.mergeSources.first() else entry.uri
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(Uri.parse(uri), Size(160, 160), null) }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(8.dp)
    if (bitmap != null) {
        Image(bitmap!!.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(shape))
    } else {
        Box(Modifier.size(56.dp).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Text(if (entry.isMerge) "PDF" else entry.ext.uppercase().take(4), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetPicker(targets: List<String>, chosen: String?, enabled: Boolean, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled && targets.isNotEmpty()) expanded = it }) {
        OutlinedTextField(
            value = chosen?.let(Formats::targetLabel) ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = enabled && targets.isNotEmpty(),
            label = { Text("Converter para") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            targets.forEach { t ->
                DropdownMenuItem(text = { Text(Formats.targetLabel(t)) }, onClick = { onPick(t); expanded = false })
            }
        }
    }
}

@Composable
private fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
private fun HistoryDialog(repo: ConvertRepository, onDismiss: () -> Unit) {
    val history by repo.history.collectAsStateWithLifecycle()
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Histórico de conversões") },
        text = {
            if (history.isEmpty()) Text("Nenhuma conversão ainda.")
            else LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(history) { h ->
                    Column {
                        Text("${if (h.ok) "✓" else "✗"} ${h.source} → ${h.target}", style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${fmt.format(Date(h.time))} · ${h.message}", style = MaterialTheme.typography.bodySmall,
                            color = if (h.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        dismissButton = { if (history.isNotEmpty()) TextButton(onClick = repo::clearHistory) { Text("Limpar") } },
    )
}

/** "Opções avançadas" (desktop): PDF → imagem, qualidade, Texto → PDF e conflito de nomes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedDialog(repo: ConvertRepository, onDismiss: () -> Unit) {
    val settings by repo.settings.collectAsStateWithLifecycle()
    val current = remember { settings.options }
    var pages by remember { mutableStateOf(current.pages) }
    var dpi by remember { mutableStateOf(current.dpi.toString()) }
    var quality by remember { mutableFloatStateOf(current.quality.toFloat()) }
    var font by remember { mutableStateOf(current.font) }
    var fontSize by remember { mutableFloatStateOf(current.fontSize.toFloat()) }
    var margin by remember { mutableFloatStateOf(current.marginMm) }
    var conflict by remember { mutableStateOf(current.conflict) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Opções avançadas") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("PDF → imagem", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(pages, { pages = it }, label = { Text("Páginas") }, placeholder = { Text("todas (ex.: 1-5,10)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(dpi, { dpi = it.filter(Char::isDigit).take(4) }, label = { Text("Resolução (DPI)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Text("Imagens e PDF", style = MaterialTheme.typography.titleSmall)
                Text("Qualidade: ${quality.toInt()} (JPG, WEBP, AVIF, HEIC e PDF → imagem)", style = MaterialTheme.typography.bodySmall)
                Slider(quality, { quality = it }, valueRange = 1f..100f)
                Text("Texto/Markdown/documentos → PDF", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PdfFont.entries.forEach { f -> FilterChip(font == f, { font = f }, label = { Text(f.label) }) }
                }
                Text("Tamanho da fonte: ${fontSize.toInt()}", style = MaterialTheme.typography.bodySmall)
                Slider(fontSize, { fontSize = it }, valueRange = 6f..36f, steps = 29)
                Text("Margem: ${margin.toInt()} mm", style = MaterialTheme.typography.bodySmall)
                Slider(margin, { margin = it }, valueRange = 0f..60f)
                Text("Arquivo de saída já existe", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConflictPolicy.entries.forEach { c -> FilterChip(conflict == c, { conflict = c }, label = { Text(c.label) }) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                repo.updateSettings { s ->
                    s.copy(options = s.options.copy(
                        pages = pages.trim(), dpi = (dpi.toIntOrNull() ?: 200).coerceIn(36, 1200), quality = quality.toInt(),
                        font = font, fontSize = fontSize.toInt(), marginMm = margin, conflict = conflict,
                    ))
                }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** "Configurações de conversão" (desktop): o que vale para todas as filas. */
@Composable
private fun SettingsDialog(repo: ConvertRepository, onDismiss: () -> Unit) {
    val settings by repo.settings.collectAsStateWithLifecycle()
    val o = settings.options
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configurações de conversão") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SwitchRow("Manter as subpastas ao converter uma pasta", o.preserveFolders) { v ->
                    repo.updateSettings { it.copy(options = it.options.copy(preserveFolders = v)) }
                }
                SwitchRow("Manter metadados (EXIF: data, câmera, GPS) quando possível", o.keepMetadata) { v ->
                    repo.updateSettings { it.copy(options = it.options.copy(keepMetadata = v)) }
                }
                Text("Pasta de saída padrão: Download/${MediaStoreSaver.FOLDER}/${OutputSink.DEFAULT_SUBFOLDER}. " +
                    "Qualidade, DPI, fonte e conflito de nomes ficam em \"Opções avançadas\".",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}

/** "Juntar imagens em um PDF": ordem das páginas, tamanho, orientação, ajuste e margem. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MergeDialog(context: Context, initial: List<Uri>, onDismiss: () -> Unit, onConfirm: (List<Uri>, String, MergeOptions) -> Unit) {
    val images = remember { mutableStateListOf<Uri>().apply { addAll(initial) } }
    var name by remember { mutableStateOf("imagens") }
    var pageSize by remember { mutableStateOf(PageSize.A4) }
    var landscape by remember { mutableStateOf(false) }
    var fit by remember { mutableStateOf(FitMode.CONTAIN) }
    var margin by remember { mutableFloatStateOf(10f) }
    val names = remember(initial) {
        initial.associateWith { uri ->
            runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Juntar imagens em um PDF") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Uma página por imagem, nesta ordem:", style = MaterialTheme.typography.bodySmall)
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    images.forEachIndexed { i, uri ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}. ${names[uri]}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { if (i > 0) images.add(i - 1, images.removeAt(i)) }, enabled = i > 0,
                                modifier = Modifier.size(32.dp)) { Icon(Icons.Default.KeyboardArrowUp, "Subir") }
                            IconButton(onClick = { if (i < images.lastIndex) images.add(i + 1, images.removeAt(i)) }, enabled = i < images.lastIndex,
                                modifier = Modifier.size(32.dp)) { Icon(Icons.Default.KeyboardArrowDown, "Descer") }
                            IconButton(onClick = { images.removeAt(i) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Tirar") }
                        }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Nome do PDF") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Tamanho da página", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PageSize.entries.forEach { p -> FilterChip(pageSize == p, { pageSize = p }, label = { Text(p.label) }) }
                }
                if (pageSize != PageSize.ORIGINAL) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(!landscape, { landscape = false }, label = { Text("Retrato") })
                        FilterChip(landscape, { landscape = true }, label = { Text("Paisagem") })
                    }
                    Text("Ajuste da imagem", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FitMode.entries.forEach { f -> FilterChip(fit == f, { fit = f }, label = { Text(f.label) }) }
                    }
                    Text("Margem: ${margin.toInt()} mm", style = MaterialTheme.typography.bodySmall)
                    Slider(margin, { margin = it }, valueRange = 0f..40f)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = images.isNotEmpty(), onClick = {
                onConfirm(images.toList(), name, MergeOptions(pageSize, landscape, fit, margin))
            }) { Text("Gerar PDF") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
