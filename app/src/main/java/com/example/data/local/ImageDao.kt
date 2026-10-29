package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ImageDao {
    @Query(
        "SELECT * FROM images WHERE accountScope = :accountScope " +
            "ORDER BY COALESCE(createdAt, '') DESC, serverId ASC"
    )
    abstract fun observeImages(accountScope: String): Flow<List<ImageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertImages(images: List<ImageEntity>)

    @Query("DELETE FROM images WHERE accountScope = :accountScope")
    abstract suspend fun deleteAllForAccount(accountScope: String)

    @Query("DELETE FROM images WHERE accountScope = :accountScope AND serverId IN (:serverIds)")
    abstract suspend fun deleteImages(accountScope: String, serverIds: List<String>)

    @Query("DELETE FROM images WHERE accountScope = :accountScope AND serverId NOT IN (:serverIds)")
    abstract suspend fun deleteMissingImages(accountScope: String, serverIds: List<String>)

    @Transaction
    open suspend fun replaceAccountImages(accountScope: String, images: List<ImageEntity>) {
        if (images.isEmpty()) {
            deleteAllForAccount(accountScope)
        } else {
            deleteMissingImages(accountScope, images.map(ImageEntity::serverId))
            upsertImages(images)
        }
    }

    @Query("SELECT * FROM image_sync_state WHERE accountScope = :accountScope")
    abstract fun observeSyncState(accountScope: String): Flow<ImageSyncStateEntity?>

    @Query("SELECT * FROM image_sync_state WHERE accountScope = :accountScope")
    abstract suspend fun getSyncState(accountScope: String): ImageSyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSyncState(syncState: ImageSyncStateEntity)
}
