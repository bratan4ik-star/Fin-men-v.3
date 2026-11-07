package ua.bella.finance

import java.security.MessageDigest

/*
 * PIN-код. Зберігається лише хеш: «pbkdf2$ітерацій$base64(сіль)$base64(хеш)», PBKDF2WithHmacSHA256,
 * сіль 16 байт, 120 000 ітерацій. Старий формат (SHA-256 від «bella|PIN», hex) приймається для входу
 * й одразу перезаписується в новому форматі. Порівняння — у постійному часі.
 */

/** Новий хеш PIN-коду. */
fun hashPin(pin: String, salt: ByteArray = Crypto.random(16), iter: Int = Crypto.ITER): String =
    "pbkdf2$" + iter + "$" + Crypto.b64(salt) + "$" + Crypto.b64(Crypto.pbkdf2(pin, salt, iter, 256))

/** Старий формат до 1.26.0: SHA-256("bella|" + PIN) у hex. Лише для перевірки й міграції. */
fun legacyPinHash(pin: String): String = MessageDigest.getInstance("SHA-256").digest("bella|$pin".toByteArray()).joinToString("") { "%02x".format(it) }

/** ok — PIN правильний; upgrade — збережений хеш старого формату, його треба перезаписати. */
data class PinCheck(val ok: Boolean, val upgrade: Boolean)

fun verifyPin(pin: String, stored: String): PinCheck {
    if (stored.isEmpty()) return PinCheck(false, false)
    if (stored.startsWith("pbkdf2$")) {
        val p = stored.split('$')
        if (p.size != 4) return PinCheck(false, false)
        val iter = p[1].toIntOrNull()?.takeIf { it in 1..10_000_000 } ?: return PinCheck(false, false)
        return try {
            val salt = Crypto.unb64(p[2]); val want = Crypto.unb64(p[3])
            PinCheck(MessageDigest.isEqual(Crypto.pbkdf2(pin, salt, iter, want.size * 8), want), false)
        } catch (_: Exception) { PinCheck(false, false) }
    }
    val ok = MessageDigest.isEqual(legacyPinHash(pin).toByteArray(), stored.toByteArray())
    return PinCheck(ok, ok)
}

/*
 * Блокування після хибних спроб. Стан зберігається в SharedPreferences, тож перезапуск застосунку його не скидає.
 * 5 хибних спроб поспіль — 30 с; кожна наступна хибна спроба (без успішного входу між ними) — удвічі довше, до 15 хв.
 */
data class PinLock(val fails: Int = 0, val until: Long = 0L, val stage: Int = 0)

const val PIN_MAX_FAILS = 5
const val PIN_LOCK_BASE_MS = 30_000L
const val PIN_LOCK_MAX_MS = 15 * 60_000L

/** Тривалість блокування для етапу 0, 1, 2… : 30 с, 60 с, 120 с … до 15 хв. */
fun pinLockDelay(stage: Int): Long = if (stage >= 15) PIN_LOCK_MAX_MS else Math.min(PIN_LOCK_BASE_MS shl stage, PIN_LOCK_MAX_MS)

/** Скільки мс ще заблоковано (0 — можна вводити). Якщо годинник перевели назад, очікування не довше максимуму. */
fun pinLockLeft(s: PinLock, now: Long): Long = (s.until - now).coerceIn(0L, PIN_LOCK_MAX_MS)

/** Хибна спроба: після 5 — перше блокування; після блокування кожна хибна спроба блокує вдвічі довше. */
fun pinFailed(s: PinLock, now: Long): PinLock {
    val f = s.fails + 1
    return if (s.stage > 0 || f >= PIN_MAX_FAILS) PinLock(0, now + pinLockDelay(s.stage), s.stage + 1) else s.copy(fails = f)
}

/** Текст для екрана блокування. */
fun pinLockText(leftMs: Long): String {
    val s = (leftMs + 999) / 1000
    return "Забагато спроб. Зачекайте " + if (s >= 60) "${(s + 59) / 60} хв" else "$s с"
}

/** Ключ-значення з атомарним записом (commit). Абстракція над SharedPreferences для тестів на JVM. */
interface KV {
    fun get(key: String): String?
    fun getLong(key: String): Long = get(key)?.toLongOrNull() ?: 0L
    /** Записує й видаляє ключі одним commit(); false — запис не вдався. */
    fun write(put: Map<String, String> = emptyMap(), remove: List<String> = emptyList()): Boolean
}

/** Збереження стану блокування PIN. */
object PinLockStore {
    fun load(kv: KV): PinLock = PinLock(kv.get("pinFails")?.toIntOrNull() ?: 0, kv.getLong("pinUntil"), kv.get("pinStage")?.toIntOrNull() ?: 0)
    fun save(kv: KV, s: PinLock) { kv.write(mapOf("pinFails" to s.fails.toString(), "pinUntil" to s.until.toString(), "pinStage" to s.stage.toString())) }
}
