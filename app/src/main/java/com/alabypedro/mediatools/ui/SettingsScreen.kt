package com.alabypedro.mediatools.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alabypedro.mediatools.BuildConfig
import com.alabypedro.mediatools.UmtApp
import com.alabypedro.mediatools.download.MediaStoreSaver
import com.alabypedro.mediatools.engine.EngineState
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: UmtApp) {
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val engine by app.engine.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(app.engine.version) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Downloads simultâneos", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..4).forEach { n ->
                FilterChip(
                    selected = settings.maxConcurrent == n,
                    onClick = { app.settings.update { it.copy(maxConcurrent = n) } },
                    label = { Text("$n") },
                )
            }
        }
        Text(
            "Pasta: Download/${MediaStoreSaver.FOLDER}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        Text("yt-dlp", style = MaterialTheme.typography.titleSmall)
        Text(
            "Versão: ${version ?: "embutida"}" + when (engine) {
                EngineState.Updating -> " (atualizando...)"
                EngineState.Starting -> " (preparando...)"
                else -> ""
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Atualizar automaticamente", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Verifica uma vez por dia ao abrir o app. As plataformas mudam com frequência e um yt-dlp antigo para de funcionar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(settings.autoUpdateYtDlp, onCheckedChange = { v -> app.settings.update { it.copy(autoUpdateYtDlp = v) } })
        }
        OutlinedButton(
            enabled = engine == EngineState.Ready,
            onClick = {
                scope.launch {
                    val msg = runCatching { app.engine.update() }.fold(
                        onSuccess = { updated -> if (updated) "yt-dlp atualizado." else "O yt-dlp já está na versão mais recente." },
                        onFailure = { it.message ?: "Falha ao atualizar." },
                    )
                    version = app.engine.version
                    app.settings.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            },
        ) { Text("Atualizar yt-dlp agora") }

        HorizontalDivider()

        Text("Sobre", style = MaterialTheme.typography.titleSmall)
        Text(
            "Universal Media Tools para Android ${BuildConfig.VERSION_NAME}\n" +
                "Use apenas para conteúdo que você tem autorização para baixar. O app não contorna DRM, " +
                "paywalls nem conteúdo privado.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
