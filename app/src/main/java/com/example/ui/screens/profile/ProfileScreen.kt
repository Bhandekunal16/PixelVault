package com.example.ui.screens.profile

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.security.BiometricCapability
import com.example.ui.components.AccountDetailsCard
import com.example.ui.components.AutoLockTimeoutSegmentedControl
import com.example.ui.components.ConfirmationDialog
import com.example.ui.components.SettingsSectionHeader
import com.example.ui.theme.AppDimens
import com.example.ui.theme.ThemeMode
import com.example.ui.viewmodel.SettingsViewModel
import com.example.util.rememberHapticHelper

@Composable
fun ProfileScreen(
    viewModel: SettingsViewModel,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val haptic = rememberHapticHelper()
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    var secretInput by remember(uiState.encryptionSecret) { mutableStateOf(uiState.encryptionSecret) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshBiometricCapability()
    }

    if (showLogoutConfirm) {
        ConfirmationDialog(
            title = "Log Out?",
            message = "Are you sure you want to log out of your account?",
            confirmLabel = "Log Out",
            isDestructive = false,
            onConfirm = {
                showLogoutConfirm = false
                viewModel.logout(onLogout)
            },
            onDismiss = { showLogoutConfirm = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(16.dp)
            .testTag("profile_screen")
    ) {
        Text(
            text = "Profile & Settings",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Manage appearance, device security, and encryption",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(AppDimens.sectionSpacing))

        SettingsSectionHeader(
            title = "Account",
            subtitle = "Your signed-in identity"
        )
        Spacer(modifier = Modifier.height(10.dp))

        AccountDetailsCard(
            userEmail = uiState.userEmail,
            userId = uiState.userId,
            userRole = uiState.userRole
        )

        Spacer(modifier = Modifier.height(AppDimens.sectionSpacing))

        SettingsSectionHeader(
            title = "Appearance",
            subtitle = "Theme and Material You colors"
        )
        Spacer(modifier = Modifier.height(10.dp))

        // Appearance & Display Mode Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("theme_settings_card"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Appearance & Theme",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Choose between pure solid Dark theme, clean Light theme, or automatically match device appearance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                val isSystemDark = isSystemInDarkTheme()
                val themeOptions = listOf(
                    Triple(ThemeMode.SYSTEM, Icons.Default.BrightnessAuto, "theme_option_system"),
                    Triple(ThemeMode.LIGHT, Icons.Default.LightMode, "theme_option_light"),
                    Triple(ThemeMode.DARK, Icons.Default.DarkMode, "theme_option_dark")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    themeOptions.forEach { (mode, icon, tag) ->
                        val isSelected = uiState.themeMode == mode
                        Surface(
                            onClick = {
                                if (uiState.themeMode != mode) {
                                    haptic.tick()
                                }
                                viewModel.setThemeMode(mode)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag(tag),
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            border = if (isSelected) {
                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            }
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(vertical = 14.dp, horizontal = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = mode.displayName,
                                    modifier = Modifier.size(24.dp),
                                    tint = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = mode.displayName,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    ),
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                    }
                }

                if (uiState.isDynamicColorSupported) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Dynamic Color (Material You)",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Sample accent tones dynamically from your device wallpaper (Android 12+)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = uiState.dynamicColor,
                            onCheckedChange = {
                                haptic.toggle()
                                viewModel.setDynamicColor(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.testTag("dynamic_color_switch")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                val colorStyleDesc = if (uiState.dynamicColor && uiState.isDynamicColorSupported) {
                    "Material You wallpaper colors"
                } else {
                    "Curated solid brand palette"
                }

                val activeThemeDescription = when (uiState.themeMode) {
                    ThemeMode.SYSTEM -> if (isSystemDark) {
                        "System default active (currently Dark mode) • $colorStyleDesc"
                    } else {
                        "System default active (currently Light mode) • $colorStyleDesc"
                    }
                    ThemeMode.LIGHT -> "Light mode active • $colorStyleDesc"
                    ThemeMode.DARK -> "Dark mode active • $colorStyleDesc"
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "• $activeThemeDescription",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(AppDimens.sectionSpacing))

        SettingsSectionHeader(
            title = "Security",
            subtitle = "Biometrics, device lock, and auto-lock timeout"
        )
        Spacer(modifier = Modifier.height(10.dp))

        // Device Lock & Biometric Security Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("app_lock_settings_card"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Biometric & Device Lock",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Switch(
                        checked = uiState.appLockEnabled,
                        onCheckedChange = { isChecked ->
                            haptic.toggle()
                            viewModel.setAppLockEnabled(isChecked)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.testTag("app_lock_switch")
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Require fingerprint, face unlock, or device PIN/pattern to access ImageVault.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Hardware Status Badge
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.testTag("status_capability_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (uiState.biometricCapability.canAuthenticate) Icons.Default.Shield else Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (uiState.biometricCapability.canAuthenticate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = uiState.biometricCapability.displayName,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // If no security enrolled, provide guidance link
                if (!uiState.biometricCapability.canAuthenticate) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.testTag("setup_screen_lock_button")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Configure Device Screen Lock", color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                if (uiState.appLockEnabled) {
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Auto-Lock Timeout (After Backgrounding):",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    AutoLockTimeoutSegmentedControl(
                        selectedTimeoutSeconds = uiState.lockTimeoutSeconds,
                        onTimeoutSelected = { seconds ->
                            if (uiState.lockTimeoutSeconds != seconds) {
                                haptic.tick()
                            }
                            viewModel.setLockTimeoutSeconds(seconds)
                        }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            haptic.click()
                            viewModel.lockNow()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("lock_now_button")
                    ) {
                        Icon(Icons.Default.LockClock, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Lock Vault Now", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(AppDimens.sectionSpacing))

        SettingsSectionHeader(
            title = "Advanced",
            subtitle = "Optional encrypted JSON responses"
        )
        Spacer(modifier = Modifier.height(10.dp))

        // Response Encryption Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("encryption_settings_card"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Response Decryption",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Switch(
                        checked = uiState.encryptionEnabled,
                        onCheckedChange = { isChecked ->
                            haptic.toggle()
                            viewModel.setEncryptionConfig(isChecked, secretInput)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.testTag("encryption_toggle")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Enable automatic payload decryption for encrypted data responses.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (uiState.encryptionEnabled) {
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = secretInput,
                        onValueChange = {
                            secretInput = it
                            viewModel.setEncryptionConfig(true, it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("encryption_secret_input"),
                        label = { Text("AES Secret Key (Hex or UTF-8)") },
                        placeholder = { Text("Enter shared secret key") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Algorithm: ${uiState.encryptionAlgo} (AES-128/256-CBC)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // App Version & Logout
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Version: ${uiState.appVersion}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = {
                    haptic.click()
                    showLogoutConfirm = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("profile_logout_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onError)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Log Out", color = MaterialTheme.colorScheme.onError)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
