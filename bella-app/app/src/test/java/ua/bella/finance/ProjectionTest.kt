package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectionTest {
    @Test fun effectiveMonthlyRate() {
        assertEquals(0.12, Math.pow(1 + monthlyRateFromAnnual(0.12), 12.0) - 1, 1e-12)
    }

    @Test fun sameNumbersAsWebVersion() {
        // ті самі значення, що перевірено у вебверсії в браузері: 1120 / 1092 / 975
        val r = compound(1000.0, 0.0, 0.12, 1, 0.12, 0.0, 0.23, 0.0)
        assertEquals(1120.0, r.total, 1e-6)
        assertEquals(120.0, r.profit, 1e-6)
        assertEquals(1092.4, r.net, 1e-6)
        assertEquals(1092.4 / 1.12, r.real, 1e-6)
    }

    @Test fun feesReduceRateAndContributionsGrow() {
        val r = compound(0.0, 1000.0, 0.10, 2, 0.0, 0.01, 0.0, 0.10)
        assertEquals(1.10 * 0.99 - 1, r.netRate, 1e-12)
        assertEquals(12 * 1000.0 + 12 * 1100.0, r.deposits, 1e-6) // другий рік внесок +10%
    }

    @Test fun fireCapitalNeededAndReach() {
        val f = fireProjection(0.0, 10000.0, 0.10, 0.0, 0.04, 1000.0)
        assertEquals(300000.0, f.capitalNeeded, 1e-6) // як у вебверсії
        assertEquals(FireStatus.REACHED, f.status)
        assertEquals(28, f.months) // 300 000 при 10 000/міс і 10% річних (перевірено тією ж формулою)
    }

    @Test fun fireEdgeCases() {
        assertEquals(FireStatus.NO_TARGET, fireProjection(1000.0, 0.0, 0.1, 0.08, 0.04, 0.0).status)
        assertEquals(FireStatus.NO_SWR, fireProjection(1000.0, 0.0, 0.1, 0.08, 0.0, 100.0).status)
        assertEquals(FireStatus.DONE, fireProjection(1_000_000.0, 0.0, 0.1, 0.08, 0.04, 1000.0).status)
        assertEquals(FireStatus.UNREACHABLE, fireProjection(1000.0, 0.0, 0.05, 0.08, 0.04, 1000.0).status)
        assertEquals(FireStatus.TOO_LONG, fireProjection(0.0, 1.0, 0.0, 0.0, 0.04, 1_000_000.0).status)
        assertEquals("3 р. 4 міс.", fmtYearsMonths(40)); assertEquals("7 міс.", fmtYearsMonths(7))
    }
}
