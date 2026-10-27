package com.example.ui.navigation

sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Login : Screen("login")
    data object Register : Screen("register")
    data object Main : Screen("main")
    data object ImageDetail : Screen("image_detail")
}

sealed class BottomNavTab(val route: String, val title: String) {
    data object Home : BottomNavTab("tab_home", "Dashboard")
    data object Upload : BottomNavTab("tab_upload", "Upload")
    data object Images : BottomNavTab("tab_images", "My Images")
    data object Profile : BottomNavTab("tab_profile", "Settings")
}
