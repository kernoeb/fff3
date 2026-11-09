package com.kernoeb.fff3.ui.add

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernoeb.fff3.R
import com.kernoeb.fff3.ui.appViewModel

/** Placeholder add screen: Companion Device Manager first, a plain scan as fallback. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceScreen(onDone: () -> Unit, onBack: () -> Unit) {
    val viewModel = appViewModel { AddDeviceViewModel(it.repository, it.controller, it.pairing, it.scanner) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var scanPermissionDenied by rememberSaveable { mutableStateOf(false) }

    val pairingSheet = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (viewModel.onPairingResult(result.resultCode, result.data)) onDone()
    }
    val scanPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        scanPermissionDenied = grants.values.any { !it }
        if (!scanPermissionDenied) viewModel.startScan()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.add_pair_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (state.pairingAvailable) R.string.add_pair_body else R.string.add_pair_unavailable),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                enabled = state.pairingAvailable,
                onClick = {
                    viewModel.startPairing { sender -> pairingSheet.launch(IntentSenderRequest.Builder(sender).build()) }
                },
            ) {
                Icon(painterResource(R.drawable.ic_bluetooth_searching), contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.add_pair_button))
            }

            state.error?.let { error ->
                Spacer(Modifier.height(12.dp))
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(40.dp))
            Text(stringResource(R.string.add_scan_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.add_scan_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row {
                if (state.scanning) {
                    FilledTonalButton(onClick = viewModel::stopScan) { Text(stringResource(R.string.add_scan_stop)) }
                } else {
                    FilledTonalButton(onClick = {
                        scanPermissions.launch(
                            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
                        )
                    }) { Text(stringResource(R.string.add_scan_button)) }
                }
            }
            if (scanPermissionDenied) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.add_scan_permission), color = MaterialTheme.colorScheme.error)
            }
            if (state.scanning) {
                Spacer(Modifier.height(16.dp))
                LinearWavyProgressIndicator()
                if (state.results.isEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.add_scan_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            state.results.forEach { device ->
                val known = device.address in state.knownAddresses
                ListItem(
                    supportingContent = {
                        Text(stringResource(R.string.add_device_summary, device.family.label, device.address, device.rssi))
                    },
                    trailingContent = {
                        if (known) {
                            Text(stringResource(R.string.add_already_added), style = MaterialTheme.typography.labelLarge)
                        } else {
                            TextButton(onClick = { viewModel.add(device) }) { Text(stringResource(R.string.action_add)) }
                        }
                    },
                ) { Text(device.name) }
            }
        }
    }
}
