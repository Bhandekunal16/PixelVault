package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.api.NetworkClient
import com.example.data.local.ImageDatabase
import com.example.data.local.ImageEntity
import com.example.data.preferences.AppPreferences
import com.example.data.repository.ImageRepositoryImpl
import com.example.util.Resource
import kotlinx.coroutines.flow.first
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
        preferences = AppPreferences(context)
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

        assertTrue(repository.synchronizeImages("person@gmail.com") is Resource.Success)
        assertEquals(1, server.requestCount.get())
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

        val serverError = server(500, """{"message":"Database unavailable","statusCode":500,"status":false,"data":[]}""")
        val errorResult = repository(serverError)
            .synchronizeImages("person@gmail.com", forceRefresh = true)
        serverError.awaitRequest()
        assertTrue(errorResult is Resource.Error)
        assertEquals(
            listOf("cached"),
            database.imageDao().observeImages("person@gmail.com").first().map(ImageEntity::serverId)
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

    private suspend fun repository(server: SingleResponseServer): ImageRepositoryImpl {
        preferences.setBaseUrl(server.baseUrl)
        return ImageRepositoryImpl(
            preferences = preferences,
            networkClient = NetworkClient(),
            imageDao = database.imageDao(),
            imageDatabase = database
        )
    }

    private fun server(status: Int, body: String): SingleResponseServer =
        SingleResponseServer(status, body).also(servers::add)

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
        body: String
    ) : Closeable {
        private val socket = ServerSocket(0)
        val requestCount = AtomicInteger()
        private val worker = Thread {
            socket.accept().use { connection ->
                val reader = connection.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) Unit
                requestCount.incrementAndGet()

                val bytes = body.toByteArray(Charsets.UTF_8)
                val reason = when (statusCode) {
                    200 -> "OK"
                    404 -> "Not Found"
                    else -> "Internal Server Error"
                }
                val headers = "HTTP/1.1 $statusCode $reason\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                connection.getOutputStream().apply {
                    write(headers.toByteArray(Charsets.UTF_8))
                    write(bytes)
                    flush()
                }
            }
        }.apply {
            name = "image-repository-test-server"
            isDaemon = true
            start()
        }

        val baseUrl = "http://127.0.0.1:${socket.localPort}/"

        fun awaitRequest() {
            worker.join(5_000)
            check(!worker.isAlive) { "Repository did not complete its HTTP request." }
        }

        override fun close() {
            socket.close()
            worker.join(1_000)
        }
    }
}
