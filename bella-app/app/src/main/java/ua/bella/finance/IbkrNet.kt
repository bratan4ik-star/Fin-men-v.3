package ua.bella.finance

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Токен і номер Flex-запиту: шифруються ключем Android Keystore і не потрапляють у резервні копії. */
object IbkrStore {
    private const val ALIAS = "bella-ibkr"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("bella", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }
    private fun enc(v: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(v.toByteArray()), Base64.NO_WRAP)
    }
    private fun dec(s: String): String? = try {
        val (iv, data) = s.split(':', limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)))
    } catch (_: Exception) { null }

    fun token(ctx: Context): String = prefs(ctx).getString("ibkrToken", null)?.let(::dec) ?: ""
    fun query(ctx: Context): String = prefs(ctx).getString("ibkrQuery", null) ?: ""
    fun save(ctx: Context, token: String, query: String) {
        prefs(ctx).edit().putString("ibkrToken", enc(token.trim())).putString("ibkrQuery", query.trim()).apply()
    }
    fun clear(ctx: Context) { prefs(ctx).edit().remove("ibkrToken").remove("ibkrQuery").remove("ibkrAt").apply() }
    fun configured(ctx: Context) = query(ctx).isNotEmpty() && prefs(ctx).contains("ibkrToken")
    fun lastAt(ctx: Context): Long = prefs(ctx).getLong("ibkrAt", 0L)
    fun setLastAt(ctx: Context, t: Long) { prefs(ctx).edit().putLong("ibkrAt", t).apply() }
}

private fun httpGet(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    return try {
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "BellaFinance/1.0 (Android)")
        val code = c.responseCode
        if (code != HttpURLConnection.HTTP_OK) throw java.io.IOException("HTTP $code")
        c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally { c.disconnect() }
}

/** Завантажує Flex-звіт: замовлення, потім до ~1 хв очікування готовності. Повертає дані або текст помилки. Лише поза головним потоком. */
fun fetchFlex(token: String, query: String, sleep: (Long) -> Unit = { Thread.sleep(it) }, get: (String) -> String = ::httpGet): Pair<FlexData?, String> {
    fun e(s: String) = URLEncoder.encode(s, "UTF-8")
    val sent = try { get("$FLEX_SEND?t=${e(token)}&q=${e(query)}&v=3") } catch (x: Exception) { return null to "Немає зв'язку з IBKR (${x.message ?: x.javaClass.simpleName})" }
    val st = parseFlexStatus(sent) ?: return null to "Незрозуміла відповідь IBKR"
    if (!st.ok) return null to flexError(st)
    val base = st.url.ifEmpty { FLEX_SEND.replace("SendRequest", "GetStatement") }
    var wait = 3000L
    repeat(14) {
        sleep(wait)
        val body = try { get("$base?q=${e(st.ref)}&t=${e(token)}&v=3") } catch (x: Exception) { return null to "Немає зв'язку з IBKR (${x.message ?: x.javaClass.simpleName})" }
        val s2 = parseFlexStatus(body)
        if (s2 == null) return try { parseFlexStatement(body) to "" } catch (_: Exception) { null to "Не вдалося прочитати звіт IBKR" }
        if (!s2.retry) return null to flexError(s2)
        wait = Math.min(wait * 2, 20000L)
    }
    return null to "IBKR не встиг сформувати звіт за ~4 хвилини. Натисніть «Синхронізувати» ще раз: звіт зазвичай уже готовий. Якщо знову так, перевірте в Client Portal, що Flex-запит запускається вручну (Run)."
}
