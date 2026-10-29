package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ImageEntity::class,
        ImageSyncStateEntity::class,
        StorageUsageEntity::class,
        QueuedUploadEntity::class
    ],
    version = 3,
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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS queued_uploads (
                        operationId TEXT NOT NULL,
                        accountScope TEXT NOT NULL,
                        localFilePath TEXT,
                        originalFilename TEXT NOT NULL,
                        mimeType TEXT NOT NULL,
                        fileSizeBytes INTEGER NOT NULL,
                        outputFormat TEXT NOT NULL,
                        status TEXT NOT NULL,
                        attempts INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        lastErrorCode TEXT,
                        lastErrorMessage TEXT,
                        serverImageId TEXT,
                        PRIMARY KEY(operationId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_queued_uploads_accountScope_status " +
                        "ON queued_uploads(accountScope, status)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_queued_uploads_accountScope_createdAt " +
                        "ON queued_uploads(accountScope, createdAt)"
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
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also { instance = it }
            }
    }
}
