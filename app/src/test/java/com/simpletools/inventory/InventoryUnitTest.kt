package com.simpletools.inventory

import com.simpletools.inventory.core.*
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Base64
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

    @Test fun stockChangesAndFreeBoundary() {
        assertTrue(InventoryRepository.canCreateFree(49))
        assertFalse(InventoryRepository.canCreateFree(50))
        assertFalse(InventoryRepository.canCreateFree(51))
        assertEquals(1000L to 4000L, stockChange(3000L, Kind.IN, 1000L))
        assertEquals(-2000L to 1000L, stockChange(3000L, Kind.OUT, 2000L))
        assertEquals(-3000L to 0L, stockChange(3000L, Kind.COUNT, 0L))
        assertThrows(IllegalArgumentException::class.java) { stockChange(1000L, Kind.OUT, 1001L) }
        assertThrows(IllegalArgumentException::class.java) { stockChange(LIMIT, Kind.IN, 1L) }
        assertThrows(IllegalArgumentException::class.java) { stockChange(0L, Kind.IN, -1000L) }
    }

    @Test fun valuationAndMonthlyChangesUseExactValues() {
        val item = Item(name = "Vis", category = "Métal", buy = 1750, quantity = 2500)
        val other = Item(name = "Sac", quantity = 6000)
        val time = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()
        val moves = listOf(Movement(itemId = item.id, itemName = item.name, kind = Kind.IN, delta = 3500,
            time = time, unit = "kg"),
            Movement(itemId = item.id, itemName = item.name, kind = Kind.OUT, delta = -1000, time = time, unit = "kg"),
            Movement(itemId = item.id, itemName = item.name, kind = Kind.IN, delta = 9000, time = time - 1, unit = "kg"))
        val analysis = analyzeStock(listOf(item, other, item.copy(archived = true)), moves,
            YearMonth.of(2026, 11), ZoneOffset.UTC)
        assertEquals("4.375", analysis.valuation["EUR"]?.stripTrailingZeros()?.toPlainString())
        assertEquals(1, analysis.unpriced)
        assertEquals(1, analysis.categories["Métal"])
        assertEquals(listOf("Vis" to 2, "Sac" to 0), analysis.activity)
        assertEquals(StockVariation(3500, 1000), analysis.variation["kg"])
    }

    @Test fun validatesCsvAndRejectsDuplicates() {
        val rows = parseArticlesCsv("article;code;quantite;description\n\"Vis; M8\";ABC;2,5;\"Note\nligne\"")
        assertEquals("Vis; M8", rows.single().first.name)
        assertEquals("Note\nligne", rows.single().first.description)
        assertEquals(2500L, rows.single().second)
        assertThrows(IllegalArgumentException::class.java) { parseArticlesCsv("article;code\nA;ABC\nB;ABC") }
        assertThrows(IllegalArgumentException::class.java) { parseArticlesCsv("article;quantite\nA;-1") }
    }

    @Test fun licenseSignatureRejectsTamperedProof() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val key = Base64.getEncoder().encodeToString(pair.public.encoded)
        val payload = """{"productId":"simpletools_inventory_pro_lifetime"}"""
        val signature = Signature.getInstance("SHA1withRSA").run {
            initSign(pair.private)
            update(payload.toByteArray())
            Base64.getEncoder().encodeToString(sign())
        }
        assertTrue(verifyPlaySignature(key, payload, signature))
        assertFalse(verifyPlaySignature(key, payload + " ", signature))
        assertFalse(verifyPlaySignature("", payload, signature))
    }

    @Test fun csvEscaping() {
        assertEquals("\"Écrou; \"\"M8\"\"\"", Transfer.csvCell("Écrou; \"M8\""))
        assertEquals("\"'=SUM(1;2)\"", Transfer.csvCell("=SUM(1;2)"))
        assertEquals("\"-1.5\"", Transfer.csvCell("-1.5"))
    }
}
