package com.kernoeb.fff3.ui.settings

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
}

/** Placeholder settings screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { SettingsViewModel(it.repository) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()

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
        Column(Modifier.fillMaxSize().padding(padding)) {
            ListItem(
                supportingContent = { Text(stringResource(R.string.settings_accent_body)) },
                trailingContent = {
                    Switch(checked = settings.accentFollowsStrip, onCheckedChange = viewModel::setAccentFollowsStrip)
                },
            ) { Text(stringResource(R.string.settings_accent_title)) }
            ListItem(
                supportingContent = {
                    Text(stringResource(R.string.settings_frame_interval_value, BleTuning.MIN_FRAME_INTERVAL_MS.toInt()))
                },
            ) { Text(stringResource(R.string.settings_frame_interval)) }
            ListItem(
                supportingContent = { Text(BuildConfig.VERSION_NAME) },
            ) { Text(stringResource(R.string.settings_version)) }
        }
    }
}
