package com.simpletools.inventory.core

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.util.UUID

const val LIMIT = 1_000_000_000_000L
fun id(): String = UUID.randomUUID().toString()
fun scaled(value: String): Long =
    BigDecimal(value.trim().replace(',', '.')).movePointRight(3).longValueExact()
        .also { require(it in -LIMIT..LIMIT) }
fun decimal(value: Long): String = BigDecimal.valueOf(value, 3).stripTrailingZeros().toPlainString()

@Serializable
@Entity(indices = [Index(value = ["code"], unique = true), Index(value = ["nfc"], unique = true),
    Index("name"), Index("reference"), Index("category"), Index("location"), Index("modified")])
data class Item(
    @PrimaryKey val id: String = id(),
    val name: String,
    val description: String = "",
    val category: String = "",
    val code: String? = null,
    val reference: String = "",
    val nfc: String? = null,
    val quantity: Long = 0,
    val unit: String = "pièce",
    val threshold: Long = 0,
    val location: String = "",
    val buy: Long? = null,
    val sell: Long? = null,
    val currency: String = "EUR",
    val created: Long = System.currentTimeMillis(),
    val modified: Long = created,
    val notes: String = "",
    val archived: Boolean = false,
    @ColumnInfo(defaultValue = "''") val inventory: String = "",
    @ColumnInfo(defaultValue = "''") val supplier: String = ""
)

@Serializable
@Entity(indices = [Index("itemId")], foreignKeys = [ForeignKey(
    entity = Item::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE
)])
data class AlternateCode(@PrimaryKey val value: String, val itemId: String)

@Serializable enum class Kind { IN, OUT, ADJUST, COUNT }

fun stockChange(current: Long, kind: Kind, amount: Long): Pair<Long, Long> {
    require(current in 0..LIMIT && amount in -LIMIT..LIMIT)
    require(kind !in listOf(Kind.IN, Kind.OUT) || amount > 0)
    val delta = when (kind) {
        Kind.OUT -> -amount
        Kind.COUNT -> amount - current
        else -> amount
    }
    val next = current + delta
    require(next in 0..LIMIT) { "quantity" }
    return delta to next
}

@Serializable
@Entity(indices = [Index(value = ["itemId", "time"]), Index("sessionId")])
data class Movement(
    @PrimaryKey val id: String = id(),
    val itemId: String,
    val itemName: String,
    val kind: Kind,
    val delta: Long,
    val time: Long = System.currentTimeMillis(),
    val reason: String = "",
    val note: String = "",
    val sessionId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val unit: String? = null
)

@Serializable
@Entity(primaryKeys = ["kind", "name"])
data class Label(val kind: String, val name: String)

@Serializable
@Entity
data class Session(
    @PrimaryKey val id: String = id(),
    val name: String,
    val created: Long = System.currentTimeMillis(),
    val closed: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val inventory: String? = null
)

@Serializable
@Entity(primaryKeys = ["sessionId", "itemId"], indices = [Index("itemId")])
data class Count(
    val sessionId: String,
    val itemId: String,
    val name: String,
    val expected: Long,
    val actual: Long? = null,
    val unit: String
)

@Entity
data class Photo(@PrimaryKey val itemId: String, val jpeg: ByteArray)

data class UnitTotal(val unit: String, val quantity: Long)

@Dao
interface InventoryDao {
    @Query("""
        SELECT * FROM Item WHERE archived = :archived AND (:inventory IS NULL OR inventory = :inventory) AND
        (instr(lower(name), lower(:term)) > 0 OR instr(lower(reference), lower(:term)) > 0
            OR instr(lower(coalesce(code, '')), lower(:term)) > 0
            OR EXISTS (SELECT 1 FROM AlternateCode WHERE itemId = Item.id AND instr(lower(value), lower(:term)) > 0)
            OR instr(lower(category), lower(:term)) > 0 OR instr(lower(location), lower(:term)) > 0)
        AND (:filter = 0 OR (:filter = 1 AND quantity < threshold) OR (:filter = 2 AND quantity = 0))
        ORDER BY CASE WHEN :sort = 1 THEN quantity END,
            CASE WHEN :sort = 2 THEN modified END DESC, name COLLATE NOCASE, id
        LIMIT :limit OFFSET :offset
    """)
    fun search(term: String, filter: Int, sort: Int, archived: Boolean, limit: Int, offset: Int, inventory: String? = null): Flow<List<Item>>

