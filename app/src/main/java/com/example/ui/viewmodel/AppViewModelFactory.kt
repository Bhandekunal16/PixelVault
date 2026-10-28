package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.data.preferences.AppPreferences
import com.example.data.repository.AuthRepository
import com.example.data.repository.ImageRepository
import com.example.security.AppLockManager
import com.example.security.BiometricAuthManager

class AppViewModelFactory(
    private val authRepository: AuthRepository,
    private val imageRepository: ImageRepository,
    private val preferences: AppPreferences,
    private val biometricAuthManager: BiometricAuthManager? = null,
    private val appLockManager: AppLockManager? = null
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(AuthViewModel::class.java) -> {
                AuthViewModel(authRepository) as T
            }
            modelClass.isAssignableFrom(DashboardViewModel::class.java) -> {
                DashboardViewModel(authRepository, imageRepository) as T
            }
            modelClass.isAssignableFrom(UploadViewModel::class.java) -> {
                UploadViewModel(authRepository, imageRepository) as T
            }
            modelClass.isAssignableFrom(ImagesViewModel::class.java) -> {
                ImagesViewModel(authRepository, imageRepository) as T
            }
            modelClass.isAssignableFrom(DetailViewModel::class.java) -> {
                DetailViewModel(imageRepository) as T
            }
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> {
                SettingsViewModel(
                    preferences = preferences,
                    authRepository = authRepository,
                    imageRepository = imageRepository,
                    biometricAuthManager = biometricAuthManager,
                    appLockManager = appLockManager
                ) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
