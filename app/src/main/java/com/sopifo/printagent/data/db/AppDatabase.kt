package com.sopifo.printagent.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DeviceConfigEntity::class,
        PrinterConfigEntity::class,
        PrintedJobEntity::class,
        RecentJobEntity::class,
    ],
    version = 2,
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
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "sopifo_agent.db")
                // Only local caches live here; the backend owns all jobs. Losing this data on an
                // unexpected schema change is safer than crash-looping an unattended device.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
