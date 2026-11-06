package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RulesTest {
    private fun tx(desc: String, cat: String = "Інше", type: String = "expense") = Tx(desc, cat, type, 100.0, "2026-11-01")

    @Test fun matchesAnyFragmentIgnoringCase() {
        val rules = listOf(CatRule("сільпо, атб", "Харчування"), CatRule("wog", "Транспорт"))
        assertEquals("Харчування", ruleFor("СІЛЬПО №123 Київ", rules))
        assertEquals("Транспорт", ruleFor("АЗС WOG 45", rules))
        assertNull(ruleFor("Rozetka", rules))
    }

    @Test fun firstRuleWins() {
        val rules = listOf(CatRule("сільпо кафе", "Розваги"), CatRule("сільпо", "Харчування"))
        assertEquals("Розваги", ruleFor("Сільпо кафе", rules)); assertEquals("Харчування", ruleFor("Сільпо", rules))
    }

    @Test fun emptyFragmentsNeverMatch() {
        assertNull(ruleFor("будь-що", listOf(CatRule(" , ,", "Харчування"))))
    }

    @Test fun applyToOldSkipsTransfersAndCounts() {
        val (out, n) = applyRules(listOf(tx("Сільпо"), tx("Сільпо", "Харчування"), tx("Сільпо", "Перекази", "transfer"), tx("Інше")), DEFAULT_RULES)
        assertEquals(1, n)
        assertEquals(listOf("Харчування", "Харчування", "Перекази", "Інше"), out.map { it.cat })
    }

    @Test fun suggestionAfterManualCategoryChange() {
        assertEquals(CatRule("Rozetka", "Побутова техніка"), suggestRule(tx("Rozetka"), tx("Rozetka", "Побутова техніка"), DEFAULT_RULES))
        assertNull(suggestRule(tx("Rozetka"), tx("Rozetka"), DEFAULT_RULES))                       // категорія та сама
        assertNull(suggestRule(tx("АТБ маркет"), tx("АТБ маркет", "Харчування"), DEFAULT_RULES))   // правило вже є
        assertNull(suggestRule(tx("ab"), tx("ab", "Розваги"), DEFAULT_RULES))                       // закороткий опис
    }

    @Test fun addRuleGoesFirstAndReplacesSameText() {
        val r = addRule(DEFAULT_RULES, CatRule("Сільпо кафе", "Розваги"))
        assertEquals(CatRule("Сільпо кафе", "Розваги"), r.first()); assertEquals(DEFAULT_RULES.size + 1, r.size)
        val r2 = addRule(r, CatRule("сільпо КАФЕ", "Харчування"))
        assertEquals(r.size, r2.size); assertEquals("Харчування", r2.first().cat)
    }

    @Test fun userRulesAffectCsvImport() {
        val rules = listOf(CatRule("rozetka", "Побутова техніка"))
        val r = Importer.parseCsv("2026-11-01; ROZETKA.UA; -2500\n2026-11-01; Сільпо; -100", 1.0, rules)
        assertEquals(listOf("Побутова техніка", "Інше"), r.txs.map { it.cat }) // вбудоване «Сільпо» видалено користувачем
    }

    @Test fun oldDataGetsDefaultRules() {
        val d = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(AppData.serializer(), """{"txs":[],"accs":[],"debts":[],"pos":[],"goals":[]}""")
        assertEquals(DEFAULT_RULES, d.rules)
    }
}
