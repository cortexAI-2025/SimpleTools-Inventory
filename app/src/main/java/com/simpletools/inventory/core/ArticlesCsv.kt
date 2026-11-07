package com.simpletools.inventory.core

import java.util.Locale

/** Parses the UTF-8, semicolon-delimited catalog format used by Inventory and Excel. */
fun parseArticlesCsv(text: String): List<Pair<Item, Long>> {
    val rows = mutableListOf<List<String>>()
    val row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var quoteClosed = false
    val source = text.removePrefix("\uFEFF")
    var index = 0

    fun cell() {
        row.add(field.toString())
        field.clear()
        quoteClosed = false
    }
    fun finishRow() {
        cell()
        if (row.any { it.isNotBlank() }) rows.add(row.toList())
        row.clear()
    }

    while (index < source.length) {
        val char = source[index++]
        when {
            quoted && char == '"' -> {
                if (source.getOrNull(index) == '"') {
                    index++
                    field.append('"')
                } else {
                    quoted = false
                    quoteClosed = true
                }
            }
            quoted -> field.append(char)
            char == ';' -> cell()
            char == '\r' || char == '\n' -> {
                if (char == '\r' && source.getOrNull(index) == '\n') index++
                finishRow()
            }
            char == '"' && field.isEmpty() && !quoteClosed -> quoted = true
            char == '"' || quoteClosed -> error("csv")
            else -> field.append(char)
        }
        require(field.length <= 16_000 && row.size <= 40 && rows.size <= 20_001) { "csv" }
    }
    require(!quoted) { "csv" }
    if (row.isNotEmpty() || field.isNotEmpty() || quoteClosed) finishRow()
    val headers = rows.firstOrNull()?.map { it.trim().lowercase(Locale.ROOT) }.orEmpty()
    require("article" in headers && headers.size == headers.distinct().size) { "csv" }
    val indexes = headers.withIndex().associate { it.value to it.index }
    val parsed = rows.drop(1).mapNotNull { cells ->
        require(cells.size == headers.size) { "csv" }
        fun value(key: String) = indexes[key]?.let(cells::get).orEmpty().trim()
        fun number(key: String) = value(key).takeIf { it.isNotEmpty() }?.let(::scaled)
        when (value("type")) {
            "mouvement", "comptage" -> return@mapNotNull null
            "", "article" -> Unit
            else -> error("csv")
        }
        val quantity = number("quantite") ?: 0
        require(quantity in 0..LIMIT) { "quantity" }
        val item = Item(name = value("article"), code = value("code").ifBlank { null }, reference = value("reference"),
            category = value("categorie"), location = value("emplacement"), description = value("description"),
            inventory = value("inventaire"), supplier = value("fournisseur"), unit = value("unite").ifBlank { "pièce" },
            threshold = number("seuil") ?: 0, buy = number("achat"), sell = number("vente"),
            currency = value("devise").ifBlank { "EUR" }.uppercase(Locale.ROOT))
        InventoryRepository.validateItem(item)
        item to quantity
    }
    val codes = parsed.mapNotNull { it.first.code }
    require(parsed.isNotEmpty() && codes.distinct().size == codes.size) { "csv" }
    return parsed
}
