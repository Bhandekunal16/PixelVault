package com.example.data.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class NetworkClient(
    private val isEncryptionEnabled: () -> Boolean = { false },
    private val getSecretKey: () -> String? = { null },
    private val getAlgorithm: () -> String = { "AES/CBC/PKCS5Padding" }
) {
    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        // Basic or Headers in production; avoids dumping full binary or sensitive payload bodies
        level = HttpLoggingInterceptor.Level.HEADERS
    }

    private val encryptionInterceptor = EncryptionInterceptor(
        isEncryptionEnabled = isEncryptionEnabled,
        getSecretKey = getSecretKey,
        getAlgorithm = getAlgorithm
    )

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .addInterceptor(encryptionInterceptor)
        .build()

    @Volatile
    private var currentBaseUrl: String = "http://147.224.251.137:8001/"

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
}
