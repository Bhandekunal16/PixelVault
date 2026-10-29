package com.example

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.api.NetworkClient
import com.example.data.local.ImageDatabase
import com.example.data.preferences.AppPreferences
import com.example.data.repository.AuthRepositoryImpl
import com.example.data.repository.ImageRepositoryImpl
import com.example.security.AppLockManager
import com.example.security.BiometricAuthManager
import com.example.ui.navigation.Screen
import com.example.ui.screens.MainScreen
import com.example.ui.screens.auth.LoginScreen
import com.example.ui.screens.auth.RegisterScreen
import com.example.ui.screens.lock.AppLockScreen
import com.example.ui.screens.splash.SplashScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ThemeMode
import com.example.ui.viewmodel.AppViewModelFactory
import com.example.ui.viewmodel.AuthViewModel
import com.example.ui.viewmodel.DashboardViewModel
import com.example.ui.viewmodel.DetailViewModel
import com.example.ui.viewmodel.ImagesViewModel
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.UploadViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private lateinit var preferences: AppPreferences
    private lateinit var networkClient: NetworkClient
    private lateinit var authRepository: AuthRepositoryImpl
    private lateinit var imageRepository: ImageRepositoryImpl
    private lateinit var viewModelFactory: AppViewModelFactory
    private lateinit var biometricAuthManager: BiometricAuthManager
    private lateinit var appLockManager: AppLockManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)

        val pixelVaultApplication = application as PixelVaultApplication
        preferences = pixelVaultApplication.preferences
        networkClient = pixelVaultApplication.networkClient
        biometricAuthManager = BiometricAuthManager(applicationContext)
        appLockManager = AppLockManager()

        // If app lock is enabled and user is already logged in on cold start, lock immediately
        lifecycleScope.launch {
            val isLockEnabled = preferences.appLockEnabledFlow.first()
            val isLoggedIn = preferences.isLoggedInFlow.first()
            if (isLockEnabled && isLoggedIn) {
                appLockManager.lockNow()
            }
        }

        authRepository = AuthRepositoryImpl(preferences, networkClient)
        val imageDatabase = ImageDatabase.getInstance(applicationContext)
        imageRepository = ImageRepositoryImpl(
            preferences = preferences,
            networkClient = networkClient,
            imageDao = imageDatabase.imageDao(),
            imageDatabase = imageDatabase
        )
        viewModelFactory = AppViewModelFactory(
            authRepository = authRepository,
            imageRepository = imageRepository,
            preferences = preferences,
            biometricAuthManager = biometricAuthManager,
            appLockManager = appLockManager
        )

        val authViewModel = ViewModelProvider(this, viewModelFactory)[AuthViewModel::class.java]
        val dashboardViewModel = ViewModelProvider(this, viewModelFactory)[DashboardViewModel::class.java]
        val uploadViewModel = ViewModelProvider(this, viewModelFactory)[UploadViewModel::class.java]
        val imagesViewModel = ViewModelProvider(this, viewModelFactory)[ImagesViewModel::class.java]
        val detailViewModel = ViewModelProvider(this, viewModelFactory)[DetailViewModel::class.java]
        val settingsViewModel = ViewModelProvider(this, viewModelFactory)[SettingsViewModel::class.java]

        setContent {
            val themeMode by preferences.themeModeFlow.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val dynamicColor by preferences.dynamicColorFlow.collectAsStateWithLifecycle(
                initialValue = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
            )
            MyApplicationTheme(
                themeMode = themeMode,
                dynamicColor = dynamicColor
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val isLocked by appLockManager.isLocked.collectAsStateWithLifecycle()
                    val appLockEnabled by preferences.appLockEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
                    val isLoggedIn by preferences.isLoggedInFlow.collectAsStateWithLifecycle(initialValue = false)
                    var wasLoggedIn by remember { mutableStateOf(false) }

                    LaunchedEffect(isLoggedIn) {
                        if (
                            wasLoggedIn &&
                            !isLoggedIn &&
                            navController.currentDestination?.route != Screen.Login.route
                        ) {
                            navController.navigate(Screen.Login.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                        wasLoggedIn = isLoggedIn
                    }

                    // Window security flag handling
                    LaunchedEffect(isLocked, appLockEnabled) {
                        appLockManager.updateWindowSecurity(
                            this@MainActivity,
                            secure = isLocked || appLockEnabled
                        )
                    }

                    if (isLoggedIn && appLockEnabled && isLocked) {
                        AppLockScreen(
                            authManager = biometricAuthManager,
                            onUnlocked = {
                                appLockManager.unlock()
                            },
                            onLogout = {
                                settingsViewModel.logout {
                                    navController.navigate(Screen.Login.route) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                            }
                        )
                    } else {
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
                                        appLockManager.unlock()
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
                                        appLockManager.reset()
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

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            val isLockEnabled = preferences.appLockEnabledFlow.first()
            val isLoggedIn = preferences.isLoggedInFlow.first()
            val timeoutSeconds = preferences.lockTimeoutSecondsFlow.first()
            appLockManager.onAppForegrounded(
                isLockEnabled = isLockEnabled,
                isLoggedIn = isLoggedIn,
                timeoutSeconds = timeoutSeconds
            )
        }
    }

    override fun onStop() {
        super.onStop()
        appLockManager.onAppBackgrounded()
    }
}
