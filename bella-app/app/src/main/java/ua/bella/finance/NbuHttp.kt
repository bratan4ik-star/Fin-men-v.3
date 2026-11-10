package ua.bella.finance

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.zip.GZIPInputStream

/*
 * Запити до bank.gov.ua (довідник ОВДП, аукціони, курси): повтори на 5xx і тайм-аутах з паузою 2/5/10 с,
 * gzip, окремі тайм-аути для з'єднання й читання. Викликати лише поза головним потоком.
 */

const val NBU_CONNECT_TIMEOUT_MS = 20_000
const val NBU_READ_TIMEOUT_MS = 60_000
val NBU_RETRY_PAUSES_MS = longArrayOf(2_000, 5_000, 10_000)

/** Повторювати чи ні: лише 5xx і мережеві збої/тайм-аути; 4xx і помилки розбору — ні. */
fun nbuShouldRetry(httpCode: Int?, ex: Throwable?): Boolean = when {
    httpCode != null -> httpCode in 500..599
    ex is SocketTimeoutException -> true
    ex is IOException -> true
    else -> false
}

/** Тіло відповіді як текст, з розпакуванням gzip, якщо сервер його надіслав. */
fun readNbuBody(c: HttpURLConnection): String {
    val raw = c.inputStream
    val stream = if (c.contentEncoding?.equals("gzip", ignoreCase = true) == true) GZIPInputStream(raw) else raw
    return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}

/** Один запит без повторів: тіло або виняток з кодом HTTP (IOException). */
private fun nbuGetOnce(url: String, readTimeoutMs: Int): String {
    val c = URL(url).openConnection() as HttpURLConnection
    return try {
        c.connectTimeout = NBU_CONNECT_TIMEOUT_MS; c.readTimeout = readTimeoutMs
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("Accept-Encoding", "gzip")
        c.setRequestProperty("User-Agent", "BellaFinance/1.0 (Android)")
        val code = c.responseCode
        if (code != HttpURLConnection.HTTP_OK) throw NbuHttpException(code)
        readNbuBody(c)
    } finally { c.disconnect() }
}

class NbuHttpException(val code: Int) : IOException("HTTP $code")

/**
 * GET з повторами: до 3 повторів (пауза 2, 5, 10 с) на 5xx і тайм-аутах. [readTimeoutMs] — для великих відповідей
 * (довідник ОВДП — 60 с). [sleep] підміняється в тестах.
 */
fun nbuGet(url: String, readTimeoutMs: Int = NBU_READ_TIMEOUT_MS, sleep: (Long) -> Unit = { Thread.sleep(it) }): String {
    var attempt = 0
    while (true) {
        try {
            return nbuGetOnce(url, readTimeoutMs)
        } catch (e: Exception) {
            val code = (e as? NbuHttpException)?.code
            if (attempt >= NBU_RETRY_PAUSES_MS.size || !nbuShouldRetry(code, e)) throw e
            sleep(NBU_RETRY_PAUSES_MS[attempt])
            attempt++
        }
    }
}
