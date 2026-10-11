package com.alabypedro.mediatools.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.alabypedro.mediatools.R
import com.alabypedro.mediatools.engine.EngineState
import com.alabypedro.mediatools.engine.MediaInfo
import com.alabypedro.mediatools.engine.Mode
import com.alabypedro.mediatools.engine.Selection
import com.alabypedro.mediatools.update.AppUpdater

@Composable
fun HomeScreen(vm: HomeViewModel, updater: AppUpdater, onQueued: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        UpdateBanner(updater)
        OutlinedTextField(
            value = state.url,
            onValueChange = vm::setUrl,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Link do vídeo, áudio ou playlist") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { focus.clearFocus(); vm.analyze() }),
            trailingIcon = {
                if (state.url.isEmpty()) {
                    IconButton(onClick = {
                        clipboard.getText()?.text?.let { text -> vm.setUrl(text); vm.analyze(text) }
                    }) { Icon(painterResource(R.drawable.ic_paste), contentDescription = "Colar") }
                } else {
                    IconButton(onClick = vm::clear) { Icon(Icons.Default.Clear, contentDescription = "Limpar") }
                }
            },
        )
        Button(
            onClick = { focus.clearFocus(); vm.analyze() },
            enabled = state.url.isNotBlank() && !state.analyzing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Analisar") }

        EngineBanner(engine)

        if (state.analyzing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Text("Analisando o link...")
            }
        }

        state.error?.let { ErrorCard(it) }

        state.info?.let { info ->
            MediaCard(info)
            Options(info, state.selection, vm::select)
            Button(
                onClick = { if (vm.download()) onQueued() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(painterResource(R.drawable.ic_download), contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (info.isPlaylist) "Baixar todos (${info.playlistCount})" else "Baixar")
            }
        }

        if (state.info == null && !state.analyzing && state.error == null) {
            Text(
                "Dica: no YouTube, Instagram, TikTok etc., toque em Compartilhar → Universal Media Tools.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EngineBanner(engine: EngineState) {
    val text = when (engine) {
        EngineState.Starting -> "Preparando o yt-dlp (na primeira abertura demora alguns segundos)..."
        EngineState.Updating -> "Atualizando o yt-dlp..."
        else -> null
    }
    AnimatedVisibility(text != null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text.orEmpty(), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    if (engine is EngineState.Failed) ErrorCard(engine.message)
}

@Composable
fun ErrorCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(
            message,
            Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun MediaCard(info: MediaInfo) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            if (info.thumbnail != null) {
                AsyncImage(
                    model = info.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                )
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(info.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(
                    info.uploader,
                    humanDuration(info.duration),
                    humanCount(info.viewCount)?.let { "$it visualizações" },
                    info.extractor,
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (info.isLive) Text("🔴 Ao vivo: grava a partir de agora", style = MaterialTheme.typography.bodySmall)
                if (info.isPlaylist) {
                    Text("Playlist · ${info.playlistCount} itens", style = MaterialTheme.typography.labelLarge)
                    info.entries.take(5).forEachIndexed { i, e ->
                        Text("${i + 1}. ${e.title}", style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    if (info.entries.size > 5) {
                        Text("... e mais ${info.entries.size - 5}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Options(info: MediaInfo, s: Selection, select: ((Selection) -> Selection) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (info.hasVideo && info.hasAudio) {
            Section("Tipo") {
                Chip("Vídeo", s.mode == Mode.VIDEO) { select { it.copy(mode = Mode.VIDEO) } }
                Chip("Apenas áudio", s.mode == Mode.AUDIO) { select { it.copy(mode = Mode.AUDIO) } }
            }
        }
        if (s.mode == Mode.VIDEO) {
            Section("Qualidade") {
                Chip(withSize("Melhor", info.estimateVideoSize(null)), s.quality == "best") {
                    select { it.copy(quality = "best") }
                }
                val heights = info.videoHeights.ifEmpty {
                    // playlist: qualidades por item só no download → presets de prioridade
                    if (info.isPlaylist) listOf(1080, 720, 480) else emptyList()
                }
                heights.forEach { h ->
                    val label = when {
                        h >= 2160 -> "${h}p 4K"
                        h >= 1440 -> "${h}p 2K"
                        info.isPlaylist && info.videoHeights.isEmpty() -> "Até ${h}p"
                        else -> "${h}p"
                    }
                    Chip(withSize(label, info.estimateVideoSize(h)), s.quality == h.toString()) {
                        select { it.copy(quality = h.toString()) }
                    }
                }
            }
            Section("Formato") {
                listOf("mp4" to "MP4", "mkv" to "MKV", "webm" to "WEBM", "original" to "Original").forEach { (k, l) ->
                    Chip(l, s.container == k) { select { it.copy(container = k) } }
                }
            }
        } else {
            Section("Formato") {
                buildList {
                    add("mp3" to "MP3"); add("m4a" to "M4A"); add("opus" to "OPUS"); add("wav" to "WAV")
                    if (info.hasLosslessAudio) add("flac" to "FLAC")
                    add("original" to "Original")
                }.forEach { (k, l) -> Chip(l, s.audioFormat == k) { select { it.copy(audioFormat = k) } } }
            }
            if (s.audioFormat !in setOf("wav", "flac", "original")) {
                Section("Qualidade" + (info.bestAudioBitrate?.let { " (fonte: até $it kbps)" } ?: "")) {
                    listOf("320", "256", "192", "128").forEach { b ->
                        Chip("$b kbps", s.audioBitrate == b) { select { it.copy(audioBitrate = b) } }
                    }
                    Chip("Melhor (VBR)", s.audioBitrate == "original") { select { it.copy(audioBitrate = "original") } }
                }
            }
            humanSize(info.estimateAudioSize(s.audioFormat, s.audioBitrate))?.let {
                Text("Tamanho estimado: ~$it", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!info.isLive) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Gravar título, autor e capítulos no arquivo", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                Switch(s.embedMetadata, onCheckedChange = { v -> select { it.copy(embedMetadata = v) } })
            }
        }
    }
}

private fun withSize(label: String, size: Long?) = humanSize(size)?.let { "$label · ~$it" } ?: label

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
