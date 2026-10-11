package com.alabypedro.mediatools.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Azul → roxo do ícone
private val Blue = Color(0xFF2762EB)
private val Violet = Color(0xFF7A3AEC)

private val Light = lightColorScheme(primary = Blue, secondary = Violet, tertiary = Violet)
private val Dark = darkColorScheme(primary = Color(0xFF9DB5FF), secondary = Color(0xFFC9B2FF), tertiary = Color(0xFFC9B2FF))

@Composable
fun UmtTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        // Android 12+: cores do papel de parede (Material You)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = colors, content = content)
}
