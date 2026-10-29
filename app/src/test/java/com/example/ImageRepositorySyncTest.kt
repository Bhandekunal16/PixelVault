package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.api.NetworkClient
import com.example.data.local.ImageDatabase
import com.example.data.local.ImageEntity
import com.example.data.preferences.AppPreferences
import com.example.data.preferences.TokenCipher
import com.example.data.repository.ImageRepositoryImpl
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

    private suspend fun repository(server: SingleResponseServer): ImageRepositoryImpl {
        preferences.setBaseUrl(server.baseUrl)
        return ImageRepositoryImpl(
            preferences = preferences,
            networkClient = NetworkClient(),
            imageDao = database.imageDao(),
            imageDatabase = database
        )
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
        private val allRequestsReceived = CountDownLatch(responseCount)
        private val worker = Thread {
            repeat(responseCount) { requestIndex ->
                socket.accept().use { connection ->
                    val reader = connection.getInputStream().bufferedReader()
                    val requestLine = reader.readLine() ?: error("Missing request line")
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
}
