package com.alabypedro.mediatools.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.alabypedro.mediatools.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AppRelease(
    val version: String,
    val notes: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256Url: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState
    data class Ready(val release: AppRelease, val apk: File) : UpdateState
    data class Failed(val message: String, val release: AppRelease? = null) : UpdateState
}

/**
 * Atualização do próprio app pelo GitHub Releases (mesmo esquema do desktop,
 * umd/core/app_update.py): cada versão publica um APK por arquitetura +
 * um .sha256; o download só é aceito se o SHA-256 bater.
 */
class AppUpdater(private val context: Context) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val dir get() = File(context.cacheDir, "updates")

    suspend fun check(): UpdateState = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Checking
        val result = try {
            val json = JSONObject(httpText("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"))
            val release = parseRelease(json)
            when {
                release == null -> UpdateState.Failed("A versão publicada não tem APK para este aparelho.")
                isNewer(release.version, BuildConfig.VERSION_NAME) -> UpdateState.Available(release)
                else -> UpdateState.UpToDate
            }
        } catch (e: Exception) {
            Log.w(TAG, "check", e)
            UpdateState.Failed("Não foi possível verificar atualizações (sem conexão?).")
        }
        // Já baixado antes e ainda válido? Vai direto para "instalar".
        if (result is UpdateState.Available) {
            val apk = File(dir, result.release.apkName)
            if (apk.isFile && apk.length() == result.release.apkSize) {
                _state.value = UpdateState.Ready(result.release, apk)
                return@withContext _state.value
            }
        }
        // apaga APKs de versões anteriores que sobraram
        if (result !is UpdateState.Available) dir.deleteRecursively()
        _state.value = result
        result
    }

    suspend fun download(release: AppRelease) = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Downloading(release, 0f)
        try {
            dir.deleteRecursively()
            dir.mkdirs()
            val expected = httpText(release.sha256Url).trim().split(Regex("\\s+")).first().lowercase()
            require(expected.length == 64) { "Arquivo .sha256 inválido." }
            val part = File(dir, release.apkName + ".part")
            val digest = MessageDigest.getInstance("SHA-256")
            open(release.apkUrl).run {
                val total = contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastEmit = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            if (done - lastEmit > 512 * 1024) {
                                lastEmit = done
                                _state.value = UpdateState.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) {
                part.delete()
                throw IllegalStateException("O APK baixado não confere com o SHA-256 publicado. Tente de novo.")
            }
            val apk = File(dir, release.apkName)
            part.renameTo(apk)
            _state.value = UpdateState.Ready(release, apk)
        } catch (e: Exception) {
            Log.w(TAG, "download", e)
            _state.value = UpdateState.Failed(e.message ?: "Falha ao baixar a atualização.", release)
        }
    }

    /**
     * Abre o instalador do Android. Na primeira vez o Android pede para permitir
     * "instalar apps desta fonte"; devolve false se mandou o usuário para essa tela.
     */
    fun install(apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    private fun parseRelease(json: JSONObject): AppRelease? {
        val version = json.getString("tag_name").removePrefix("v")
        val assets = json.getJSONArray("assets")
        val byName = (0 until assets.length()).associate { i ->
            val a = assets.getJSONObject(i)
            a.getString("name") to a
        }
        // primeira arquitetura do aparelho que tem APK publicado
        val abi = Build.SUPPORTED_ABIS.firstOrNull { apkName(version, it) in byName } ?: return null
        val apk = byName.getValue(apkName(version, abi))
        val sha = byName[apkName(version, abi) + ".sha256"] ?: return null
        return AppRelease(
            version = version,
            notes = json.optString("body").trim(),
            apkName = apk.getString("name"),
            apkUrl = apk.getString("browser_download_url"),
            apkSize = apk.optLong("size"),
            sha256Url = sha.getString("browser_download_url"),
        )
    }

    private fun httpText(url: String): String = open(url).inputStream.use { it.readBytes().decodeToString() }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "UniversalMediaTools-Android/${BuildConfig.VERSION_NAME}")
            if (responseCode !in 200..299) throw IllegalStateException("HTTP $responseCode em $url")
        }

    companion object {
        private const val TAG = "AppUpdater"

        /** Mesmo nome que o release.py dá aos APKs publicados. */
        fun apkName(version: String, abi: String) = "UniversalMediaTools-$version-$abi.apk"

        /** "0.10.0" > "0.9.3"; sufixos não numéricos são ignorados. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