    @Query("SELECT * FROM Item ORDER BY name COLLATE NOCASE, id") suspend fun items(): List<Item>
    @Query("SELECT * FROM Item ORDER BY name COLLATE NOCASE, id") fun observeItems(): Flow<List<Item>>
    @Query("SELECT count(*) FROM Item") suspend fun itemCount(): Int
    @Query("SELECT count(*) FROM Item") fun catalogSize(): Flow<Int>
    @Query("SELECT count(*) FROM Item WHERE archived = 0") fun references(): Flow<Int>
    @Query("SELECT unit, sum(quantity) AS quantity FROM Item WHERE archived = 0 GROUP BY unit ORDER BY unit")
    fun totals(): Flow<List<UnitTotal>>
    @Query("SELECT count(*) FROM Item WHERE archived = 0 AND quantity < threshold") fun low(): Flow<Int>
    @Query("SELECT count(*) FROM Item WHERE archived = 0 AND quantity = 0") fun empty(): Flow<Int>
    @Query("SELECT * FROM Item WHERE id = :id") suspend fun item(id: String): Item?
    @Query("SELECT * FROM Item WHERE id = :id") fun observeItem(id: String): Flow<Item?>
    @Query("SELECT * FROM Item WHERE code = :value OR id = (SELECT itemId FROM AlternateCode WHERE value = :value)") suspend fun byCode(value: String): Item?
    @Query("SELECT * FROM Item WHERE nfc = :value") suspend fun byNfc(value: String): Item?
    @Query("SELECT value FROM AlternateCode WHERE itemId = :id ORDER BY value") fun codes(id: String): Flow<List<String>>
    @Query("SELECT * FROM AlternateCode") suspend fun allCodes(): List<AlternateCode>
    @Insert suspend fun insertCode(code: AlternateCode)
    @Query("DELETE FROM AlternateCode WHERE itemId = :id AND value = :value") suspend fun deleteCode(id: String, value: String)
    @Upsert suspend fun putItem(item: Item)
    @Query("DELETE FROM Item WHERE id = :id") suspend fun deleteItem(id: String)
    @Query("SELECT * FROM Movement ORDER BY time DESC, id") suspend fun movements(): List<Movement>
    @Query("SELECT * FROM Movement ORDER BY time DESC, id") fun observeMovements(): Flow<List<Movement>>
    @Query("SELECT * FROM Movement ORDER BY time DESC, id LIMIT 8") fun recent(): Flow<List<Movement>>
    @Query("SELECT * FROM Movement WHERE itemId = :id ORDER BY time DESC, id") fun history(id: String): Flow<List<Movement>>
    @Insert suspend fun insertMovement(movement: Movement)
    @Query("SELECT * FROM Label ORDER BY name COLLATE NOCASE") fun labels(): Flow<List<Label>>
    @Query("SELECT * FROM Label ORDER BY name COLLATE NOCASE") suspend fun allLabels(): List<Label>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun label(label: Label)
    @Query("DELETE FROM Label WHERE kind = :kind AND name = :name") suspend fun deleteLabel(kind: String, name: String)
    @Query("SELECT * FROM Session ORDER BY created DESC") fun sessions(): Flow<List<Session>>
    @Query("SELECT * FROM Session ORDER BY created DESC") suspend fun allSessions(): List<Session>
    @Query("SELECT * FROM Session WHERE id = :id") suspend fun session(id: String): Session?
    @Upsert suspend fun putSession(session: Session)
    @Query("SELECT * FROM Count WHERE sessionId = :id ORDER BY name COLLATE NOCASE, itemId") fun counts(id: String): Flow<List<Count>>
    @Query("SELECT * FROM Count WHERE sessionId = :session AND itemId = :item") suspend fun count(session: String, item: String): Count?
    @Query("SELECT * FROM Count WHERE sessionId = :id ORDER BY name COLLATE NOCASE, itemId") suspend fun sessionCounts(id: String): List<Count>
    @Query("SELECT * FROM Count") suspend fun allCounts(): List<Count>
    @Upsert suspend fun putCount(count: Count)
    @Query("SELECT jpeg FROM Photo WHERE itemId = :id") suspend fun photo(id: String): ByteArray?
    @Query("SELECT jpeg FROM Photo WHERE itemId = :id") fun observePhoto(id: String): Flow<ByteArray?>
    @Upsert suspend fun putPhoto(photo: Photo)
    @Query("DELETE FROM Photo WHERE itemId = :id") suspend fun deletePhoto(id: String)
}

