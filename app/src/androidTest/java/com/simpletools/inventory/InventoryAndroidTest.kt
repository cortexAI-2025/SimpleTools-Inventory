package com.simpletools.inventory

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.nfc.NdefRecord
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.simpletools.inventory.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class InventoryAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun database() = Room.inMemoryDatabaseBuilder(context, InventoryDatabase::class.java).build()

    private inline fun <T> InventoryDatabase.useDatabase(block: (InventoryDatabase) -> T): T =
        try { block(this) } finally { close() }

    @Test fun catalogSearchAndStock() = runBlocking {
        database().useDatabase { db ->
            val repo = InventoryRepository(db) { true }
            val item = repo.save(Item(name = "Vis", code = "123", reference = "R1", category = "Métal",
                location = "Atelier", threshold = scaled("30")), scaled("20"))
            repo.save(item.copy(name = "Vis inox"))
            assertTrue(runCatching { repo.save(Item(name = "Doublon", code = "123")) }.isFailure)
            repo.move(item.id, Kind.IN, scaled("10"))
            repo.move(item.id, Kind.OUT, scaled("4"))
            assertEquals(scaled("26"), repo.dao.item(item.id)?.quantity)
            repo.move(item.id, Kind.ADJUST, scaled("-1,5"), "Correction manuelle")
            assertEquals(scaled("24.5"), repo.dao.item(item.id)?.quantity)
            assertTrue(runCatching { repo.move(item.id, Kind.OUT, scaled("100")) }.isFailure)
            assertEquals(1, repo.dao.low().first())
            assertEquals(0, repo.dao.empty().first())
            for (term in listOf("inox", "R1", "123", "Métal", "Atelier")) {
                assertEquals(item.id, repo.dao.search(term, 1, 1, false, 10, 0).first().single().id)
            }
            assertTrue(repo.dao.search("", 2, 0, false, 10, 0).first().isEmpty())
            repo.delete(item.id)
            assertNull(repo.dao.item(item.id))
            assertEquals(4, repo.dao.history(item.id).first().size)
        }
    }

    @Test fun transactionsAndPhysicalCount() = runBlocking {
        database().useDatabase { db ->
            val repo = InventoryRepository(db) { true }
            val item = repo.save(Item(name = "Boîte"))
            (1..20).map { async(Dispatchers.IO) { repo.move(item.id, Kind.IN, scaled("1")) } }.awaitAll()
            val session = repo.start("Réserve")
            repo.count(session.id, item.id, scaled("17"))
            val row = repo.dao.counts(session.id).first().single()
            assertEquals(scaled("-3"), requireNotNull(row.actual) - row.expected)
            assertEquals(scaled("20"), repo.dao.item(item.id)?.quantity)
            repo.finalize(session.id)
            assertEquals(scaled("17"), repo.dao.item(item.id)?.quantity)
            assertEquals(1, repo.dao.history(item.id).first().count { it.kind == Kind.COUNT })
            assertTrue(runCatching { repo.finalize(session.id) }.isFailure)
            val stale = repo.start("Contrôle")
            repo.count(stale.id, item.id, 0)
            repo.move(item.id, Kind.IN, 1000)
            assertTrue(runCatching { repo.finalize(stale.id) }.isFailure)
            assertNull(repo.dao.session(stale.id)?.closed)
        }
    }

    @Test fun exportRestoreAndPersistence() = runBlocking {
        val dbName = "inventory-test-${id()}.db"
        try {
            val image = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            val photo = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
            image.recycle()
            val backup = ByteArrayOutputStream()
            var itemId = ""
            database().useDatabase { db ->
                val repo = InventoryRepository(db) { true }
                itemId = repo.save(Item(name = "Étagère", code = "789"), 1250, photo, true).id
                repo.addCode(itemId, "789-alias")
                val transfer = Transfer(repo)
                assertTrue(ByteArrayOutputStream().also { transfer.csv(it) }.toString("UTF-8").contains("Étagère"))
                assertTrue(ByteArrayOutputStream().also { transfer.writeJson(it, false) }.toString("UTF-8").contains("schemaVersion"))
                assertTrue(ByteArrayOutputStream().also { transfer.pdf(it, true) }.toString("ISO-8859-1").startsWith("%PDF-"))
                transfer.writeJson(backup, true)
            }
            Room.databaseBuilder(context, InventoryDatabase::class.java, dbName).build().useDatabase { db ->
                val repo = InventoryRepository(db) { true }
                val transfer = Transfer(repo)
                transfer.restore(ByteArrayInputStream(backup.toByteArray()))
                transfer.restore(ByteArrayInputStream(backup.toByteArray()))
                assertTrue(runCatching { transfer.restore(ByteArrayInputStream("{broken".toByteArray())) }.isFailure)
                assertEquals(1, repo.dao.items().size)
                assertArrayEquals(photo, repo.dao.photo(itemId))
                assertEquals(itemId, repo.dao.byCode("789-alias")?.id)
                assertEquals(1, repo.dao.movements().size)
            }
            Room.databaseBuilder(context, InventoryDatabase::class.java, dbName).build().useDatabase { db ->
                assertEquals(1250L, db.inventory().item(itemId)?.quantity)
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test fun freeLimitIsTransactionalAndRevocationKeepsExistingData() = runBlocking {
        database().useDatabase { db ->
            var pro = false
            val repo = InventoryRepository(db) { pro }
            val first = repo.save(Item(name = "Article initial"))
            (2..49).forEach { repo.save(Item(name = "Article $it")) }
            val attempts = (50..51).map { i ->
                async(Dispatchers.IO) { runCatching { repo.save(Item(name = "Article $i")) } }
            }.awaitAll()
            assertEquals(1, attempts.count { it.isSuccess })
            assertEquals("free_limit", attempts.first { it.isFailure }.exceptionOrNull()?.message)
            assertEquals(50, repo.dao.itemCount())
            pro = true
            val extra = repo.save(Item(name = "Au-delà de la limite", inventory = "Dépôt"), 4000)
            repo.addCode(extra.id, "BIS")
            assertEquals(extra.id, repo.dao.byCode("BIS")?.id)
            assertEquals(extra.id, repo.dao.search("BIS", 0, 0, false, 10, 0, "Dépôt").first().single().id)
            assertEquals("code", runCatching { repo.save(first.copy(code = "BIS")) }.exceptionOrNull()?.message)
            pro = false
            repo.save(extra.copy(name = "Toujours modifiable"))
            repeat(55) { repo.move(extra.id, Kind.IN, 1000) }
            repo.move(extra.id, Kind.OUT, 3000)
            assertEquals(56_000L, repo.dao.item(extra.id)?.quantity)
            assertEquals(57, repo.dao.history(extra.id).first().size)
            assertEquals("pro", runCatching { repo.move(extra.id, Kind.ADJUST, 1000, "Motif") }.exceptionOrNull()?.message)
            assertEquals("pro", runCatching { repo.start("Comptage") }.exceptionOrNull()?.message)
            assertEquals("pro", runCatching { repo.createLabelCode(first.id) }.exceptionOrNull()?.message)
            assertEquals("pro", runCatching { Transfer(repo).csv(ByteArrayOutputStream()) }.exceptionOrNull()?.message)
            assertEquals("free_limit", runCatching { repo.save(Item(name = "Bloqué")) }.exceptionOrNull()?.message)
            assertEquals(51, repo.dao.itemCount())
        }
    }

    @Test fun csvImportChecksConflictsBeforeWritingAndLabelUsesCatalogCode() = runBlocking {
        database().useDatabase { db ->
            val repo = InventoryRepository(db) { true }
            val transfer = Transfer(repo)
            repo.save(Item(name = "Existant", code = "A"))
            fun csv(rows: String) = ByteArrayInputStream(("article;code;quantite;inventaire\n" + rows).toByteArray())
            assertTrue(runCatching { transfer.importCsv(csv("Valide;B;2;Dépôt\nCollision;A;1;Dépôt")) }.isFailure)
            assertNull(repo.dao.byCode("B"))
            assertEquals(1, repo.dao.itemCount())
            assertEquals(1, transfer.importCsv(csv("Valide;B;2,5;Dépôt")))
            val imported = requireNotNull(repo.dao.byCode("B"))
            assertEquals(2500L, imported.quantity)
            assertEquals("Dépôt", imported.inventory)
            assertEquals(1, repo.dao.history(imported.id).first().size)
            val fresh = repo.save(Item(name = "Étiquette"))
            val labeled = repo.createLabelCode(fresh.id)
            assertEquals(labeled.id, repo.dao.byCode(labeled.labelCode())?.id)
            val bytes = ByteArrayOutputStream()
            writeProductLabel(labeled, bytes)
            assertTrue(bytes.toString("ISO-8859-1").startsWith("%PDF-"))
            assertPrintedLabelDecodes(labeled.labelCode(), bytes.toByteArray())
        }
    }

    private fun assertPrintedLabelDecodes(expected: String, pdf: ByteArray) {
        val file = File.createTempFile("inventory-label", ".pdf", context.cacheDir)
        try {
            file.writeBytes(pdf)
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                    val client = BarcodeScanning.getClient()
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val result = Tasks.await(client.process(InputImage.fromBitmap(bitmap, 0)), 20, TimeUnit.SECONDS)
                        assertTrue(result.any { it.rawValue == expected })
                    } finally {
                        client.close()
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test fun nfcIdentityAndLookup() = runBlocking {
        assertEquals(NfcStatus.ABSENT, nfcStatus(null))
        val uid = byteArrayOf(8, 1, 2, 3)
        assertNull(TagIdentity.identify(emptyArray(), uid))
        val record = NdefRecord.createMime(TagIdentity.MIME, "shelf-17".toByteArray())
        val value = TagIdentity.identify(arrayOf(record), uid)
        assertEquals("ndef:shelf-17", value)
        database().useDatabase { db ->
            val repo = InventoryRepository(db) { true }
            val item = repo.save(Item(name = "Bac", nfc = value))
            assertEquals(item.id, repo.dao.byNfc(requireNotNull(value))?.id)
        }
    }

    @Test fun bundledDecoderReadsQrAndEanFixtures() {
        val client = BarcodeScanning.getClient()
        try {
            for ((format, expected) in listOf(BarcodeFormat.QR_CODE to "inventory:fixture-01",
                BarcodeFormat.EAN_13 to "5901234123457")) {
                val matrix = MultiFormatWriter().encode(expected, format, 720, 360)
                val pixels = IntArray(matrix.width * matrix.height) { i ->
                    if (matrix[i % matrix.width, i / matrix.width]) Color.BLACK else Color.WHITE
                }
                val bitmap = Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
                try {
                    val result = Tasks.await(client.process(InputImage.fromBitmap(bitmap, 0)), 20, TimeUnit.SECONDS)
                    assertTrue(result.any { it.rawValue == expected })
                } finally { bitmap.recycle() }
            }
        } finally { client.close() }
    }
}
