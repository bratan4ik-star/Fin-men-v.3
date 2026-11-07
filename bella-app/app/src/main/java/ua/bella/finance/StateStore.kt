package ua.bella.finance

/*
 * Збережений стан (Persisted у JSON) лежить зашифрованим у ключі «stateEnc»: AES-256-GCM, ключ у AndroidKeyStore
 * (alias «bella-state»), формат «base64(iv):base64(шифротекст)». До 1.26.0 стан лежав відкритим у «state».
 *
 * Міграція одноразова: відкритий «state» читається, записується зашифрованим, зашифрований запис читається назад
 * і порівнюється, і лише тоді «state» видаляється (commit). Якщо будь-який крок не вдався, «state» лишається,
 * а міграція повториться при наступному запуску.
 *
 * Якщо «stateEnc» є, але розшифрувати його не вдалось (ключ у Keystore втрачено, дані пошкоджено), порожній стан
 * мовчки НЕ створюється: load повертає Failed, застосунок показує екран відновлення і нічого не записує.
 */
sealed class StateLoad {
    /** Стан прочитано. migrated — щойно перенесено з відкритого «state». */
    data class Ok(val json: String, val migrated: Boolean = false) : StateLoad()
    /** Даних ще немає (перший запуск). */
    data object Empty : StateLoad()
    /** Дані є, але прочитати їх не вдалося. */
    data class Failed(val reason: String) : StateLoad()
}

object StateStore {
    const val ENC = "stateEnc"
    const val PLAIN = "state"
    /** Сюди переноситься нерозшифрований запис, якщо користувач вирішив почати з порожніми даними (не видаляється). */
    const val BROKEN = "stateEncBroken"

    /**
     * Читає стан. [valid] перевіряє, що JSON розбирається (інакше Failed). [migrate] = false — лише читання
     * (так читають фонові задачі, щоб не змагатися з застосунком за міграцію).
     */
    fun load(kv: KV, cipher: StringCipher, valid: (String) -> Boolean, migrate: Boolean = true): StateLoad {
        val enc = kv.get(ENC)
        if (enc != null) {
            val text = try { cipher.decrypt(enc) } catch (e: Exception) { return StateLoad.Failed("Не вдалося розшифрувати дані: ${e.javaClass.simpleName}") }
            return if (valid(text)) StateLoad.Ok(text) else StateLoad.Failed("Дані пошкоджено")
        }
        val plain = kv.get(PLAIN) ?: return StateLoad.Empty
        if (!valid(plain)) return StateLoad.Failed("Дані пошкоджено")
        if (!migrate) return StateLoad.Ok(plain)
        return StateLoad.Ok(plain, migrated = migratePlain(kv, cipher, plain))
    }

    /** Шифрує відкритий стан; видаляє «state» лише після успішного запису й перевірки читанням. */
    fun migratePlain(kv: KV, cipher: StringCipher, plain: String): Boolean {
        val enc = try { cipher.encrypt(plain) } catch (_: Exception) { return false }
        if (!kv.write(mapOf(ENC to enc))) return false
        val back = try { kv.get(ENC)?.let { cipher.decrypt(it) } } catch (_: Exception) { null }
        if (back != plain) { kv.write(remove = listOf(ENC)); return false }
        return kv.write(remove = listOf(PLAIN))
    }

    /** Зберігає стан зашифрованим. Відкритий текст не пишеться ніколи; false — не вдалося (попередній запис лишається). */
    fun save(kv: KV, cipher: StringCipher, json: String, extra: Map<String, String> = emptyMap()): Boolean {
        val enc = try { cipher.encrypt(json) } catch (_: Exception) { return false }
        return kv.write(extra + (ENC to enc), remove = if (kv.get(PLAIN) != null) listOf(PLAIN) else emptyList())
    }

    /** «Почати з порожніми даними» після невдалого читання: нерозшифрований запис зберігається під іншим ключем. */
    fun setAsideBroken(kv: KV): Boolean {
        val enc = kv.get(ENC) ?: return true
        return kv.write(mapOf(BROKEN to enc), remove = listOf(ENC))
    }
}