@Database(entities = [Item::class, Movement::class, Label::class, Session::class, Count::class, Photo::class, AlternateCode::class],
    version = 2, exportSchema = false)
abstract class InventoryDatabase : RoomDatabase() {
    abstract fun inventory(): InventoryDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE Item ADD COLUMN inventory TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE Item ADD COLUMN supplier TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE Session ADD COLUMN inventory TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE Movement ADD COLUMN unit TEXT DEFAULT NULL")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS AlternateCode (
                value TEXT NOT NULL PRIMARY KEY, itemId TEXT NOT NULL,
                FOREIGN KEY(itemId) REFERENCES Item(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_AlternateCode_itemId ON AlternateCode(itemId)")
    }
}

class InventoryRepository(val db: InventoryDatabase, private val licensed: () -> Boolean = { false }) {
    fun requirePro() { check(licensed()) { "pro" } }
    val dao = db.inventory()

    suspend fun save(item: Item, initial: Long = 0, photo: ByteArray? = null, replacePhoto: Boolean = false, duplicate: Boolean = false): Item =
        db.withTransaction {
            val old = dao.item(item.id)
            if (old == null && !licensed()) check(canCreateFree(dao.itemCount())) { "free_limit" }
            if (duplicate || (item.archived && old?.archived != true) ||
                item.threshold != (old?.threshold ?: 0L) || item.inventory != (old?.inventory ?: "") ||
                item.supplier != (old?.supplier ?: "")) requirePro()
            val normalized = item.copy(name = item.name.trim(), code = item.code?.trim()?.ifEmpty { null },
                nfc = item.nfc?.trim()?.ifEmpty { null }, quantity = old?.quantity ?: 0,
                created = old?.created ?: item.created, modified = System.currentTimeMillis())
            validateItem(normalized)
            require(initial in 0..LIMIT)
            require(normalized.code == null || normalized.code == old?.code ||
                normalized.code !in dao.codes(item.id).first()) { "code" }
            require(normalized.code?.let { dao.byCode(it)?.id in listOf(null, item.id) } != false) { "code" }
            require(normalized.nfc?.let { dao.byNfc(it)?.id in listOf(null, item.id) } != false) { "nfc" }
            require(old == null || old.unit == normalized.unit || old.quantity == 0L) { "unit" }
            dao.putItem(normalized)
            if (replacePhoto) {
                if (photo == null) dao.deletePhoto(item.id)
                else {
                    require(photo.size in 1..MAX_PHOTO_BYTES)
                    dao.putPhoto(Photo(item.id, photo))
                }
            }
            if (old == null && initial > 0) applyMovement(normalized, Kind.IN, initial, "", "", null)
            requireNotNull(dao.item(item.id))
        }

    suspend fun addLabel(kind: String, name: String) = db.withTransaction {
        val normalized = name.trim()
        require(kind in listOf("category", "location", "inventory") && normalized.length in 1..160)
        if (kind == "inventory") requirePro()
        val label = Label(kind, normalized)
        if (!licensed()) {
            val existing = dao.allLabels()
            val limit = if (kind == "category") FREE_ITEMS else 1
            check(label in existing || existing.count { it.kind == kind } < limit) { "label_limit" }
        }
        dao.label(label)
    }

    suspend fun createLabelCode(itemId: String): Item = db.withTransaction {
        requirePro()
        val item = requireNotNull(dao.item(itemId))
        if (item.code != null) item else save(item.copy(code = item.id))
    }

