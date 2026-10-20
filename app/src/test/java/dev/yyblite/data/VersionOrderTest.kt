package dev.yyblite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionOrderTest {

    @Test
    fun compare() {
        assertEquals(0, VersionOrder.compare("8.0.79", "8.0.79"))
        assertEquals(0, VersionOrder.compare("9.04.65", "9.4.65"))
        assertEquals(0, VersionOrder.compare("1.0", "1.0.0.0"))
        assertTrue(VersionOrder.compare("1.10.0", "1.9.9") > 0)
        assertTrue(VersionOrder.compare("8.0.80", "8.0.79") > 0)
        assertTrue(VersionOrder.compare("8.0.70", "8.0.79") < 0)
        assertTrue(VersionOrder.compare(null, "0") == 0)
        assertTrue(VersionOrder.compare("1.2", "") > 0)
    }
}
