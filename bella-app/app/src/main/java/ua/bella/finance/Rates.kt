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

/** Обов'язкові валюти й ті, що НБУ публікує теж і які застосунок бере, якщо вони є у відповіді. */
private val NBU_CODES = listOf("USD", "EUR", "PLN")
private val NBU_EXTRA = listOf("GBP", "CHF")

/** Розбирає відповідь https://bank.gov.ua/NBUStatService/v1/statdirectory/exchange?json. null, якщо немає всіх трьох валют. */
fun parseNbuRates(body: String): NbuRates? = try {
    val out = mutableMapOf<String, Double>()
    var date = ""
    for (el in Json.parseToJsonElement(body).jsonArray) {
        val o = el.jsonObject
        val cc = o["cc"]?.jsonPrimitive?.content ?: continue
        if (cc !in NBU_CODES && cc !in NBU_EXTRA) continue
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

/**
 * Архів курсів НБУ за період для однієї валюти: дата (РРРР-ММ-ДД) → курс до гривні.
 * Відповідь: [{"cc":"USD","rate":45.3,"exchangedate":"09.11.2026"}, …]; записи без курсу пропускаються.
 */
fun parseNbuHistory(body: String, cur: String): Map<String, Double> = try {
    val out = HashMap<String, Double>()
    for (el in Json.parseToJsonElement(body).jsonArray) {
        val o = el.jsonObject
        if (o["cc"]?.jsonPrimitive?.content != cur) continue
        val rate = o["rate"]?.jsonPrimitive?.doubleOrNull ?: continue
        val d = o["exchangedate"]?.jsonPrimitive?.content ?: continue
        val m = Regex("^(\\d{2})\\.(\\d{2})\\.(\\d{4})$").find(d) ?: continue
        if (rate > 0) out["${m.groupValues[3]}-${m.groupValues[2]}-${m.groupValues[1]}"] = rate
    }
    out
} catch (_: Exception) { emptyMap() }

/** Мережевий запит архіву за період [startIso, endIso]. Лише поза головним потоком; при збої — порожньо. */
fun fetchNbuHistory(cur: String, startIso: String, endIso: String): Map<String, Double> = try {
    val s = startIso.replace("-", ""); val e = endIso.replace("-", "")
    val url = URL("https://bank.gov.ua/NBUStatService/v1/statdirectory/exchange?valcode=${cur.lowercase()}&start=$s&end=$e&sort=exchangedate&order=asc&json")
    val c = url.openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 10000; c.readTimeout = 15000
        if (c.responseCode != HttpURLConnection.HTTP_OK) emptyMap()
        else parseNbuHistory(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }, cur)
    } finally { c.disconnect() }
} catch (_: Exception) { emptyMap() }
