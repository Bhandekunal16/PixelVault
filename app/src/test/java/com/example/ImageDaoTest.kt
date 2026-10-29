package com.example

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ImageDatabase
import com.example.data.local.ImageEntity
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
}
