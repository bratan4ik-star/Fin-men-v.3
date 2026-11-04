package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DatePickTest {
    @Test fun roundTrip() {
        for (d in listOf("2026-11-04", "2027-02-28", "2028-02-29", "2026-01-01", "2026-12-31")) assertEquals(d, pickerMillisToIso(isoToPickerMillis(d)!!))
    }
    @Test fun midnightUtcMillis() { assertEquals(1_793_836_800_000L, isoToPickerMillis("2026-11-05")) }
    @Test fun invalidIsNull() {
        assertNull(isoToPickerMillis("")); assertNull(isoToPickerMillis("04.11.2026")); assertNull(isoToPickerMillis("2026-02-30"))
    }
    @Test fun trimsSpaces() { assertEquals("2026-11-04", pickerMillisToIso(isoToPickerMillis(" 2026-11-04 ")!!)) }
}
