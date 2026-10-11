package com.alabypedro.mediatools.ui

import java.util.Locale

fun humanSize(bytes: Long?): String? {
    if (bytes == null || bytes <= 0) return null
    val units = listOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (i <= 1) String.format(Locale.US, "%.0f %s", v, units[i])
    else String.format(Locale("pt", "BR"), "%.1f %s", v, units[i])
}

fun humanDuration(seconds: Double?): String? {
    val s = seconds?.toLong()?.takeIf { it > 0 } ?: return null
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun humanCount(n: Long?): String? {
    n ?: return null
    return when {
        n >= 1_000_000_000 -> String.format(Locale("pt", "BR"), "%.1f bi", n / 1e9)
        n >= 1_000_000 -> String.format(Locale("pt", "BR"), "%.1f mi", n / 1e6)
        n >= 1_000 -> String.format(Locale("pt", "BR"), "%.1f mil", n / 1e3)
        else -> n.toString()
    }
}

private val URL_REGEX = Regex("""https?://[^\s<>"]+""")

/** Apps costumam compartilhar "Olha isso! https://..." : pega só o link. */
fun extractUrl(text: String?): String? = text?.let { URL_REGEX.find(it)?.value?.trimEnd('.', ',', ')', ']') }
