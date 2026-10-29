package com.example

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.api.NetworkClient
import com.example.data.local.ImageDatabase
import com.example.data.local.ImageEntity
import com.example.data.local.PendingUploadFileStore
import com.example.data.local.QueuedUploadEntity
import com.example.data.local.StorageUsageEntity
import com.example.data.local.UploadQueueStatus
import com.example.data.preferences.AppPreferences
import com.example.data.preferences.TokenCipher
import com.example.data.repository.ImageRepositoryImpl
import com.example.data.repository.QueuedUploadWorkResult
import com.example.data.repository.UploadWorkScheduler
import com.example.data.model.OutputFormat
import com.example.util.Resource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImageRepositorySyncTest {

    private lateinit var context: Context
    private lateinit var database: ImageDatabase
    private lateinit var preferences: AppPreferences
    private val servers = mutableListOf<SingleResponseServer>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, ImageDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferences = AppPreferences(context, TestTokenCipher())
        runBlocking {
            preferences.saveUserSession(
                email = "person@gmail.com",
                id = "person-id",
                role = "user",
                accessToken = "test-access-token"
            )
        }
    }

    @After
    fun tearDown() {
        servers.forEach(Closeable::close)
        database.close()
    }

    @Test
    fun `sync persists backend metadata and reuses recently synced local data`() = runBlocking {
        val server = server(
            200,
            """{"message":"Images found!","statusCode":200,"status":true,"data":[{"_id":"server-id","name":"photo.webp","mimeType":"image/webp","format":"webp","width":120,"height":80,"originalSize":400,"storedSize":200,"optimized":true,"checksum":"abc","createdAt":"2026-10-29T10:00:00.000Z"}]}"""
        )
        val repository = repository(server)

        assertTrue(repository.synchronizeImages(" Person@Gmail.com ", forceRefresh = true) is Resource.Success)
        server.awaitRequest()

        val image = database.imageDao().observeImages("person@gmail.com").first().single()
        assertEquals("server-id", image.serverId)
        assertEquals("photo.webp", image.filename)
        assertEquals("image/webp", image.mimeType)
        assertEquals(200L, image.storedSize)
        assertEquals(listOf("Bearer test-access-token"), server.authorizationHeaders)

        assertTrue(repository.synchronizeImages("person@gmail.com") is Resource.Success)
        assertEquals(1, server.requestCount.get())
    }

    @Test
    fun `overlapping automatic sync requests share one successful network reconciliation`() = runBlocking {
        val firstRequestArrived = CountDownLatch(1)
        val releaseFirstResponse = CountDownLatch(1)
        val server = server(
            200,
            """{"message":"Images found!","statusCode":200,"status":true,"data":[]}""",
            responseCount = 1,
            firstRequestArrived = firstRequestArrived,
            releaseFirstResponse = releaseFirstResponse
        )
        val repository = repository(server)

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            repository.synchronizeImages("person@gmail.com", forceRefresh = true)
        }
        assertTrue(withContext(Dispatchers.IO) { firstRequestArrived.await(5, TimeUnit.SECONDS) })
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            repository.synchronizeImages("person@gmail.com", forceRefresh = false)
        }
        releaseFirstResponse.countDown()

        assertTrue(first.await() is Resource.Success)
        assertTrue(second.await() is Resource.Success)
        server.awaitRequest()
        assertEquals(1, server.requestCount.get())
    }

    @Test
    fun `overlapping explicit refreshes each contact the server in serialized order`() = runBlocking {
        val firstRequestArrived = CountDownLatch(1)
        val releaseFirstResponse = CountDownLatch(1)
        val server = server(
            200,
            """{"message":"Images found!","statusCode":200,"status":true,"data":[]}""",
            responseCount = 2,
            firstRequestArrived = firstRequestArrived,
            releaseFirstResponse = releaseFirstResponse,
            responses = listOf(
                200 to """{"message":"Images found!","statusCode":200,"status":true,"data":[{"_id":"older-response","name":"old.png"}]}""",
                200 to """{"message":"Images found!","statusCode":200,"status":true,"data":[{"_id":"newer-response","name":"new.png"}]}"""
            )
        )
        val repository = repository(server)

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            repository.synchronizeImages("person@gmail.com", forceRefresh = true)
        }
        assertTrue(withContext(Dispatchers.IO) { firstRequestArrived.await(5, TimeUnit.SECONDS) })
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            repository.synchronizeImages("person@gmail.com", forceRefresh = true)
        }
        releaseFirstResponse.countDown()

        assertTrue(first.await() is Resource.Success)
        assertTrue(second.await() is Resource.Success)
        server.awaitRequest(expectedCount = 2)
        assertEquals(2, server.requestCount.get())
        assertEquals(
            listOf("newer-response"),
            database.imageDao().observeImages("person@gmail.com").first().map(ImageEntity::serverId)
        )
    }

    @Test
    fun `recognized empty-list 404 clears only that account after successful sync`() = runBlocking {
        database.imageDao().upsertImages(
            listOf(
                image("person@gmail.com", "old"),
                image("other@gmail.com", "retained")
            )
        )
        val server = server(
            404,
            """{"message":"Image not found","statusCode":404,"status":false,"data":[]}"""
        )

        val result = repository(server).synchronizeImages("person@gmail.com", forceRefresh = true)
        server.awaitRequest()

        assertTrue(result is Resource.Success)
        assertTrue(database.imageDao().observeImages("person@gmail.com").first().isEmpty())
        assertEquals(
            listOf("retained"),
            database.imageDao().observeImages("other@gmail.com").first().map(ImageEntity::serverId)
        )
    }

    @Test
    fun `server and malformed responses preserve cached images`() = runBlocking {
        database.imageDao().upsertImages(listOf(image("person@gmail.com", "cached")))
        database.imageDao().upsertSyncState(
            com.example.data.local.ImageSyncStateEntity(
                accountScope = "person@gmail.com",
                lastSuccessfulSyncAt = 1234L,
                lastSyncError = null
            )
        )

        val serverError = server(500, """{"message":"Database unavailable","statusCode":500,"status":false,"data":[]}""")
        val errorResult = repository(serverError)
            .synchronizeImages("person@gmail.com", forceRefresh = true)
        serverError.awaitRequest()
        assertTrue(errorResult is Resource.Error)
        assertEquals(
            listOf("cached"),
            database.imageDao().observeImages("person@gmail.com").first().map(ImageEntity::serverId)
        )
        assertEquals(
            1234L,
            database.imageDao().getSyncState("person@gmail.com")?.lastSuccessfulSyncAt
        )

        val malformedServer = server(200, """{"message":"Images found","statusCode":200,"status":true,"data":{}}""")
        val malformedResult = repository(malformedServer)
            .synchronizeImages("person@gmail.com", forceRefresh = true)
        malformedServer.awaitRequest()
        assertTrue(malformedResult is Resource.Error)
        assertEquals(
            listOf("cached"),
            database.imageDao().observeImages("person@gmail.com").first().map(ImageEntity::serverId)
        )
    }

    @Test
    fun `syncs after the two-minute freshness window`() = runBlocking {
        database.imageDao().upsertSyncState(
            com.example.data.local.ImageSyncStateEntity(
                accountScope = "person@gmail.com",
                lastSuccessfulSyncAt = System.currentTimeMillis() - 2 * 60 * 1000 - 1,
                lastSyncError = null
            )
        )
        val server = server(
            200,
            """{"message":"Images found!","statusCode":200,"status":true,"data":[]}"""
        )

        assertTrue(repository(server).synchronizeImages("person@gmail.com") is Resource.Success)
        server.awaitRequest()
        assertEquals(1, server.requestCount.get())
    }

    @Test
    fun `confirmed deletion removes only the selected account cache`() = runBlocking {
        database.imageDao().upsertImages(
            listOf(
                image("person@gmail.com", "deleted"),
                image("other@gmail.com", "retained")
            )
        )
        val server = server(
            200,
            """{"message":"Images deleted successfully","statusCode":200,"status":true,"data":1}""",
            expectedMethods = listOf("DELETE")
        )

        val result = repository(server)
            .deleteImages(listOf("deleted"), "person@gmail.com")
            .last()
        server.awaitRequest()

        assertTrue(result is Resource.Success)
        assertTrue(database.imageDao().observeImages("person@gmail.com").first().isEmpty())
        assertEquals(
            listOf("retained"),
            database.imageDao().observeImages("other@gmail.com").first().map(ImageEntity::serverId)
        )
    }

    @Test
    fun `confirmed deletion is not restored by a subsequent full sync`() = runBlocking {
        database.imageDao().upsertImages(
            listOf(
                image("person@gmail.com", "deleted"),
                image("other@gmail.com", "other-account")
            )
        )
        val deleteServer = server(
            200,
            """{"message":"Images deleted successfully","statusCode":200,"status":true,"data":1}""",
            expectedMethods = listOf("DELETE")
        )

        assertTrue(
            repository(deleteServer)
                .deleteImages(listOf("deleted"), "person@gmail.com")
                .last() is Resource.Success
        )
        deleteServer.awaitRequest()

        val syncServer = server(
            200,
            """{"message":"Images found!","statusCode":200,"status":true,"data":[]}"""
        )
        val syncResult = repository(syncServer)
            .synchronizeImages("person@gmail.com", forceRefresh = true)
        syncServer.awaitRequest()

        assertTrue(syncResult is Resource.Success)
        assertTrue(database.imageDao().observeImages("person@gmail.com").first().isEmpty())
        assertEquals(
            listOf("other-account"),
            database.imageDao().observeImages("other@gmail.com").first().map(ImageEntity::serverId)
        )
    }

    @Test
    fun `exact already-deleted response removes stale local image`() = runBlocking {
        database.imageDao().upsertImages(listOf(image("person@gmail.com", "already-deleted")))
        val server = server(
            404,
            """{"message":"Image not found","statusCode":404,"status":false,"data":{"deletedCount":0}}""",
            expectedMethods = listOf("DELETE")
        )

        val result = repository(server)
            .deleteImages(listOf("already-deleted"), "person@gmail.com")
            .last()
        server.awaitRequest()

        assertTrue(result is Resource.Success)
        assertTrue(database.imageDao().observeImages("person@gmail.com").first().isEmpty())
    }

    @Test
    fun `failed deletion preserves local image`() = runBlocking {
        database.imageDao().upsertImages(listOf(image("person@gmail.com", "still-present")))
        val server = server(
            500,
            """{"message":"Database unavailable","statusCode":500,"status":false,"data":{}}""",
            expectedMethods = listOf("DELETE")
        )

        val result = repository(server)
            .deleteImages(listOf("still-present"), "person@gmail.com")
            .last()
        server.awaitRequest()

        assertTrue(result is Resource.Error)
        assertEquals(
            listOf("still-present"),
            database.imageDao().observeImages("person@gmail.com").first().map(ImageEntity::serverId)
        )
    }

    @Test
    fun `partial deletion is verified against server before reconciling local records`() = runBlocking {
        database.imageDao().upsertImages(
            listOf(
                image("person@gmail.com", "deleted"),
                image("person@gmail.com", "still-present"),
                image("other@gmail.com", "other-account")
            )
        )
        val server = server(
            200,
            """{"message":"Images deleted successfully","statusCode":200,"status":true,"data":1}""",
            responseCount = 2,
            responses = listOf(
                200 to """{"message":"Images deleted successfully","statusCode":200,"status":true,"data":1}""",
                200 to """{"message":"Images found!","statusCode":200,"status":true,"data":[{"_id":"still-present","name":"still-present.jpg","mimeType":"image/jpeg"}]}"""
            ),
            expectedMethods = listOf("DELETE", "GET")
        )

        val result = repository(server)
            .deleteImages(listOf("deleted", "still-present"), "person@gmail.com")
            .last()
        server.awaitRequest(expectedCount = 2)

        assertTrue(result is Resource.Error)
        assertEquals(listOf("DELETE", "GET"), server.requestMethods)
        assertEquals(
            listOf("still-present"),
            database.imageDao().observeImages("person@gmail.com").first().map(ImageEntity::serverId)
        )
        assertEquals(
            listOf("other-account"),
            database.imageDao().observeImages("other@gmail.com").first().map(ImageEntity::serverId)
        )
    }

    @Test
    fun `unrelated verification error does not clear local deletion candidates`() = runBlocking {
        database.imageDao().upsertImages(
            listOf(
                image("person@gmail.com", "candidate"),
                image("person@gmail.com", "other-candidate")
            )
        )
        val server = server(
            200,
            """{"message":"Images deleted successfully","statusCode":200,"status":true,"data":1}""",
            responseCount = 2,
            responses = listOf(
                200 to """{"message":"Images deleted successfully","statusCode":200,"status":true,"data":1}""",
                503 to """{"message":"Service unavailable","statusCode":503,"status":false,"data":[]}"""
            ),
            expectedMethods = listOf("DELETE", "GET")
        )

        val result = repository(server)
            .deleteImages(listOf("candidate", "other-candidate"), "person@gmail.com")
            .last()
        server.awaitRequest(expectedCount = 2)

        assertTrue(result is Resource.Error)
        assertEquals(
            listOf("candidate", "other-candidate"),
            database.imageDao().observeImages("person@gmail.com").first().map(ImageEntity::serverId).sorted()
        )
    }

    @Test
    fun `request from a previous account cannot synchronize after account switch`() = runBlocking {
        database.imageDao().upsertImages(
            listOf(
                image("person@gmail.com", "person-image"),
                image("other@gmail.com", "other-image")
            )
        )
        val server = server(
            200,
            """{"message":"Images found!","statusCode":200,"status":true,"data":[]}"""
        )
        preferences.saveUserSession(
            email = "other@gmail.com",
            id = "other-id",
            role = "user",
            accessToken = "other-access-token"
        )

        val result = repository(server).synchronizeImages("person@gmail.com", forceRefresh = true)

        assertTrue(result is Resource.Error)
        assertEquals(0, server.requestCount.get())
        assertEquals(
            listOf("person-image"),
            database.imageDao().observeImages("person@gmail.com").first().map(ImageEntity::serverId)
        )
        assertEquals(
            listOf("other-image"),
            database.imageDao().observeImages("other@gmail.com").first().map(ImageEntity::serverId)
        )
    }

    @Test
    fun `delayed response for previous account cannot overwrite active account data`() = runBlocking {
        database.imageDao().upsertImages(
            listOf(
                image("person@gmail.com", "person-cached"),
                image("other@gmail.com", "other-cached")
            )
        )
        val firstRequestArrived = CountDownLatch(1)
        val releaseFirstResponse = CountDownLatch(1)
        val server = server(
            200,
            """{"message":"Images found!","statusCode":200,"status":true,"data":[{"_id":"person-remote","name":"person.jpg","mimeType":"image/jpeg"}]}""",
            firstRequestArrived = firstRequestArrived,
            releaseFirstResponse = releaseFirstResponse
        )
        val repository = repository(server)

        val request = async(start = CoroutineStart.UNDISPATCHED) {
            repository.synchronizeImages("person@gmail.com", forceRefresh = true)
        }
        assertTrue(withContext(Dispatchers.IO) { firstRequestArrived.await(5, TimeUnit.SECONDS) })
        preferences.saveUserSession(
            email = "other@gmail.com",
            id = "other-id",
            role = "user",
            accessToken = "other-access-token"
        )
        releaseFirstResponse.countDown()

        assertTrue(request.await() is Resource.Success)
        server.awaitRequest()
        assertEquals(
            listOf("person-remote"),
            database.imageDao().observeImages("person@gmail.com").first().map(ImageEntity::serverId)
        )
        assertEquals(
            listOf("other-cached"),
            database.imageDao().observeImages("other@gmail.com").first().map(ImageEntity::serverId)
        )
    }

    @Test
    fun `storage usage refresh persists validated account scoped summary`() = runBlocking {
        val server = server(
            200,
            """{"message":"Storage usage retrieved successfully","statusCode":200,"status":true,"data":{"usedBytes":80,"quotaBytes":100,"availableBytes":20,"usagePercentage":80,"imageCount":3,"warningLevel":"info"}}""",
            expectedMethods = listOf("GET")
        )
        val repository = repository(server)

        val result = repository.refreshStorageUsage("person@gmail.com", forceRefresh = true)
        server.awaitRequest()

        assertTrue(result is Resource.Success)
        val savedUsage = database.imageDao().getStorageUsage("person@gmail.com")!!
        assertEquals(80L, savedUsage.usedBytes)
        assertEquals(100L, savedUsage.quotaBytes)
        assertEquals(20L, savedUsage.availableBytes)
        assertEquals(80, savedUsage.usagePercentage)
        assertEquals(3, savedUsage.imageCount)
        assertEquals("info", savedUsage.warningLevel)
        assertTrue(savedUsage.fetchedAt > 0L)
        assertEquals(1, server.requestCount.get())
    }

    @Test
    fun `failed storage usage refresh retains last confirmed account summary`() = runBlocking {
        database.imageDao().upsertStorageUsage(
            StorageUsageEntity(
                accountScope = "person@gmail.com",
                usedBytes = 60,
                quotaBytes = 100,
                availableBytes = 40,
                usagePercentage = 60,
                imageCount = 2,
                warningLevel = "normal",
                fetchedAt = 1234L
            )
        )
        val server = server(
            503,
            """{"message":"Service unavailable","statusCode":503,"status":false,"data":{"errorCode":"STORAGE_USAGE_UNAVAILABLE"}}""",
            expectedMethods = listOf("GET")
        )

        val result = repository(server).refreshStorageUsage("person@gmail.com", forceRefresh = true)
        server.awaitRequest()

        assertTrue(result is Resource.Error)
        val retained = database.imageDao().getStorageUsage("person@gmail.com")!!
        assertEquals(60L, retained.usedBytes)
        assertEquals(1234L, retained.fetchedAt)
        assertTrue(!retained.lastSyncError.isNullOrBlank())
        assertTrue(database.imageDao().observeStorageUsage("other@gmail.com").first() == null)
    }

    @Test
    fun `queue stages image bytes locally and schedules account scoped operation`() = runBlocking {
        val source = sourceImage()
        val uploadContext = isolatedUploadContext()
        val scheduler = RecordingUploadScheduler()
        val repository = repository(
            server(200, "{}", responseCount = 0),
            scheduler,
            uploadContext
        )

        val result = repository.queueUpload(
            uploadContext,
            Uri.fromFile(source),
            " Person@Gmail.com ",
            OutputFormat.WEBP
        )

        assertTrue("queue failed: ${(result as? Resource.Error)?.message}", result is Resource.Success)
        val queued = (result as Resource.Success).data
        assertEquals("person@gmail.com", queued.accountScope)
        assertEquals(UploadQueueStatus.QUEUED, queued.status)
        assertTrue(File(queued.localFilePath!!).exists())
        assertEquals(listOf(queued.operationId), scheduler.enqueued)
        assertEquals(queued, database.imageDao().observeQueuedUploads("person@gmail.com").first().single())
        assertTrue(database.imageDao().observeQueuedUploads("other@gmail.com").first().isEmpty())
        source.delete()
        PendingUploadFileStore(uploadContext).delete(queued.localFilePath)
        removeUploadContext(uploadContext)
    }

    @Test
    fun `queue does not upload when backend lacks idempotency support`() = runBlocking {
        val source = sourceImage()
        val uploadContext = isolatedUploadContext()
        val scheduler = RecordingUploadScheduler()
        val server = server(
            200,
            """{"message":"Storage usage retrieved successfully","statusCode":200,"status":true,"data":{"usedBytes":0,"quotaBytes":1000,"availableBytes":1000,"usagePercentage":0,"imageCount":0,"warningLevel":"normal"}}""",
            expectedMethods = listOf("GET")
        )
        val repository = repository(server, scheduler, uploadContext)
        val queued = repository.queueUpload(
            uploadContext,
            Uri.fromFile(source),
            "person@gmail.com",
            OutputFormat.WEBP
        )
        source.delete()
        assertTrue("queue failed: ${(queued as? Resource.Error)?.message}", queued is Resource.Success)
        val operationId = (queued as Resource.Success).data.operationId

        val result = repository.processQueuedUpload(operationId)
        server.awaitRequest()

        assertEquals(QueuedUploadWorkResult.FINISHED, result)
        assertEquals(listOf("GET /storage/usage HTTP/1.1"), server.requestLines)
        val persisted = database.imageDao().getQueuedUpload(operationId)!!
        assertEquals(UploadQueueStatus.PERMANENT_FAILURE, persisted.status)
        assertEquals("SERVER_IDEMPOTENCY_REQUIRED", persisted.lastErrorCode)
        PendingUploadFileStore(uploadContext).delete(persisted.localFilePath)
        removeUploadContext(uploadContext)
    }

    @Test
    fun `transient upload errors retry with the same durable idempotency key`() = runBlocking {
        val source = sourceImage()
        val uploadContext = isolatedUploadContext()
        val scheduler = RecordingUploadScheduler()
        val server = server(
            200,
            "",
            responseCount = 2,
            responses = listOf(
                200 to """{"message":"Storage usage retrieved successfully","statusCode":200,"status":true,"data":{"usedBytes":0,"quotaBytes":1000,"availableBytes":1000,"usagePercentage":0,"imageCount":0,"warningLevel":"normal","idempotencySupported":true}}""",
                503 to """{"message":"Service unavailable","statusCode":503,"status":false,"data":{"errorCode":"TEMPORARY_FAILURE"}}"""
            ),
            expectedMethods = listOf("GET", "POST")
        )
        val repository = repository(server, scheduler, uploadContext)
        val queued = repository.queueUpload(
            uploadContext,
            Uri.fromFile(source),
            "person@gmail.com",
            OutputFormat.WEBP
        )
        source.delete()
        assertTrue("queue failed: ${(queued as? Resource.Error)?.message}", queued is Resource.Success)
        val operationId = (queued as Resource.Success).data.operationId

        val result = repository.processQueuedUpload(operationId)
        server.awaitRequest(expectedCount = 2)

        assertEquals(QueuedUploadWorkResult.RETRY, result)
        val persisted = database.imageDao().getQueuedUpload(operationId)!!
        assertEquals(UploadQueueStatus.RETRYABLE_FAILURE, persisted.status)
        assertEquals(listOf(operationId), server.idempotencyKeys.filter(String::isNotBlank))
        PendingUploadFileStore(uploadContext).delete(persisted.localFilePath)
        removeUploadContext(uploadContext)
    }

    @Test
    fun `quota rejection is retained for user action instead of automatic retry`() = runBlocking {
        val source = sourceImage()
        val uploadContext = isolatedUploadContext()
        val scheduler = RecordingUploadScheduler()
        val server = server(
            200,
            "",
            responseCount = 3,
            responses = listOf(
                200 to """{"message":"Storage usage retrieved successfully","statusCode":200,"status":true,"data":{"usedBytes":0,"quotaBytes":1000,"availableBytes":1000,"usagePercentage":0,"imageCount":0,"warningLevel":"normal","idempotencySupported":true}}""",
                409 to """{"message":"Account cloud storage quota exceeded","statusCode":409,"status":false,"data":{"errorCode":"STORAGE_QUOTA_EXCEEDED"}}""",
                200 to """{"message":"Storage usage retrieved successfully","statusCode":200,"status":true,"data":{"usedBytes":1000,"quotaBytes":1000,"availableBytes":0,"usagePercentage":100,"imageCount":1,"warningLevel":"full","idempotencySupported":true}}"""
            ),
            expectedMethods = listOf("GET", "POST", "GET")
        )
        val repository = repository(server, scheduler, uploadContext)
        val queued = repository.queueUpload(
            uploadContext,
            Uri.fromFile(source),
            "person@gmail.com",
            OutputFormat.WEBP
        )
        source.delete()
        assertTrue("queue failed: ${(queued as? Resource.Error)?.message}", queued is Resource.Success)
        val operationId = (queued as Resource.Success).data.operationId

        assertEquals(
            QueuedUploadWorkResult.FINISHED,
            repository.processQueuedUpload(operationId)
        )
        server.awaitRequest(expectedCount = 3)

        val persisted = database.imageDao().getQueuedUpload(operationId)!!
        assertEquals(UploadQueueStatus.PERMANENT_FAILURE, persisted.status)
        assertEquals("STORAGE_QUOTA_EXCEEDED", persisted.lastErrorCode)
        assertEquals(3, server.requestCount.get())
        PendingUploadFileStore(uploadContext).delete(persisted.localFilePath)
        removeUploadContext(uploadContext)
    }

    private suspend fun repository(
        server: SingleResponseServer,
        scheduler: UploadWorkScheduler? = null,
        uploadFileContext: Context = context
    ): ImageRepositoryImpl {
        preferences.setBaseUrl(server.baseUrl)
        return ImageRepositoryImpl(
            preferences = preferences,
            networkClient = NetworkClient(),
            imageDao = database.imageDao(),
            imageDatabase = database,
            uploadScheduler = scheduler,
            applicationContext = uploadFileContext
        )
    }

    private fun isolatedUploadContext(): Context =
        object : ContextWrapper(context) {
            private val isolatedFilesDir = File(
                context.cacheDir,
                "upload-files-${System.nanoTime()}"
            ).apply {
                check(mkdirs())
            }

            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = isolatedFilesDir
        }

    private fun removeUploadContext(uploadContext: Context) {
        File(uploadContext.filesDir, "pending_uploads").delete()
        uploadContext.filesDir.delete()
    }

    private fun sourceImage(): File =
        File(context.cacheDir, "queued-${System.nanoTime()}.jpg").apply {
            writeBytes(ByteArray(128) { it.toByte() })
        }

    private fun server(
        status: Int,
        body: String,
        responseCount: Int = 1,
        firstRequestArrived: CountDownLatch? = null,
        releaseFirstResponse: CountDownLatch? = null,
        responses: List<Pair<Int, String>>? = null,
        expectedMethods: List<String>? = null
    ): SingleResponseServer =
        SingleResponseServer(
            status,
            body,
            responseCount,
            firstRequestArrived,
            releaseFirstResponse,
            responses,
            expectedMethods
        ).also(servers::add)

    private fun image(account: String, id: String) = ImageEntity(
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
        createdAt = null,
        lastSyncedAt = 1L
    )

    private class SingleResponseServer(
        statusCode: Int,
        body: String,
        responseCount: Int = 1,
        private val firstRequestArrived: CountDownLatch? = null,
        private val releaseFirstResponse: CountDownLatch? = null,
        responses: List<Pair<Int, String>>? = null,
        private val expectedMethods: List<String>? = null
    ) : Closeable {
        private val socket = ServerSocket(0)
        val requestCount = AtomicInteger()
        val authorizationHeaders = java.util.concurrent.CopyOnWriteArrayList<String>()
        val requestMethods = java.util.concurrent.CopyOnWriteArrayList<String>()
        val requestLines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val idempotencyKeys = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val allRequestsReceived = CountDownLatch(responseCount)
        private val worker = Thread {
            repeat(responseCount) { requestIndex ->
                socket.accept().use { connection ->
                    val reader = connection.getInputStream().bufferedReader()
                    val requestLine = reader.readLine() ?: error("Missing request line")
                    requestLines.add(requestLine)
                    requestMethods.add(requestLine.substringBefore(' '))
                    expectedMethods?.get(requestIndex)?.let { expectedMethod ->
                        check(requestLine.startsWith("$expectedMethod ")) {
                            "Expected $expectedMethod request but received: $requestLine"
                        }
                    }
                    var contentLength = 0
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                        if (header.startsWith("Authorization:", ignoreCase = true)) {
                            authorizationHeaders.add(header.substringAfter(':').trim())
                        }
                        if (header.startsWith("Idempotency-Key:", ignoreCase = true)) {
                            idempotencyKeys.add(header.substringAfter(':').trim())
                        }
                        if (header.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = header.substringAfter(':').trim().toInt()
                        }
                    }
                    repeat(contentLength) { reader.read() }
                    requestCount.incrementAndGet()
                    allRequestsReceived.countDown()

                    if (requestIndex == 0) {
                        firstRequestArrived?.countDown()
                        releaseFirstResponse?.await()
                    }

                    val (responseStatus, responseBody) = responses?.get(requestIndex)
                        ?: (statusCode to body)
                    val bytes = responseBody.toByteArray(Charsets.UTF_8)
                    val reason = when (responseStatus) {
                        200 -> "OK"
                        404 -> "Not Found"
                        else -> "Internal Server Error"
                    }
                    val headers = "HTTP/1.1 $responseStatus $reason\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    connection.getOutputStream().apply {
                        write(headers.toByteArray(Charsets.UTF_8))
                        write(bytes)
                        flush()
                    }
                }
            }
        }.apply {
            name = "image-repository-test-server"
            isDaemon = true
            start()
        }

        val baseUrl = "http://127.0.0.1:${socket.localPort}/"

        fun awaitRequest(expectedCount: Int = 1) {
            check(allRequestsReceived.await(5, TimeUnit.SECONDS)) {
                "Repository did not complete $expectedCount HTTP request(s)."
            }
        }

        override fun close() {
            socket.close()
            worker.join(1_000)
        }
    }

    private class TestTokenCipher : TokenCipher {
        override fun encrypt(token: String): String =
            Base64.getEncoder().encodeToString(token.toByteArray(Charsets.UTF_8))

        override fun decrypt(encoded: String): String =
            String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
    }

    private class RecordingUploadScheduler : UploadWorkScheduler {
        val enqueued = mutableListOf<String>()

        override suspend fun enqueue(operationId: String) {
            enqueued.add(operationId)
        }

        override suspend fun cancel(operationId: String) = Unit
    }
}
