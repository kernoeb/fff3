package com.kernoeb.fff3.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kernoeb.fff3.BuildConfig
import com.kernoeb.fff3.R
import com.kernoeb.fff3.ble.BleTuning
import com.kernoeb.fff3.data.LedRepository
import com.kernoeb.fff3.data.Settings
import com.kernoeb.fff3.ui.appViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: LedRepository) : ViewModel() {

    val settings: StateFlow<Settings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    fun setAccentFollowsStrip(enabled: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(accentFollowsStrip = enabled) } }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(hapticsEnabled = enabled) } }
    }
}

/** App settings: look, vibration, advanced timing and about. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { SettingsViewModel(it.repository) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var showLicences by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section(stringResource(R.string.settings_section_general)) {
                SwitchItem(
                    title = stringResource(R.string.settings_accent_title),
                    body = stringResource(R.string.settings_accent_body),
                    checked = settings.accentFollowsStrip,
                    onChange = viewModel::setAccentFollowsStrip,
                )
                SwitchItem(
                    title = stringResource(R.string.settings_haptics_title),
                    body = stringResource(R.string.settings_haptics_body),
                    checked = settings.hapticsEnabled,
                    onChange = viewModel::setHapticsEnabled,
                )
            }
            Section(stringResource(R.string.settings_section_advanced)) {
                ListItem(
                    colors = transparentItem(),
                    supportingContent = {
                        Text(stringResource(R.string.settings_frame_interval_body, BleTuning.MIN_FRAME_INTERVAL_MS.toInt()))
                    },
                ) { Text(stringResource(R.string.settings_frame_interval)) }
            }
            Section(stringResource(R.string.settings_section_about)) {
                ListItem(
                    colors = transparentItem(),
                    supportingContent = { Text(BuildConfig.VERSION_NAME) },
                ) { Text(stringResource(R.string.settings_version)) }
                ListItem(
                    modifier = Modifier.clickable { showLicences = true },
                    colors = transparentItem(),
                ) { Text(stringResource(R.string.settings_licences)) }
            }
        }
    }

    if (showLicences) {
        AlertDialog(
            onDismissRequest = { showLicences = false },
            title = { Text(stringResource(R.string.settings_licences)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_licences_body))
                    Text(stringResource(R.string.settings_licences_list), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicences = false }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp),
    )
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) { Column(Modifier.padding(vertical = 4.dp)) { content() } }
}

@Composable
private fun SwitchItem(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val haptic = LocalHapticFeedback.current
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        colors = transparentItem(),
        supportingContent = { Text(body) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = {
                    haptic.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    onChange(it)
                },
            )
        },
    ) { Text(title) }
}

@Composable
private fun transparentItem() = ListItemDefaults.colors(containerColor = Color.Transparent)
