package com.example

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ImageDatabase
import com.example.data.local.ImageEntity
import com.example.data.local.QueuedUploadEntity
import com.example.data.local.UploadQueueStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImageDaoTest {

    private lateinit var database: ImageDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ImageDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `image records are isolated by account and full sync removes stale records`() = runBlocking {
        val dao = database.imageDao()
        dao.upsertImages(
            listOf(
                image("one@gmail.com", "first"),
                image("one@gmail.com", "stale"),
                image("two@gmail.com", "private")
            )
        )

        dao.replaceAccountImages("one@gmail.com", listOf(image("one@gmail.com", "first", "2026-01-02")))

        val firstAccount = dao.observeImages("one@gmail.com").first()
        val secondAccount = dao.observeImages("two@gmail.com").first()
        assertEquals(listOf("first"), firstAccount.map(ImageEntity::serverId))
        assertEquals(listOf("private"), secondAccount.map(ImageEntity::serverId))
        assertEquals("2026-01-02", firstAccount.single().createdAt)
    }

    @Test
    fun `reconciliation changes roll back together on transaction failure`() = runBlocking {
        val dao = database.imageDao()
        dao.upsertImages(listOf(image("one@gmail.com", "retained")))

        try {
            database.withTransaction {
                dao.deleteAllForAccount("one@gmail.com")
                dao.upsertImages(listOf(image("one@gmail.com", "temporary")))
                error("force transaction rollback")
            }
        } catch (_: IllegalStateException) {
            // The test intentionally aborts the transaction.
        }

        val rows = dao.observeImages("one@gmail.com").first()
        assertEquals(listOf("retained"), rows.map(ImageEntity::serverId))
    }

    @Test
    fun `sync state is account scoped`() = runBlocking {
        val dao = database.imageDao()
        dao.upsertSyncState(
            com.example.data.local.ImageSyncStateEntity(
                accountScope = "one@gmail.com",
                lastSuccessfulSyncAt = 123L,
                lastSyncError = null
            )
        )

        assertNotNull(dao.observeSyncState("one@gmail.com").first())
        assertNull(dao.observeSyncState("two@gmail.com").first())
    }

    @Test
    fun `upload queue is account scoped and pending counts exclude terminal records`() = runBlocking {
        val dao = database.imageDao()
        dao.insertQueuedUpload(queuedUpload("one@gmail.com", "queued", UploadQueueStatus.QUEUED))
        dao.insertQueuedUpload(
            queuedUpload("one@gmail.com", "completed", UploadQueueStatus.COMPLETED)
        )
        dao.insertQueuedUpload(
            queuedUpload("two@gmail.com", "private", UploadQueueStatus.RETRYABLE_FAILURE)
        )

        assertEquals(
            listOf("completed", "queued"),
            dao.observeQueuedUploads("one@gmail.com").first().map(QueuedUploadEntity::operationId)
        )
        assertEquals(1, dao.observePendingUploadCount("one@gmail.com").first())
        assertEquals(1, dao.observePendingUploadCount("two@gmail.com").first())
    }

    @Test
    fun `cancelled queue item cannot be started by a worker`() = runBlocking {
        val dao = database.imageDao()
        val upload = queuedUpload("one@gmail.com", "cancel-me", UploadQueueStatus.QUEUED)
        dao.insertQueuedUpload(upload)

        assertEquals(
            upload.localFilePath,
            dao.cancelQueuedUpload(upload.operationId, upload.accountScope, 10L)
        )
        assertEquals(
            0,
            dao.beginQueuedUpload(upload.operationId, upload.accountScope, 11L)
        )
        assertEquals(
            UploadQueueStatus.CANCELLED,
            dao.getQueuedUpload(upload.operationId)?.status
        )
    }

    @Test
    fun `confirmed upload metadata and queue completion persist together`() = runBlocking {
        val dao = database.imageDao()
        val upload = queuedUpload("one@gmail.com", "complete-me", UploadQueueStatus.UPLOADING)
        dao.insertQueuedUpload(upload)

        assertEquals(
            upload.localFilePath,
            dao.completeQueuedUpload(
                upload.operationId,
                upload.accountScope,
                image(upload.accountScope, "server-image"),
                20L
            )
        )
        assertEquals(
            UploadQueueStatus.COMPLETED,
            dao.getQueuedUpload(upload.operationId)?.status
        )
        assertEquals(
            listOf("server-image"),
            dao.observeImages(upload.accountScope).first().map(ImageEntity::serverId)
        )
        assertEquals(0, dao.observePendingUploadCount(upload.accountScope).first())
    }

    private fun image(account: String, id: String, createdAt: String? = null) =
        ImageEntity(
            accountScope = account,
            serverId = id,
            filename = "$id.jpg",
            mimeType = "image/jpeg",
            format = "jpeg",
            width = 32,
            height = 32,
            originalSize = 100L,
            storedSize = 80L,
            optimized = true,
            checksum = null,
            createdAt = createdAt,
            lastSyncedAt = 1L
        )

    private fun queuedUpload(account: String, operationId: String, status: String) =
        QueuedUploadEntity(
            operationId = operationId,
            accountScope = account,
            localFilePath = "/tmp/$operationId.upload",
            originalFilename = "$operationId.jpg",
            mimeType = "image/jpeg",
            fileSizeBytes = 128L,
            outputFormat = "webp",
            status = status,
            attempts = 0,
            createdAt = 1L,
            updatedAt = 1L
        )
}
