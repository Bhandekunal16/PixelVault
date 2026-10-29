package com.example.data.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.example.data.preferences.AppPreferences
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun isAllowedCleartextUrl(url: HttpUrl): Boolean =
    url.host in LOCAL_DEVELOPMENT_HOSTS ||
        (url.host == HOSTED_API_HOST && url.port == HOSTED_API_PORT)

private const val HOSTED_API_HOST = "147.224.251.137"
private const val HOSTED_API_PORT = 8001
private val LOCAL_DEVELOPMENT_HOSTS = setOf(
    "localhost",
    "127.0.0.1",
    "::1",
    "10.0.2.2"
)

class NetworkClient(
    private val isEncryptionEnabled: () -> Boolean = { false },
    private val getSecretKey: () -> String? = { null },
    private val getAlgorithm: () -> String = { "AES/CBC/PKCS5Padding" },
    private val getAccessToken: () -> String? = { null },
    private val onUnauthorized: (String) -> Unit = {}
) {
    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        // Basic or Headers in production; avoids dumping full binary or sensitive payload bodies
        level = HttpLoggingInterceptor.Level.HEADERS
        redactHeader("Authorization")
    }

    private val encryptionInterceptor = EncryptionInterceptor(
        isEncryptionEnabled = isEncryptionEnabled,
        getSecretKey = getSecretKey,
        getAlgorithm = getAlgorithm
    )

    val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .addInterceptor(encryptionInterceptor)
        .addInterceptor(authenticationInterceptor())
        .build()

    @Volatile
    private var currentBaseUrl: String = AppPreferences.DEFAULT_BASE_URL

    @Volatile
    private var currentApiService: ApiService? = null

    fun getApiService(baseUrl: String): ApiService {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        if (currentApiService == null || currentBaseUrl != normalized) {
            synchronized(this) {
                if (currentApiService == null || currentBaseUrl != normalized) {
                    currentBaseUrl = normalized
                    currentApiService = buildRetrofit(normalized).create(ApiService::class.java)
                }
            }
        }
        return currentApiService!!
    }

    private fun buildRetrofit(baseUrl: String): Retrofit {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    private fun authenticationInterceptor() = Interceptor { chain ->
        val originalRequest = chain.request()
        val url = originalRequest.url
        if (!url.isHttps && !isAllowedCleartextUrl(url)) {
            throw IOException("HTTPS is required except for the configured local and hosted development endpoints.")
        }

        val isPublicRoute = url.encodedPath in PUBLIC_ROUTES
        val suppliedToken = originalRequest.header("Authorization")
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
        val token = suppliedToken ?: if (isPublicRoute) null else getAccessToken()
        val request = if (suppliedToken != null || token.isNullOrBlank() || isPublicRoute) {
            originalRequest
        } else {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }

        val response = chain.proceed(request)
        if (
            response.code == 401 &&
            token != null &&
            !isPublicRoute &&
            url.encodedPath == IMAGE_ROUTE
        ) {
            onUnauthorized(token)
        }
        response
    }

    private companion object {
        const val IMAGE_ROUTE = "/image"
        val PUBLIC_ROUTES = setOf("/", "/login", "/register")
    }
}
