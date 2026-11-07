package com.simpletools.inventory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.simpletools.inventory.core.InventoryDatabase
import com.simpletools.inventory.core.MIGRATION_1_2
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InventoryMigrationTest {
    @Test fun migratesV1WithoutDeletingCatalogOrHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "inventory-migration.db"
        context.deleteDatabase(name)
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("""
                    CREATE TABLE Item (
                        id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL,
                        category TEXT NOT NULL, code TEXT, reference TEXT NOT NULL, nfc TEXT, quantity INTEGER NOT NULL,
                        unit TEXT NOT NULL, threshold INTEGER NOT NULL, location TEXT NOT NULL, buy INTEGER, sell INTEGER,
                        currency TEXT NOT NULL, created INTEGER NOT NULL, modified INTEGER NOT NULL,
                        notes TEXT NOT NULL, archived INTEGER NOT NULL
                    )
                """.trimIndent())
                listOf("name", "reference", "category", "location", "modified").forEach {
                    db.execSQL("CREATE INDEX index_Item_$it ON Item($it)")
                }
                db.execSQL("CREATE UNIQUE INDEX index_Item_code ON Item(code)")
                db.execSQL("CREATE UNIQUE INDEX index_Item_nfc ON Item(nfc)")
                db.execSQL("""
                    CREATE TABLE Movement (
                        id TEXT NOT NULL PRIMARY KEY, itemId TEXT NOT NULL, itemName TEXT NOT NULL, kind TEXT NOT NULL,
                        delta INTEGER NOT NULL, time INTEGER NOT NULL, reason TEXT NOT NULL, note TEXT NOT NULL, sessionId TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX index_Movement_itemId_time ON Movement(itemId, time)")
                db.execSQL("CREATE INDEX index_Movement_sessionId ON Movement(sessionId)")
                db.execSQL("CREATE TABLE Label (kind TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(kind, name))")
                db.execSQL("CREATE TABLE Session (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, created INTEGER NOT NULL, closed INTEGER)")
                db.execSQL("""
                    CREATE TABLE Count (
                        sessionId TEXT NOT NULL, itemId TEXT NOT NULL, name TEXT NOT NULL, expected INTEGER NOT NULL,
                        actual INTEGER, unit TEXT NOT NULL, PRIMARY KEY(sessionId, itemId)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX index_Count_itemId ON Count(itemId)")
                db.execSQL("CREATE TABLE Photo (itemId TEXT NOT NULL PRIMARY KEY, jpeg BLOB NOT NULL)")
                db.execSQL("""
                    INSERT INTO Item VALUES
                    ('old-item','Ancien article','Description','Métal','ABC','REF',NULL,6000,'kg',8000,'Réserve',3500,NULL,'EUR',1,2,'',0)
                """.trimIndent())
                db.execSQL("INSERT INTO Movement VALUES ('old-move','old-item','Ancien article','IN',6000,1,'','',NULL)")
                db.execSQL("INSERT INTO Label VALUES ('location','Réserve')")
                db.execSQL("INSERT INTO Session VALUES ('old-session','Comptage',1,NULL)")
                db.execSQL("INSERT INTO Count VALUES ('old-session','old-item','Ancien article',6000,5000,'kg')")
                db.execSQL("INSERT INTO Photo VALUES ('old-item',X'010203')")
                db.version = 1
            }
            val migrated = Room.databaseBuilder(context, InventoryDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2).build()
            try {
                val dao = migrated.inventory()
                val item = requireNotNull(dao.byCode("ABC"))
                assertEquals("Ancien article", item.name)
                assertEquals(6000L, item.quantity)
                assertEquals(8000L, item.threshold)
                assertEquals("", item.inventory)
                assertNull(dao.movements().single().unit)
                assertEquals("Réserve", dao.allLabels().single().name)
                assertNull(dao.allSessions().single().inventory)
                assertEquals(5000L, dao.allCounts().single().actual)
                assertArrayEquals(byteArrayOf(1, 2, 3), dao.photo(item.id))
                assertTrue(dao.allCodes().isEmpty())
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
