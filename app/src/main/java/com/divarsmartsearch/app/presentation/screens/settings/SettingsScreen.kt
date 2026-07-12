package com.divarsmartsearch.app.presentation.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.divarsmartsearch.app.presentation.components.AppTextField
import com.divarsmartsearch.app.presentation.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val settings = state.settings
    var apiKeyField by remember(settings.anthropicApiKey) { mutableStateOf(settings.anthropicApiKey.orEmpty()) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("تنظیمات") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                SectionHeader("جستجوی خودکار در پس‌زمینه")
                Text(
                    "وقتی روشن باشد، دیگر لازم نیست خودتان برنامه را باز کنید و صفحه را اسکرول کنید — " +
                        "برنامه هر چند دقیقه یک‌بار خودش جستجوهای فعال شما را در دیوار بررسی می‌کند و " +
                        "فقط برای آگهی‌هایی که صد‌درصد از فیلترها رد شده‌اند اعلان می‌فرستد.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                SettingRow(
                    title = "اسکن خودکار در پس‌زمینه فعال باشد",
                    checked = settings.backgroundScanEnabled,
                    onCheckedChange = viewModel::updateBackgroundScanEnabled,
                )
            }
            if (settings.backgroundScanEnabled) {
                item {
                    Text(
                        "فاصله بین هر بررسی: هر ${settings.backgroundScanIntervalMinutes} دقیقه",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = settings.backgroundScanIntervalMinutes.toFloat(),
                        onValueChange = { viewModel.updateBackgroundScanInterval(it.toInt()) },
                        valueRange = 2f..30f,
                        steps = 27,
                    )
                }
            }
            item {
                SettingRow(
                    title = "حالت شب",
                    checked = settings.darkModeEnabled,
                    onCheckedChange = viewModel::updateDarkMode,
                )
            }
            item {
                SettingRow(
                    title = "اعلان‌ها فعال باشند",
                    checked = settings.notificationsEnabled,
                    onCheckedChange = viewModel::updateNotificationsEnabled,
                )
            }
            item {
                SettingRow(
                    title = "صدای اعلان",
                    checked = settings.notificationSoundEnabled,
                    onCheckedChange = viewModel::updateNotificationSound,
                )
            }

            item {
                SectionHeader("آستانه تشخیص مشاور املاک", modifier = Modifier.padding(top = 8.dp))
                Text(
                    "هرچه بالاتر، سخت‌گیرانه‌تر عمل می‌کند (فعلی: ${(settings.ownerDetectionThreshold * 100).toInt()}٪)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = settings.ownerDetectionThreshold.toFloat(),
                    onValueChange = { viewModel.updateOwnerDetectionThreshold(it.toDouble()) },
                    valueRange = 0.1f..0.9f,
                )
            }

            item {
                SectionHeader("تحلیل هوشمند متن آگهی (اختیاری)", modifier = Modifier.padding(top = 8.dp))
                Text(
                    "برای تحلیل دقیق‌تر متن آگهی با هوش مصنوعی Claude، کلید API خودتان را وارد کنید. " +
                        "بدون این کلید، تشخیص با روش قانون‌محور آفلاین انجام می‌شود.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                AppTextField(
                    value = apiKeyField,
                    onValueChange = { apiKeyField = it },
                    label = "کلید API آنتروپیک (Anthropic)",
                    keyboardType = KeyboardType.Password,
                )
            }
            item {
                androidx.compose.material3.Button(
                    onClick = { viewModel.updateAnthropicApiKey(apiKeyField) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("ذخیره کلید API")
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
