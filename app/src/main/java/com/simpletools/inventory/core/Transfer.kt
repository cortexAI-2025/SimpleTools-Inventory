package com.simpletools.inventory.core

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.room.withTransaction
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

@Serializable
data class Snapshot(
    val schemaVersion: Int = 1,
    val format: String,
    val articles: List<Item>,
    val labels: List<Label>,
    val movements: List<Movement>,
    val sessions: List<Session>,
    val counts: List<Count>,
    val photos: Map<String, String> = emptyMap()
)

class Transfer(private val repo: InventoryRepository) {
    private val dao = repo.dao
    private val json = Json { encodeDefaults = true }

    suspend fun snapshot(backup: Boolean): Snapshot = repo.db.withTransaction {
        val articles = dao.items()
        val photos = mutableMapOf<String, String>()
        var bytes = 0
        if (backup) for (item in articles) {
            val photo = dao.photo(item.id) ?: continue
            bytes += photo.size
            require(bytes <= MAX_BYTES / 2)
            photos[item.id] = Base64.getEncoder().encodeToString(photo)
        }
        Snapshot(format = if (backup) "simpletools-inventory-backup" else "simpletools-inventory-report",
            articles = articles, labels = dao.allLabels(), movements = dao.movements(),
            sessions = dao.allSessions(), counts = dao.allCounts(), photos = photos)
    }

    suspend fun writeJson(output: OutputStream, backup: Boolean) {
        val bytes = json.encodeToString(Snapshot.serializer(), snapshot(backup)).toByteArray()
        if (backup) require(bytes.size <= MAX_BYTES)
        output.write(bytes)
    }

