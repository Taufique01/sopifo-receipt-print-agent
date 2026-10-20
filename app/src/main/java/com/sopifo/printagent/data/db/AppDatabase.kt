package com.sopifo.printagent.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DeviceConfigEntity::class,
        PrinterConfigEntity::class,
        PrintedJobEntity::class,
        RecentJobEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), // printer_config.paper_width_mm
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceConfigDao(): DeviceConfigDao
    abstract fun printerConfigDao(): PrinterConfigDao
    abstract fun printedJobDao(): PrintedJobDao
    abstract fun recentJobDao(): RecentJobDao

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
