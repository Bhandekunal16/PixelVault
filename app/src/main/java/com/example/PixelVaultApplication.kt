package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.data.api.NetworkClient
import com.example.data.preferences.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class PixelVaultApplication : Application(), ImageLoaderFactory {
    lateinit var preferences: AppPreferences
        private set

    lateinit var networkClient: NetworkClient
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(applicationContext)
        networkClient = NetworkClient(
            isEncryptionEnabled = {
                runBlocking { preferences.encryptionEnabledFlow.first() }
            },
            getSecretKey = {
                runBlocking { preferences.encryptionSecretFlow.first() }
            },
            getAlgorithm = {
                runBlocking { preferences.encryptionAlgoFlow.first() }
            },
            getAccessToken = {
                runBlocking { preferences.accessTokenFlow.first() }
            },
            onUnauthorized = { token ->
                runBlocking { preferences.clearSessionIfAccessToken(token) }
            }
        )
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(networkClient.okHttpClient)
            .crossfade(true)
            .build()
}
