package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ImageEntity::class, ImageSyncStateEntity::class, StorageUsageEntity::class],
    version = 2,
    exportSchema = false
)
abstract class ImageDatabase : RoomDatabase() {
    abstract fun imageDao(): ImageDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS storage_usage (
                        accountScope TEXT NOT NULL,
                        usedBytes INTEGER NOT NULL,
                        quotaBytes INTEGER NOT NULL,
                        availableBytes INTEGER NOT NULL,
                        usagePercentage INTEGER NOT NULL,
                        imageCount INTEGER NOT NULL,
                        warningLevel TEXT NOT NULL,
                        fetchedAt INTEGER NOT NULL,
                        lastSyncError TEXT,
                        PRIMARY KEY(accountScope)
                    )
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var instance: ImageDatabase? = null

        fun getInstance(context: Context): ImageDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ImageDatabase::class.java,
                    "pixel_vault_images.db"
                ).addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }
    }
}
