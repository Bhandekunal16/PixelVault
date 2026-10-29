package com.example.data.api

import com.example.data.model.ApiResponse
import com.example.data.model.DeleteImagesRequest
import com.example.data.model.ImageMetadata
import com.example.data.model.LoginData
import com.example.data.model.LoginRequest
import com.example.data.model.RegisterData
import com.example.data.model.RegisterRequest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import retrofit2.http.Streaming

interface ApiService {

    /**
     * Check basic server availability / health check.
     */
    @GET("/")
    suspend fun checkHealth(): Response<ResponseBody>

    /**
     * Create an account.
     * Gmail addresses only, password >= 8 chars.
     */
    @POST("/register")
    suspend fun register(
        @Body request: RegisterRequest
    ): Response<ApiResponse<RegisterData>>

    /**
     * Authenticate user.
     * On success returns user object in data.user.
     * Note: Does not return tokens or cookies.
     */
    @POST("/login")
    suspend fun login(
        @Body request: LoginRequest
    ): Response<ApiResponse<LoginData>>

    /**
     * Upload an image with conversion format and user email.
     * Supports up to 10 MiB image data.
     */
    @Multipart
    @POST("/image")
    suspend fun uploadImage(
        @Part image: MultipartBody.Part,
        @Part("email") email: RequestBody,
        @Part("format") format: RequestBody
    ): Response<ApiResponse<ImageMetadata>>

    /**
     * Retrieve image metadata associated with the user's email.
     */
    @GET("/image")
    suspend fun getImagesByEmail(
        @Query("email") email: String
    ): Response<ApiResponse<List<ImageMetadata>>>

    /**
     * Raw endpoint for image metadata query in case backend returns direct list or custom JSON.
     */
    @GET("/image")
    suspend fun getImagesByEmailRaw(
        @Query("email") email: String
    ): Response<ResponseBody>

    /**
     * Retrieve original stored image bytes as binary data.
     */
    @Streaming
    @GET("/image")
    suspend fun getImageBytes(
        @Query("id") id: String
    ): Response<ResponseBody>

    /**
     * Delete one or more images by ID (1 to 100 IDs).
     *
     * BACKEND LIMITATION NOTE:
     * The Express backend does not currently require authentication or ownership authorization
     * for image deletion. Any client possessing image IDs can trigger deletion.
     */
    @HTTP(method = "DELETE", path = "/image", hasBody = true)
    suspend fun deleteImages(
        @Body request: DeleteImagesRequest
    ): Response<ApiResponse<Int>>
}
