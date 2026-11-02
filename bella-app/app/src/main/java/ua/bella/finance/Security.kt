package ua.bella.finance

import java.security.MessageDigest

/** PIN зберігається лише як SHA-256 хеш із сіллю "bella|", як у прототипі. */
fun hashPin(v: String): String = MessageDigest.getInstance("SHA-256").digest("bella|$v".toByteArray()).joinToString("") { "%02x".format(it) }
