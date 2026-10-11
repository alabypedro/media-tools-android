package com.alabypedro.mediatools.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alabypedro.mediatools.update.AppUpdater
import com.alabypedro.mediatools.update.UpdateState
import kotlinx.coroutines.launch

/** Aviso de versão nova do app (Início e Ajustes). Some quando não há nada a fazer. */
@Composable
fun UpdateBanner(updater: AppUpdater) {
    val state by updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val release = when (val s = state) {
        is UpdateState.Available -> s.release
        is UpdateState.Downloading -> s.release
        is UpdateState.Ready -> s.release
        is UpdateState.Failed -> s.release
        else -> null
    } ?: return

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nova versão do app: ${release.version}", style = MaterialTheme.typography.titleSmall)
            if (release.notes.isNotBlank()) {
                Text(release.notes, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            when (val s = state) {
                is UpdateState.Downloading -> {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Text("Baixando ${humanSize(release.apkSize) ?: ""}...", style = MaterialTheme.typography.bodySmall)
                }
                is UpdateState.Ready -> Row {
                    Button(onClick = {
                        if (!updater.install(s.apk)) {
                            Toast.makeText(context, "Permita instalar apps do Universal Media Tools e toque em Instalar de novo.",
                                Toast.LENGTH_LONG).show()
                        }
                    }) { Text("Instalar") }
                }
                is UpdateState.Failed -> {
                    Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { scope.launch { updater.download(release) } }) { Text("Tentar de novo") }
                }
                else -> Button(onClick = { scope.launch { updater.download(release) } }) {
                    Text("Atualizar" + (humanSize(release.apkSize)?.let { " ($it)" } ?: ""))
                }
            }
        }
    }
}
