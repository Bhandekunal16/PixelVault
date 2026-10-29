package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.ImageMetadata
import com.example.data.preferences.AppPreferences
import com.example.ui.navigation.BottomNavTab
import com.example.ui.screens.detail.ImageDetailScreen
import com.example.ui.screens.home.HomeScreen
import com.example.ui.screens.images.ImagesListScreen
import com.example.ui.screens.profile.ProfileScreen
import com.example.ui.screens.upload.UploadScreen
import com.example.ui.viewmodel.DashboardViewModel
import com.example.ui.viewmodel.DetailViewModel
import com.example.ui.viewmodel.ImagesViewModel
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.UploadViewModel
import com.example.util.rememberHapticHelper
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    preferences: AppPreferences,
    dashboardViewModel: DashboardViewModel,
    uploadViewModel: UploadViewModel,
    imagesViewModel: ImagesViewModel,
    detailViewModel: DetailViewModel,
    settingsViewModel: SettingsViewModel,
    onLogout: () -> Unit
) {
    val baseUrl by preferences.baseUrlFlow.collectAsState(initial = AppPreferences.DEFAULT_BASE_URL)
    val haptic = rememberHapticHelper()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var currentTab by rememberSaveable { mutableStateOf<String>(BottomNavTab.Home.route) }
    var selectedImageForDetail by rememberSaveable { mutableStateOf<ImageMetadata?>(null) }

    // If an image is selected, display detail screen
    if (selectedImageForDetail != null) {
        ImageDetailScreen(
            metadata = selectedImageForDetail!!,
            baseUrl = baseUrl,
            viewModel = detailViewModel,
            onNavigateBack = {
                selectedImageForDetail = null
                // Refresh list when returning from detail
                imagesViewModel.loadImages(isRefresh = true)
                dashboardViewModel.loadDashboardData()
            }
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            Box {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .testTag("main_bottom_nav_bar")
                ) {
                    val navItemColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Home Tab
                    NavigationBarItem(
                        selected = currentTab == BottomNavTab.Home.route,
                        onClick = {
                            if (currentTab != BottomNavTab.Home.route) {
                                haptic.navigation()
                                currentTab = BottomNavTab.Home.route
                            }
                        },
                        icon = {
                            Icon(
                                if (currentTab == BottomNavTab.Home.route) Icons.Default.Dashboard else Icons.Outlined.Dashboard,
                                contentDescription = "Dashboard"
                            )
                        },
                        label = { Text("Dashboard") },
                        colors = navItemColors,
                        modifier = Modifier.testTag("nav_tab_dashboard")
                    )

                    // Upload Tab
                    NavigationBarItem(
                        selected = currentTab == BottomNavTab.Upload.route,
                        onClick = {
                            if (currentTab != BottomNavTab.Upload.route) {
                                haptic.navigation()
                                currentTab = BottomNavTab.Upload.route
                            }
                        },
                        icon = {
                            Icon(
                                if (currentTab == BottomNavTab.Upload.route) Icons.Default.CloudUpload else Icons.Outlined.CloudUpload,
                                contentDescription = "Upload"
                            )
                        },
                        label = { Text("Upload") },
                        colors = navItemColors,
                        modifier = Modifier.testTag("nav_tab_upload")
                    )

                    // Images Tab
                    NavigationBarItem(
                        selected = currentTab == BottomNavTab.Images.route,
                        onClick = {
                            if (currentTab != BottomNavTab.Images.route) {
                                haptic.navigation()
                                currentTab = BottomNavTab.Images.route
                            }
                        },
                        icon = {
                            Icon(
                                if (currentTab == BottomNavTab.Images.route) Icons.Default.Collections else Icons.Outlined.Collections,
                                contentDescription = "My Images"
                            )
                        },
                        label = { Text("My Images") },
                        colors = navItemColors,
                        modifier = Modifier.testTag("nav_tab_images")
                    )

                    // Profile Tab
                    NavigationBarItem(
                        selected = currentTab == BottomNavTab.Profile.route,
                        onClick = {
                            if (currentTab != BottomNavTab.Profile.route) {
                                haptic.navigation()
                                currentTab = BottomNavTab.Profile.route
                            }
                        },
                        icon = {
                            Icon(
                                if (currentTab == BottomNavTab.Profile.route) Icons.Default.Settings else Icons.Outlined.Settings,
                                contentDescription = "Settings"
                            )
                        },
                        label = { Text("Settings") },
                        colors = navItemColors,
                        modifier = Modifier.testTag("nav_tab_profile")
                    )
                }
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                BottomNavTab.Home.route -> {
                    HomeScreen(
                        viewModel = dashboardViewModel,
                        baseUrl = baseUrl,
                        onNavigateToUpload = { currentTab = BottomNavTab.Upload.route },
                        onNavigateToImages = { currentTab = BottomNavTab.Images.route },
                        onImageSelected = { selectedImageForDetail = it },
                        onLogout = onLogout
                    )
                }
                BottomNavTab.Upload.route -> {
                    UploadScreen(
                        viewModel = uploadViewModel,
                        onUploadSuccess = {
                            imagesViewModel.loadImages(isRefresh = true)
                            dashboardViewModel.loadDashboardData()
                            currentTab = BottomNavTab.Images.route
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Image uploaded successfully",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        }
                    )
                }
                BottomNavTab.Images.route -> {
                    ImagesListScreen(
                        viewModel = imagesViewModel,
                        baseUrl = baseUrl,
                        onNavigateToUpload = { currentTab = BottomNavTab.Upload.route },
                        onImageSelected = { selectedImageForDetail = it }
                    )
                }
                BottomNavTab.Profile.route -> {
                    ProfileScreen(
                        viewModel = settingsViewModel,
                        onLogout = onLogout
                    )
                }
            }
        }
    }
}
