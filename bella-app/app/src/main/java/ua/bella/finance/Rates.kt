package ua.bella.finance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/** Курси НБУ до гривні (UAH = 1) і дата курсу у форматі ДД.ММ.РРРР. */
data class NbuRates(val rates: Map<String, Double>, val date: String)

private val NBU_CODES = listOf("USD", "EUR", "PLN")

/** Розбирає відповідь https://bank.gov.ua/NBUStatService/v1/statdirectory/exchange?json. null, якщо немає всіх трьох валют. */
fun parseNbuRates(body: String): NbuRates? = try {
    val out = mutableMapOf<String, Double>()
    var date = ""
    for (el in Json.parseToJsonElement(body).jsonArray) {
        val o = el.jsonObject
        val cc = o["cc"]?.jsonPrimitive?.content ?: continue
        if (cc !in NBU_CODES) continue
        val rate = o["rate"]?.jsonPrimitive?.doubleOrNull ?: continue
        if (rate <= 0) continue
        out[cc] = rate
        if (date.isEmpty()) date = o["exchangedate"]?.jsonPrimitive?.content ?: ""
    }
    if (out.keys.containsAll(NBU_CODES)) NbuRates(out, date) else null
} catch (_: Exception) { null }

/** Мережевий запит. Викликати лише поза головним потоком. */
fun fetchNbuRates(): NbuRates? {
    val c = URL("https://bank.gov.ua/NBUStatService/v1/statdirectory/exchange?json").openConnection() as HttpURLConnection
    return try {
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/json")
        if (c.responseCode != HttpURLConnection.HTTP_OK) null
        else parseNbuRates(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    } finally { c.disconnect() }
}

/** Конвертація між валютами через гривню за мапою курсів (UAH = 1). */
fun convertAmount(amount: Double, from: String, to: String, rates: Map<String, Double>): Double =
    amount * (rates[from] ?: 1.0) / (rates[to] ?: 1.0)
