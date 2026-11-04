package com.timeagent.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/** Device-level checks for v14 → v15: the link table must not disturb legacy ledger rows. */
class LedgerSourceMigrationDeviceTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), TimeAgentDatabase::class.java,
    )

    @Test fun v14LedgerRowsSurviveAndSourceTableMatchesContract() {
        val name = "t45-ledger-source-schema.db"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(name)
        createV14Fixture(name).close()
        val db = helper.runMigrationsAndValidate(name, 15, true, LedgerSourceMigration)
        try {
            db.query("SELECT categoryId,captureId,amountMinor,direction,version FROM ledger_transactions WHERE id='tx-old'").use {
                assertTrue(it.moveToFirst())
                assertEquals("cat-1", it.getString(0))
                assertEquals("cap-1", it.getString(1))
                assertEquals(12_345L, it.getLong(2))
                assertEquals("EXPENSE", it.getString(3))
                assertEquals(3L, it.getLong(4))
            }
            db.query("SELECT amountMinor,status,transactionId FROM payment_captures WHERE id='cap-1'").use {
                assertTrue(it.moveToFirst())
                assertEquals(12_345L, it.getLong(0))
                assertEquals("CONFIRMED", it.getString(1))
                assertEquals("tx-old", it.getString(2))
            }
            db.query("SELECT count(*) FROM ledger_transaction_sources").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            val unique = mutableMapOf<String, Boolean>()
            db.query("PRAGMA index_list(`ledger_transaction_sources`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
                while (cursor.moveToNext()) unique[cursor.getString(nameIndex)] = cursor.getInt(uniqueIndex) == 1
            }
            assertEquals(
                mapOf(
                    "index_ledger_transaction_sources_transactionId" to false,
                    "index_ledger_transaction_sources_captureId" to false,
                    "index_ledger_transaction_sources_transactionId_captureId" to true,
                ),
                unique.filterKeys { it.startsWith("index_ledger_transaction_sources_") },
            )
            db.query("PRAGMA index_info(`index_ledger_transaction_sources_transactionId_captureId`)").use {
                val columns = buildList { while (it.moveToNext()) add(it.getString(2)) }
                assertEquals(listOf("transactionId", "captureId"), columns)
            }
            val foreignKeys = mutableMapOf<String, String>()
            db.query("PRAGMA foreign_key_list(`ledger_transaction_sources`)").use { cursor ->
                while (cursor.moveToNext()) {
                    val column = cursor.getString(cursor.getColumnIndexOrThrow("from"))
                    foreignKeys[column] = cursor.getString(cursor.getColumnIndexOrThrow("table")) + "." +
                        cursor.getString(cursor.getColumnIndexOrThrow("to")) + " " +
                        cursor.getString(cursor.getColumnIndexOrThrow("on_delete"))
                }
            }
            assertEquals(
                mapOf(
                    "transactionId" to "ledger_transactions.id CASCADE",
                    "captureId" to "payment_captures.id RESTRICT",
                ),
                foreignKeys,
            )
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun v12SampleMigratesTo15WithoutLosingLedgerOrScheduleRows() {
        val name = "t45-v12-chain.db"
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open("db/v12-sample.db").use { input ->
            path.outputStream().use { input.copyTo(it) }
        }
        val tables = listOf("ledger_accounts", "ledger_categories", "payment_captures", "ledger_transactions", "schedule_blocks")
        val counts = android.database.sqlite.SQLiteDatabase.openDatabase(
            path.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        ).use { old ->
            tables.associateWith { table ->
                old.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }
            }
        }
        val db = helper.runMigrationsAndValidate(
            name, 15, true, ScheduleMigration, TimelineIndexMigration, LedgerSourceMigration,
        )
        try {
            counts.forEach { (table, expected) ->
                db.query("SELECT COUNT(*) FROM $table").use {
                    assertTrue(it.moveToFirst()); assertEquals(expected, it.getLong(0))
                }
            }
            db.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun mergeAndSplitSourcesAreAcceptedAndConstraintsHold() {
        val name = "t45-ledger-source-constraints.db"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(name)
        createV14Fixture(name).close()
        val db = helper.runMigrationsAndValidate(name, 15, true, LedgerSourceMigration)
        try {
            db.execSQL("PRAGMA foreign_keys=ON")
            // Merge: one transaction backed by two captures.
            db.execSQL("INSERT INTO ledger_transaction_sources(id,transactionId,captureId) VALUES('src-a','tx-merged','cap-1')")
            db.execSQL("INSERT INTO ledger_transaction_sources(id,transactionId,captureId) VALUES('src-b','tx-merged','cap-2')")
            // Split: one capture funding two transactions.
            db.execSQL("INSERT INTO ledger_transaction_sources(id,transactionId,captureId) VALUES('src-c','tx-split-1','cap-3')")
            db.execSQL("INSERT INTO ledger_transaction_sources(id,transactionId,captureId) VALUES('src-d','tx-split-2','cap-3')")
            db.query("SELECT count(*) FROM ledger_transaction_sources WHERE transactionId='tx-merged'").use {
                assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
            }
            db.query("SELECT count(*) FROM ledger_transaction_sources WHERE captureId='cap-3'").use {
                assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
            }
            assertFails(db, "INSERT INTO ledger_transaction_sources(id,transactionId,captureId) VALUES('src-dup','tx-merged','cap-1')")
            assertFails(db, "INSERT INTO ledger_transaction_sources(id,transactionId,captureId) VALUES('src-no-tx','tx-missing','cap-1')")
            assertFails(db, "INSERT INTO ledger_transaction_sources(id,transactionId,captureId) VALUES('src-no-cap','tx-merged','cap-missing')")
            assertFails(db, "DELETE FROM payment_captures WHERE id='cap-3'")
            db.execSQL("DELETE FROM ledger_transactions WHERE id='tx-merged'")
            db.query("SELECT count(*) FROM ledger_transaction_sources WHERE transactionId='tx-merged'").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun assertFails(db: SupportSQLiteDatabase, sql: String) {
        try {
            db.execSQL(sql)
            fail("应被约束拒绝: $sql")
        } catch (expected: android.database.sqlite.SQLiteConstraintException) {
        }
    }

    private fun createV14Fixture(name: String): SupportSQLiteDatabase = helper.createDatabase(name, 14).apply {
        execSQL("INSERT INTO ledger_accounts(id,name,type,currency,openingBalanceMinor,sortOrder,archivedAt,createdAt,updatedAt,version) VALUES('acc-1','现金','CASH','CNY',0,0,NULL,1000,1000,1)")
        execSQL("INSERT INTO ledger_categories(id,name,direction,icon,sortOrder,archivedAt,createdAt,updatedAt,version) VALUES('cat-1','餐饮','EXPENSE','',0,NULL,1000,1000,1)")
        execSQL("INSERT INTO ledger_categories(id,name,direction,icon,sortOrder,archivedAt,createdAt,updatedAt,version) VALUES('cat-2','交通','EXPENSE','',1,NULL,1000,1000,1)")
        execSQL("INSERT INTO payment_captures(id,sourcePackage,sourceLabel,notificationKey,externalTransactionId,occurredAt,amountMinor,direction,merchant,rawTextHash,parsedJson,parserVersion,status,transactionId,createdAt,updatedAt,version) VALUES('cap-1','com.tencent.mm','微信','key-1',NULL,2000,12345,'EXPENSE','面馆','hash-1','',1,'CONFIRMED','tx-old',1000,1000,2)")
        execSQL("INSERT INTO payment_captures(id,sourcePackage,sourceLabel,notificationKey,externalTransactionId,occurredAt,amountMinor,direction,merchant,rawTextHash,parsedJson,parserVersion,status,transactionId,createdAt,updatedAt,version) VALUES('cap-2','com.tencent.mm','微信','key-2',NULL,2000,5000,'EXPENSE','便利店','hash-2','',1,'CONFIRMED','tx-merged',1000,1000,2)")
        execSQL("INSERT INTO payment_captures(id,sourcePackage,sourceLabel,notificationKey,externalTransactionId,occurredAt,amountMinor,direction,merchant,rawTextHash,parsedJson,parserVersion,status,transactionId,createdAt,updatedAt,version) VALUES('cap-3','com.eg.android.AlipayGphone','支付宝','key-3',NULL,3000,8000,'EXPENSE','超市','hash-3','',1,'PENDING',NULL,1000,1000,2)")
        execSQL("INSERT INTO ledger_transactions(id,accountId,categoryId,captureId,amountMinor,direction,merchant,note,occurredAt,createdAt,updatedAt,confirmedAt,version,deletedAt) VALUES('tx-old','acc-1','cat-1','cap-1',12345,'EXPENSE','面馆','旧账目',2000,1000,1000,1000,3,NULL)")
        execSQL("INSERT INTO ledger_transactions(id,accountId,categoryId,captureId,amountMinor,direction,merchant,note,occurredAt,createdAt,updatedAt,confirmedAt,version,deletedAt) VALUES('tx-merged','acc-1','cat-1',NULL,17345,'EXPENSE','面馆＋便利店','合并',2000,1000,1000,1000,0,NULL)")
        execSQL("INSERT INTO ledger_transactions(id,accountId,categoryId,captureId,amountMinor,direction,merchant,note,occurredAt,createdAt,updatedAt,confirmedAt,version,deletedAt) VALUES('tx-split-1','acc-1','cat-1',NULL,3000,'EXPENSE','超市','分摊一',3000,1000,1000,1000,0,NULL)")
        execSQL("INSERT INTO ledger_transactions(id,accountId,categoryId,captureId,amountMinor,direction,merchant,note,occurredAt,createdAt,updatedAt,confirmedAt,version,deletedAt) VALUES('tx-split-2','acc-1','cat-2',NULL,5000,'EXPENSE','超市','分摊二',3000,1000,1000,1000,0,NULL)")
    }
}