    suspend fun addCode(id: String, value: String) = db.withTransaction {
        requirePro()
        val code = value.trim()
        require(code.isNotBlank() && code.length <= 2048 && dao.item(id) != null)
        require(dao.byCode(code) == null) { "code" }
        dao.insertCode(AlternateCode(code, id))
    }

    suspend fun removeCode(id: String, value: String) = db.withTransaction {
        requirePro()
        dao.deleteCode(id, value)
    }

    suspend fun delete(id: String) = db.withTransaction {
        dao.deletePhoto(id)
        dao.deleteItem(id)
    }

    suspend fun move(id: String, kind: Kind, amount: Long, reason: String = "", note: String = "") =
        db.withTransaction {
            require(kind != Kind.COUNT)
            if (kind == Kind.ADJUST) {
                requirePro()
                require(reason.isNotBlank()) { "reason" }
            }
            applyMovement(requireNotNull(dao.item(id)), kind, amount, reason, note, null)
        }

    private suspend fun applyMovement(item: Item, kind: Kind, amount: Long, reason: String, note: String, session: String?) {
        require(!item.archived)
        val (delta, next) = stockChange(item.quantity, kind, amount)
        if (delta == 0L) return
        dao.insertMovement(Movement(itemId = item.id, itemName = item.name, kind = kind,
            delta = delta, reason = reason, note = note, sessionId = session, unit = item.unit))
        dao.putItem(item.copy(quantity = next, modified = System.currentTimeMillis()))
    }

    suspend fun start(name: String, inventory: String? = null): Session = db.withTransaction {
        requirePro()
        require(name.isNotBlank() && name.length <= 160)
        val session = Session(name = name.trim(), inventory = inventory)
        dao.putSession(session)
        dao.items().filter { !it.archived && (inventory == null || it.inventory == inventory) }.forEach {
            dao.putCount(Count(session.id, it.id, it.name, it.quantity, unit = it.unit))
        }
        session
    }

    suspend fun count(session: String, item: String, actual: Long) = db.withTransaction {
        requirePro()
        require(actual in 0..LIMIT)
        require(requireNotNull(dao.session(session)).closed == null)
        val row = requireNotNull(dao.count(session, item))
        dao.putCount(row.copy(actual = actual))
    }

    suspend fun finalize(session: String) = db.withTransaction {
        requirePro()
        val header = requireNotNull(dao.session(session))
        require(header.closed == null)
        val counted = dao.sessionCounts(session).filter { it.actual != null }
        require(counted.isNotEmpty())
        for (row in counted) {
            val item = requireNotNull(dao.item(row.itemId))
            require(item.quantity == row.expected && item.unit == row.unit &&
                (header.inventory == null || header.inventory == item.inventory)) { "changed" }
            applyMovement(item, Kind.COUNT, requireNotNull(row.actual), header.name, "", session)
        }
        dao.putSession(header.copy(closed = System.currentTimeMillis()))
    }

    companion object {
        const val FREE_ITEMS = 50
        fun canCreateFree(current: Int): Boolean = current < FREE_ITEMS
        const val MAX_PHOTO_BYTES = 700_000
        fun validateItem(item: Item) {
            require(UUID.fromString(item.id).toString() == item.id)
            require(item.name.isNotBlank() && item.name.length <= 160)
            require(item.unit.isNotBlank() && item.unit.length <= 40)
            require(item.inventory.length <= 160 && item.supplier.length <= 160)
            require(item.category.length <= 160 && item.location.length <= 160 && item.reference.length <= 160)
            require(item.description.length <= 4000 && item.notes.length <= 4000)
            require(item.quantity in 0..LIMIT && item.threshold in 0..LIMIT)
            require(item.buy?.let { it in 0..LIMIT } != false && item.sell?.let { it in 0..LIMIT } != false)
            require(item.currency.matches(Regex("[A-Z]{3}")))
            require(item.code?.let { it.isNotBlank() && it.length <= 2048 } != false)
            require(item.nfc?.let { it.isNotBlank() && it.length <= 512 } != false)
        }
    }
}
