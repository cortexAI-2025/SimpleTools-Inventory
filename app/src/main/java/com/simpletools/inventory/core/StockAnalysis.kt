package com.simpletools.inventory.core

import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

data class StockVariation(
    val incoming: Long = 0,
    val outgoing: Long = 0,
    val corrections: Long = 0
)

data class StockAnalysis(
    val valuation: Map<String, BigDecimal>,
    val unpriced: Int,
    val categories: Map<String, Int>,
    val activity: List<Pair<String, Int>>,
    val variation: Map<String, StockVariation>
)

fun analyzeStock(items: List<Item>, movements: List<Movement>, month: YearMonth, zone: ZoneId): StockAnalysis {
    val active = items.filterNot(Item::archived)
    val period = movements.filter { YearMonth.from(Instant.ofEpochMilli(it.time).atZone(zone)) == month }
    val activity = period.groupingBy(Movement::itemId).eachCount()
    val variation = period.groupBy { it.unit.orEmpty() }.mapValues { (_, group) ->
        StockVariation(
            incoming = group.filter { it.kind == Kind.IN }.sumOf(Movement::delta),
            outgoing = -group.filter { it.kind == Kind.OUT }.sumOf(Movement::delta),
            corrections = group.filter { it.kind == Kind.ADJUST || it.kind == Kind.COUNT }.sumOf(Movement::delta)
        )
    }
    val valuation = active.filter { it.buy != null }.groupBy(Item::currency).mapValues { (_, group) ->
        group.fold(BigDecimal.ZERO) { total, item ->
            total + BigDecimal.valueOf(item.quantity, 3) * BigDecimal.valueOf(requireNotNull(item.buy), 3)
        }
    }
    return StockAnalysis(valuation, active.count { it.buy == null },
        active.groupingBy(Item::category).eachCount(),
        active.map { it.name to (activity[it.id] ?: 0) }.sortedByDescending { it.second }, variation)
}
