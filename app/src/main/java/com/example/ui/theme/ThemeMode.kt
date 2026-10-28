package com.example.ui.theme

enum class ThemeMode(
    val displayName: String,
    val subtitle: String
) {
    SYSTEM(
        displayName = "System",
        subtitle = "Follows device appearance"
    ),
    LIGHT(
        displayName = "Light",
        subtitle = "Bright & crisp interface"
    ),
    DARK(
        displayName = "Dark",
        subtitle = "Sleek dark interface"
    );

    fun isDark(systemInDarkTheme: Boolean): Boolean = when (this) {
        SYSTEM -> systemInDarkTheme
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromString(value: String?): ThemeMode {
            return when (value?.uppercase()) {
                LIGHT.name -> LIGHT
                DARK.name -> DARK
                SYSTEM.name -> SYSTEM
                else -> SYSTEM
            }
        }
    }
}
