package com.example

import com.example.data.model.ApiResponse
import com.example.data.model.ImageMetadata
import com.example.data.model.LoginData
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelParsingTest {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun `parse login response json with user object`() {
        val json = """
            {
                "message": "Login successful",
                "statusCode": 200,
                "status": true,
                "data": {
                    "user": {
                        "id": "64f1a2b3c4d5e6f7a8b9c0d1",
                        "email": "person@gmail.com",
                        "role": "admin"
                    }
                }
            }
        """.trimIndent()

        val type = Types.newParameterizedType(ApiResponse::class.java, LoginData::class.java)
        val adapter = moshi.adapter<ApiResponse<LoginData>>(type)
        val response = adapter.fromJson(json)

        assertNotNull(response)
        assertEquals("Login successful", response?.message)
        assertEquals(200, response?.statusCode)
        val user = response?.data?.user
        assertNotNull(user)
        assertEquals("64f1a2b3c4d5e6f7a8b9c0d1", user?.effectiveId)
        assertEquals("person@gmail.com", user?.email)
        assertEquals("admin", user?.role)
    }

    @Test
    fun `parse backend image metadata fields and compute compression savings`() {
        val json = """
            {
                "message": "Images retrieved",
                "statusCode": 200,
                "data": [
                    {
                        "_id": "img123",
                        "name": "sunset.webp",
                        "mimeType": "image/webp",
                        "format": "webp",
                        "width": 1920,
                        "height": 1080,
                        "originalSize": 2000000,
                        "storedSize": 800000,
                        "optimized": true
                    }
                ]
            }
        """.trimIndent()

        val listType = Types.newParameterizedType(List::class.java, ImageMetadata::class.java)
        val type = Types.newParameterizedType(ApiResponse::class.java, listType)
        val adapter = moshi.adapter<ApiResponse<List<ImageMetadata>>>(type)
        val response = adapter.fromJson(json)

        assertNotNull(response)
        val images = response?.data
        assertNotNull(images)
        assertEquals(1, images?.size)

        val image = images!![0]
        assertEquals("img123", image.effectiveId)
        assertEquals("sunset.webp", image.effectiveFilename)
        assertEquals("image/webp", image.effectiveMimeType)
        assertEquals("WEBP", image.effectiveFormat)
        assertEquals(1200000L, image.savingsBytes)
        assertEquals(60, image.savingsPercentage)
        assertTrue(image.effectiveOptimized)
    }

    @Test
    fun `parse numeric delete response data returned by backend`() {
        val json = """
            {
                "message": "Images deleted successfully",
                "statusCode": 200,
                "status": true,
                "data": 2
            }
        """.trimIndent()

        val type = Types.newParameterizedType(ApiResponse::class.java, Int::class.javaObjectType)
        val response = moshi.adapter<ApiResponse<Int>>(type).fromJson(json)

        assertEquals(2, response?.data)
    }

    @Test
    fun `legacy image metadata names remain readable`() {
        val json = """{"filename":"legacy.jpg","mimetype":"image/jpeg"}"""
        val metadata = moshi.adapter(ImageMetadata::class.java).fromJson(json)

        assertEquals("legacy.jpg", metadata?.effectiveFilename)
        assertEquals("image/jpeg", metadata?.effectiveMimeType)
    }

    @Test
    fun `image metadata handles null sizes gracefully`() {
        val meta = ImageMetadata(
            id = "test-id",
            filename = "sample.png",
            originalSize = null,
            storedSize = null
        )
        assertEquals(0L, meta.savingsBytes)
        assertEquals(0, meta.savingsPercentage)
    }
}
