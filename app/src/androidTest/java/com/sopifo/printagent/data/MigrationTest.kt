package com.sopifo.printagent.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sopifo.printagent.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun printersSurviveUpgradeWithPaperWidthUnset() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO printer_config (role, name, mac_address, protocol, width_dots, label_width_mm, label_height_mm, label_gap_mm, cut_paper, updated_at) " +
                    "VALUES ('RECEIPT', 'R', '00:11:22:33:44:55', 'ESC_POS', 576, 50, 30, 2, 1, 0)",
            )
        }
        helper.runMigrationsAndValidate(DB, 2, true).use { db ->
            db.query("SELECT name, paper_width_mm FROM printer_config WHERE role = 'RECEIPT'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("R", c.getString(0))
                assertTrue(c.isNull(1))
            }
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
