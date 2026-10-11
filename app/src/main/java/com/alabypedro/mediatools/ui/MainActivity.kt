package com.alabypedro.mediatools.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alabypedro.mediatools.R
import com.alabypedro.mediatools.UmtApp
import com.alabypedro.mediatools.ui.theme.UmtTheme

class MainActivity : ComponentActivity() {
    private val home: HomeViewModel by viewModels()
    private val tab = mutableIntStateOf(TAB_HOME)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* negado: só sem notificações */ }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val app = application as UmtApp

        setContent {
            UmtTheme {
                val jobs by app.downloads.jobs.collectAsStateWithLifecycle()
                val active = jobs.count { it.isActive }
                val current = tab.intValue
                Scaffold(
                    topBar = {
                        TopAppBar(title = {
                            Text(when (current) {
                                TAB_DOWNLOADS -> "Downloads"
                                TAB_SETTINGS -> "Ajustes"
                                else -> "Universal Media Tools"
                            })
                        })
                    },
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = current == TAB_HOME,
                                onClick = { tab.intValue = TAB_HOME },
                                icon = { Icon(Icons.Default.Home, contentDescription = null) },
                                label = { Text("Início") },
                            )
                            NavigationBarItem(
                                selected = current == TAB_DOWNLOADS,
                                onClick = { tab.intValue = TAB_DOWNLOADS },
                                icon = {
                                    BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) {
                                        Icon(painterResource(R.drawable.ic_download), contentDescription = null)
                                    }
                                },
                                label = { Text("Downloads") },
                            )
                            NavigationBarItem(
                                selected = current == TAB_SETTINGS,
                                onClick = { tab.intValue = TAB_SETTINGS },
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = { Text("Ajustes") },
                            )
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        when (current) {
                            TAB_DOWNLOADS -> DownloadsScreen(app.downloads)
                            TAB_SETTINGS -> SettingsScreen(app)
                            else -> HomeScreen(home, app.updater, onQueued = { tab.intValue = TAB_DOWNLOADS })
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** "Compartilhar → Universal Media Tools" de outro app, ou toque numa notificação. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_SEND) {
            val url = extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
            if (url != null) {
                tab.intValue = TAB_HOME
                home.analyze(url)
            }
        }
        if (intent.hasExtra(EXTRA_TAB)) tab.intValue = intent.getIntExtra(EXTRA_TAB, TAB_HOME)
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_HOME = 0
        const val TAB_DOWNLOADS = 1
        const val TAB_SETTINGS = 2
    }
}
