package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.api.NetworkClient
import com.example.data.preferences.AppPreferences
import com.example.data.repository.AuthRepositoryImpl
import com.example.data.repository.ImageRepositoryImpl
import com.example.ui.navigation.Screen
import com.example.ui.screens.MainScreen
import com.example.ui.screens.auth.LoginScreen
import com.example.ui.screens.auth.RegisterScreen
import com.example.ui.screens.splash.SplashScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.AppViewModelFactory
import com.example.ui.viewmodel.AuthViewModel
import com.example.ui.viewmodel.DashboardViewModel
import com.example.ui.viewmodel.DetailViewModel
import com.example.ui.viewmodel.ImagesViewModel
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.UploadViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    private lateinit var preferences: AppPreferences
    private lateinit var networkClient: NetworkClient
    private lateinit var authRepository: AuthRepositoryImpl
    private lateinit var imageRepository: ImageRepositoryImpl
    private lateinit var viewModelFactory: AppViewModelFactory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        preferences = AppPreferences(applicationContext)

        // Wire dynamic encryption settings to OkHttp interceptor
        networkClient = NetworkClient(
            isEncryptionEnabled = {
                runBlocking { preferences.encryptionEnabledFlow.first() }
            },
            getSecretKey = {
                runBlocking { preferences.encryptionSecretFlow.first() }
            },
            getAlgorithm = {
                runBlocking { preferences.encryptionAlgoFlow.first() }
            }
        )

        authRepository = AuthRepositoryImpl(preferences, networkClient)
        imageRepository = ImageRepositoryImpl(preferences, networkClient)
        viewModelFactory = AppViewModelFactory(authRepository, imageRepository, preferences)

        val authViewModel = ViewModelProvider(this, viewModelFactory)[AuthViewModel::class.java]
        val dashboardViewModel = ViewModelProvider(this, viewModelFactory)[DashboardViewModel::class.java]
        val uploadViewModel = ViewModelProvider(this, viewModelFactory)[UploadViewModel::class.java]
        val imagesViewModel = ViewModelProvider(this, viewModelFactory)[ImagesViewModel::class.java]
        val detailViewModel = ViewModelProvider(this, viewModelFactory)[DetailViewModel::class.java]
        val settingsViewModel = ViewModelProvider(this, viewModelFactory)[SettingsViewModel::class.java]

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = Screen.Splash.route
                    ) {
                        composable(Screen.Splash.route) {
                            SplashScreen(
                                preferences = preferences,
                                onNavigateToLogin = {
                                    navController.navigate(Screen.Login.route) {
                                        popUpTo(Screen.Splash.route) { inclusive = true }
                                    }
                                },
                                onNavigateToMain = {
                                    navController.navigate(Screen.Main.route) {
                                        popUpTo(Screen.Splash.route) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable(Screen.Login.route) {
                            LoginScreen(
                                viewModel = authViewModel,
                                onNavigateToRegister = {
                                    navController.navigate(Screen.Register.route)
                                },
                                onNavigateToDashboard = {
                                    dashboardViewModel.loadDashboardData()
                                    navController.navigate(Screen.Main.route) {
                                        popUpTo(Screen.Login.route) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable(Screen.Register.route) {
                            RegisterScreen(
                                viewModel = authViewModel,
                                onNavigateToLogin = {
                                    navController.popBackStack()
                                }
                            )
                        }

                        composable(Screen.Main.route) {
                            MainScreen(
                                preferences = preferences,
                                dashboardViewModel = dashboardViewModel,
                                uploadViewModel = uploadViewModel,
                                imagesViewModel = imagesViewModel,
                                detailViewModel = detailViewModel,
                                settingsViewModel = settingsViewModel,
                                onLogout = {
                                    navController.navigate(Screen.Login.route) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
