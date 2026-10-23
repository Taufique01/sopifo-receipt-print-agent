package com.sopifo.printagent.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.DeleteColumn
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DeviceConfigEntity::class,
        PrinterConfigEntity::class,
        PrintedJobEntity::class,
        RecentJobEntity::class,
        PrintRunEntity::class,
        PrintRunItemEntity::class,
    ],
    version = 6,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), // printer_config.paper_width_mm
        AutoMigration(from = 4, to = 5, spec = AppDatabase.RemoveStickers::class),
        AutoMigration(from = 5, to = 6), // print_runs + print_run_items (the app's own prints)
    ],
)
abstract class AppDatabase : RoomDatabase() {
    /**
     * Sticker mode (1.2.2–1.2.3) is retired: its settings go, and every printer is back to the
     * fastest send speed.
     */
    @DeleteColumn(tableName = "printer_config", columnName = "paper_type")
    @DeleteColumn(tableName = "printer_config", columnName = "sticker_height_mm")
    @DeleteColumn(tableName = "printer_config", columnName = "sticker_gap_mm")
    @DeleteColumn(tableName = "printer_config", columnName = "sticker_offset_tenths")
    @DeleteColumn(tableName = "printer_config", columnName = "sticker_pitch_adjust")
    class RemoveStickers : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE printer_config SET send_speed_kbps = 64")
        }
    }

    abstract fun deviceConfigDao(): DeviceConfigDao
    abstract fun printerConfigDao(): PrinterConfigDao
    abstract fun printedJobDao(): PrintedJobDao
    abstract fun recentJobDao(): RecentJobDao
    abstract fun printRunDao(): PrintRunDao

    companion object {
        /**
         * printer_config.send_speed_kbps. Label printers already set up move to the safe speed:
         * small portable ones dropped every label over ~10 KB at the old fast pacing.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE printer_config ADD COLUMN send_speed_kbps INTEGER NOT NULL DEFAULT 64")
                db.execSQL("UPDATE printer_config SET send_speed_kbps = 8 WHERE protocol = 'TSPL'")
            }
        }

        /** Label stickers on a receipt printer: paper type and sticker setup. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE printer_config ADD COLUMN paper_type TEXT NOT NULL DEFAULT 'ROLL'")
                db.execSQL("ALTER TABLE printer_config ADD COLUMN sticker_height_mm INTEGER NOT NULL DEFAULT 75")
                db.execSQL("ALTER TABLE printer_config ADD COLUMN sticker_gap_mm INTEGER NOT NULL DEFAULT 3")
                db.execSQL("ALTER TABLE printer_config ADD COLUMN sticker_offset_tenths INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE printer_config ADD COLUMN sticker_pitch_adjust INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "sopifo_agent.db")
                // Only local caches live here; the backend owns all jobs. Losing this data on an
                // unexpected schema change is safer than crash-looping an unattended device.
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
