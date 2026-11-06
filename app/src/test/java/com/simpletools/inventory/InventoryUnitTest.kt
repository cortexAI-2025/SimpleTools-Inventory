package com.simpletools.inventory

import com.simpletools.inventory.core.Transfer
import com.simpletools.inventory.core.decimal
import com.simpletools.inventory.core.scaled
import org.junit.Assert.*
import org.junit.Test

class InventoryUnitTest {
    @Test fun exactFractionalQuantities() {
        assertEquals(26_000L, scaled("20") + scaled("10") - scaled("4"))
        assertEquals(300L, scaled("0,1") + scaled("0.2"))
        assertEquals("0.3", decimal(300L))
        assertThrows(ArithmeticException::class.java) { scaled("0.0001") }
        assertThrows(IllegalArgumentException::class.java) { scaled("1000000001") }
    }

    @Test fun csvEscaping() {
        assertEquals("\"Écrou; \"\"M8\"\"\"", Transfer.csvCell("Écrou; \"M8\""))
        assertEquals("\"'=SUM(1;2)\"", Transfer.csvCell("=SUM(1;2)"))
        assertEquals("\"-1.5\"", Transfer.csvCell("-1.5"))
    }
}
