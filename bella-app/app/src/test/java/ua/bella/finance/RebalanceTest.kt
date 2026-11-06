package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RebalanceTest {
    private val values = mapOf("ОВДП" to 50000.0, "Депозити" to 20000.0, "ETF" to 30000.0, "Крипто" to 0.0)

    @Test fun adviceMovesPortfolioToTargets() {
        val r = rebalance(values, mapOf("ОВДП" to 40.0, "Депозити" to 20.0, "ETF" to 30.0, "Крипто" to 10.0)).associateBy { it.group }
        assertEquals(-10000.0, r.getValue("ОВДП").diffUah, 1e-9) // 50% → 40%
        assertEquals(10000.0, r.getValue("Крипто").diffUah, 1e-9) // 0% → 10%
        assertTrue(r.getValue("ETF").ok)
        assertEquals(50.0, r.getValue("ОВДП").nowPct, 1e-9)
        assertEquals(0.0, r.values.sumOf { it.diffUah }, 1e-9)
    }

    @Test fun smallDeviationIsOk() {
        val r = rebalance(mapOf("ОВДП" to 40500.0, "ETF" to 59500.0), mapOf("ОВДП" to 40.0, "ETF" to 60.0))
        assertTrue(r.all { it.ok })
    }

    @Test fun groupWithoutTargetIsShownAsSellAll() {
        val r = rebalance(mapOf("ОВДП" to 1000.0, "Крипто" to 1000.0), mapOf("ОВДП" to 100.0)).associateBy { it.group }
        assertEquals(listOf("ОВДП", "Крипто"), r.keys.toList())
        assertEquals(-1000.0, r.getValue("Крипто").diffUah, 1e-9)
        assertFalse(r.getValue("Крипто").ok)
    }

    @Test fun emptyPortfolioHasZeroShares() {
        val r = rebalance(emptyMap(), DEFAULT_TARGETS)
        assertEquals(4, r.size)
        assertTrue(r.all { it.nowPct == 0.0 && it.diffUah == 0.0 })
    }

    @Test fun oldDataWithoutTargetsGetsDefaults() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val d = json.decodeFromString(AppData.serializer(), """{"txs":[],"accs":[],"debts":[],"pos":[],"goals":[]}""")
        assertEquals(DEFAULT_TARGETS, d.targets)
    }
}
