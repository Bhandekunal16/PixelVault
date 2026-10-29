package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.data.api.NetworkClient
import com.example.data.local.ImageDatabase
import com.example.data.preferences.AppPreferences
import com.example.data.repository.ImageRepositoryImpl
import com.example.data.repository.WorkManagerUploadScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class PixelVaultApplication : Application(), ImageLoaderFactory {
    lateinit var preferences: AppPreferences
        private set

    lateinit var networkClient: NetworkClient
        private set

    lateinit var imageRepository: ImageRepositoryImpl
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        val imageDatabase = ImageDatabase.getInstance(applicationContext)
        imageRepository = ImageRepositoryImpl(
            preferences = preferences,
            networkClient = networkClient,
            imageDao = imageDatabase.imageDao(),
            imageDatabase = imageDatabase,
            uploadScheduler = WorkManagerUploadScheduler(applicationContext),
            applicationContext = applicationContext
        )

        applicationScope.launch {
            try {
                imageRepository.cleanupQueuedUploadFiles()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("PixelVaultApplication", "Queued upload file cleanup failed.", e)
            }
            combine(
                preferences.isLoggedInFlow,
                preferences.userEmailFlow
            ) { isLoggedIn, email -> isLoggedIn to email }
                .collectLatest { (isLoggedIn, email) ->
                    if (isLoggedIn && !email.isNullOrBlank()) {
                        try {
                            imageRepository.resumeQueuedUploads(email)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.e(
                                "PixelVaultApplication",
                                "Could not resume the account upload queue.",
                                e
                            )
                        }
                    }
                }
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(networkClient.okHttpClient)
            .crossfade(true)
            .build()
}