    suspend fun restore(input: InputStream) {
        val buffer = ByteArray(8192)
        val bounded = ByteArrayOutputStream()
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(bounded.size() + n <= MAX_BYTES)
            bounded.write(buffer, 0, n)
        }
        val bytes = bounded.toByteArray()
        require(bytes.size in 1..MAX_BYTES)
        val data = json.decodeFromString(Snapshot.serializer(), bytes.decodeToString(throwOnInvalidSequence = true))
        require(data.schemaVersion == 1 && data.format == "simpletools-inventory-backup")
        val ids = data.articles.map { it.id }.toSet()
        val sessions = data.sessions.map { it.id }.toSet()
        require(ids.size == data.articles.size && sessions.size == data.sessions.size)
        require(data.counts.distinctBy { it.sessionId to it.itemId }.size == data.counts.size)
        require(data.movements.distinctBy { it.id }.size == data.movements.size)
        require(data.labels.distinctBy { it.kind to it.name }.size == data.labels.size)
        require(data.articles.mapNotNull { it.code }.let { it.size == it.toSet().size })
        require(data.articles.mapNotNull { it.nfc }.let { it.size == it.toSet().size })
        data.articles.forEach { InventoryRepository.validateItem(it) }
        data.labels.forEach { require(it.kind in listOf("category", "location") && it.name.isNotBlank() && it.name.length <= 160) }
        data.sessions.forEach { uuid(it.id); require(it.name.isNotBlank() && it.name.length <= 160) }
        data.movements.forEach {
            uuid(it.id)
            uuid(it.itemId)
            require(it.delta in -LIMIT..LIMIT && it.delta != 0L)
            require(it.sessionId == null || it.sessionId in sessions)
        }
        data.counts.forEach {
            uuid(it.itemId)
            require(it.sessionId in sessions && it.expected in 0..LIMIT && it.actual?.let { n -> n in 0..LIMIT } != false)
        }
        val photos = data.photos.mapValues { (key, encoded) ->
            require(key in ids && encoded.length <= 950_000)
            Base64.getDecoder().decode(encoded).also { photo ->
                require(photo.size in 1..InventoryRepository.MAX_PHOTO_BYTES)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(photo, 0, photo.size, bounds)
                require(bounds.outMimeType == "image/jpeg" && bounds.outWidth in 1..1600 && bounds.outHeight in 1..1600)
            }
        }
        repo.db.withTransaction {
            // Refuse conflicts before writing. An identical import is idempotent.
            for (item in data.articles) {
                val existing = dao.item(item.id)
                require(existing == null || existing == item)
                if (existing != null) require(samePhoto(dao.photo(item.id), photos[item.id]))
                item.code?.let { require(dao.byCode(it)?.id in listOf(null, item.id)) }
                item.nfc?.let { require(dao.byNfc(it)?.id in listOf(null, item.id)) }
            }
            val currentSessions = dao.allSessions().associateBy { it.id }
            data.sessions.forEach { require(currentSessions[it.id]?.let { old -> old == it } != false) }
            val currentMovements = dao.movements().associateBy { it.id }
            data.movements.forEach { require(currentMovements[it.id]?.let { old -> old == it } != false) }
            val currentCounts = dao.allCounts().associateBy { it.sessionId to it.itemId }
            data.counts.forEach { require(currentCounts[it.sessionId to it.itemId]?.let { old -> old == it } != false) }
            data.labels.forEach { dao.label(it) }
            data.articles.forEach { dao.putItem(it) }
            data.sessions.forEach { dao.putSession(it) }
            data.movements.filter { it.id !in currentMovements }.forEach { dao.insertMovement(it) }
            data.counts.forEach { dao.putCount(it) }
            photos.forEach { (key, value) -> dao.putPhoto(Photo(key, value)) }
        }
    }

    suspend fun csv(output: OutputStream) {
        val s = snapshot(false)
        val writer = output.bufferedWriter()
        writer.write("\uFEFF")
        val headers = listOf("type", "id", "article", "code", "reference", "categorie", "emplacement",
            "quantite", "unite", "achat", "vente", "devise", "date", "session", "theorique", "compte", "ecart",
            "mouvement", "motif", "note", "archive")
        fun row(vararg values: Pair<String, String>) {
            val cells = values.toMap()
            writer.append(headers.joinToString(";") { csvCell(cells[it].orEmpty()) }).append("\r\n")
        }
        writer.append(headers.joinToString(";")).append("\r\n")
        s.articles.forEach {
            row("type" to "article", "id" to it.id, "article" to it.name, "code" to it.code.orEmpty(),
                "reference" to it.reference, "categorie" to it.category, "emplacement" to it.location,
                "quantite" to decimal(it.quantity), "unite" to it.unit, "achat" to it.buy?.let(::decimal).orEmpty(),
                "vente" to it.sell?.let(::decimal).orEmpty(), "devise" to it.currency, "archive" to it.archived.toString())
        }
        s.movements.forEach {
            row("type" to "mouvement", "id" to it.itemId, "article" to it.itemName, "quantite" to decimal(it.delta),
                "date" to Instant.ofEpochMilli(it.time).toString(), "session" to it.sessionId.orEmpty(),
                "mouvement" to it.kind.name, "motif" to it.reason, "note" to it.note)
        }
        s.counts.forEach {
            row("type" to "comptage", "id" to it.itemId, "article" to it.name, "session" to it.sessionId,
                "unite" to it.unit, "theorique" to decimal(it.expected),
                "compte" to it.actual?.let(::decimal).orEmpty(), "ecart" to it.actual?.let { n -> decimal(n - it.expected) }.orEmpty())
        }
        writer.flush()
    }

    suspend fun pdf(output: OutputStream, french: Boolean, sessionId: String? = null) {
        fun tr(fr: String, en: String) = if (french) fr else en
        val s = snapshot(false)
        val items = s.articles.filterNot { it.archived }
        val session = s.sessions.find { it.id == sessionId }
        val title = session?.name ?: tr("Rapport de stock", "Stock report")
        val date = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.now())
        val doc = PdfDocument()
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f }
            var pageNo = 0
            var page: PdfDocument.Page? = null
            var y = 0f
            fun newPage() {
                page?.let(doc::finishPage)
                pageNo++
                val current = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNo).create())
                page = current
                paint.color = Color.rgb(14, 96, 84)
                current.canvas.drawRoundRect(34f, 30f, 65f, 57f, 6f, 6f, paint)
                paint.color = Color.WHITE
                paint.typeface = Typeface.DEFAULT_BOLD
                current.canvas.drawText("ST", 43f, 48f, paint)
                paint.color = Color.BLACK
                current.canvas.drawText("SimpleTools Inventory · AIWORKPAY", 76f, 48f, paint)
                current.canvas.drawText(date, 34f, 75f, paint)
                current.canvas.drawText("Page $pageNo", 34f, 809f, paint)
                paint.typeface = Typeface.DEFAULT
                y = 100f
            }
            fun line(text: String) {
                var rest = text.replace(Regex("[\\r\\n\\t]"), " ")
                if (rest.isEmpty()) rest = " "
                while (rest.isNotEmpty()) {
                    if (page == null || y > 780f) newPage()
                    val n = paint.breakText(rest, true, 520f, null).coerceAtLeast(1)
                    requireNotNull(page).canvas.drawText(rest.take(n), 34f, y, paint)
                    y += 16f
                    rest = rest.drop(n)
                }
            }
            line(title)
            line(tr("Références actives", "Active items") + " · " + items.size)
            items.groupBy { it.unit }.forEach { (unit, group) -> line(decimal(group.sumOf { it.quantity }) + " " + unit) }
            if (sessionId == null) {
                line(tr("Articles", "Items"))
                items.forEach { line("${it.name} | ${decimal(it.quantity)} ${it.unit} | ${it.category} | ${it.location}") }
                line(tr("Alertes", "Alerts"))
                items.filter { it.quantity == 0L || it.quantity < it.threshold }.forEach {
                    line("${it.name} | ${decimal(it.quantity)} / ${decimal(it.threshold)} ${it.unit}")
                }
            }
            val sessions = if (sessionId == null) s.sessions else listOfNotNull(session)
            for (header in sessions) {
                line(tr("Comptage", "Count") + " · " + header.name)
                line(tr("Article | Théorique | Compté | Écart", "Item | Expected | Counted | Difference"))
                s.counts.filter { it.sessionId == header.id }.forEach {
                    val actual = it.actual
                    line("${it.name} | ${decimal(it.expected)} ${it.unit} | " +
                        if (actual == null) tr("Non compté", "Uncounted")
                        else "${decimal(actual)} | ${decimal(actual - it.expected)}")
                }
            }
            page?.let(doc::finishPage)
            doc.writeTo(output)
        } finally {
            doc.close()
        }
    }

    companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
        fun csvCell(value: String): String {
            val dangerous = value.trimStart().firstOrNull() in listOf('=', '+', '-', '@') &&
                !value.matches(Regex("-?[0-9]+(\\.[0-9]+)?")) || value.firstOrNull() in listOf('\t', '\r', '\n')
            val safe = if (dangerous) "'$value" else value
            return "\"${safe.replace("\"", "\"\"")}\""
        }
        private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
        private fun samePhoto(a: ByteArray?, b: ByteArray?) = if (a == null) b == null else b != null && a.contentEquals(b)
    }
}
