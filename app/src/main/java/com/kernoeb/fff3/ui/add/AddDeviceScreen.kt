package com.kernoeb.fff3.ui.add

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernoeb.fff3.R
import com.kernoeb.fff3.ble.BluetoothStatus
import com.kernoeb.fff3.ble.ScannedDevice
import com.kernoeb.fff3.data.KnownDevice
import com.kernoeb.fff3.ui.appViewModel
import com.kernoeb.fff3.ui.common.BluetoothStatusCard

/**
 * Adds strips: the system pairing sheet first, a plain scan as fallback.
 * After each strip you name it, then add another or go back; the screen stays open in between.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AddDeviceScreen(onDone: () -> Unit, onBack: () -> Unit) {
    val viewModel = appViewModel {
        AddDeviceViewModel(it.repository, it.controller, it.pairing, it.scanner, it.bluetooth)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val pairingSheet = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onPairingResult(result.resultCode, result.data)
    }
    val scanPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        viewModel.onScanPermissionResult(grants.values.all { it })
    }
    val startPairing = {
        viewModel.startPairing { sender -> pairingSheet.launch(IntentSenderRequest.Builder(sender).build()) }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.add_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            AnimatedVisibility(visible = state.added.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = startPairing,
                            enabled = state.pairingAvailable,
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) { Text(stringResource(R.string.add_another)) }
                        Button(onClick = onDone, modifier = Modifier.weight(1f).height(56.dp)) {
                            Text(stringResource(R.string.add_done))
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BluetoothStatusCard(state.bluetooth, onChanged = viewModel::onBluetoothSettingsChanged)

            state.message?.let { message ->
                MessageCard(
                    message = message,
                    onDismiss = viewModel::dismissMessage,
                    onOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", context.packageName, null)),
                        )
                    },
                )
            }

            if (state.added.isNotEmpty()) AddedList(state.added, onRename = { viewModel.renameRequest(it) })

            PairCard(
                available = state.pairingAvailable,
                bluetoothReady = state.bluetooth != BluetoothStatus.Off,
                onStart = startPairing,
            )

            ScanSection(
                state = state,
                onScan = {
                    scanPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                },
                onStop = viewModel::stopScan,
                onAdd = viewModel::add,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    state.naming?.let { device ->
        NameStripDialog(
            device = device,
            onIdentify = { viewModel.identify(device.address) },
            onSave = { viewModel.rename(device.address, it) },
            onSkip = viewModel::skipNaming,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PairCard(available: Boolean, bluetoothReady: Boolean, onStart: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp)) {
            Box(
                Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_bluetooth_searching),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.add_pair_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (available) R.string.add_pair_body else R.string.add_pair_unavailable),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                enabled = available && bluetoothReady,
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Text(stringResource(R.string.add_pair_button), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun ScanSection(
    state: AddDeviceUiState,
    onScan: () -> Unit,
    onStop: () -> Unit,
    onAdd: (ScannedDevice) -> Unit,
) {
    Column(Modifier.padding(horizontal = 8.dp)) {
        Text(stringResource(R.string.add_scan_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.add_scan_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (state.scanning) {
            FilledTonalButton(onClick = onStop) { Text(stringResource(R.string.add_scan_stop)) }
            Spacer(Modifier.height(16.dp))
            LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            if (state.results.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.add_scan_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            FilledTonalButton(onClick = onScan, enabled = state.bluetooth != BluetoothStatus.Off) {
                Text(stringResource(R.string.add_scan_button))
            }
        }
        Spacer(Modifier.height(8.dp))
        state.results.forEach { device ->
            val knownAs = state.known[device.address]
            ListItem(
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                headlineContent = { Text(knownAs ?: device.name) },
                supportingContent = {
                    Text(stringResource(R.string.add_scan_summary, device.family.label, device.address, signalLabel(device.rssi)))
                },
                trailingContent = {
                    if (knownAs != null) {
                        Text(
                            stringResource(R.string.add_already_added),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Button(onClick = { onAdd(device) }) { Text(stringResource(R.string.action_add)) }
                    }
                },
            )
        }
    }
}

@Composable
private fun signalLabel(rssi: Int): String = stringResource(
    when {
        rssi >= -60 -> R.string.add_signal_strong
        rssi >= -80 -> R.string.add_signal_medium
        else -> R.string.add_signal_weak
    },
)

@Composable
private fun AddedList(added: List<KnownDevice>, onRename: (KnownDevice) -> Unit) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                stringResource(R.string.add_added_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            added.forEach { device ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        device.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onRename(device) }) { Text(stringResource(R.string.action_rename)) }
                }
            }
        }
    }
}

@Composable
private fun MessageCard(message: AddMessage, onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    val text = when (message) {
        is AddMessage.AlreadyAdded -> stringResource(R.string.add_message_already, message.name)
        AddMessage.ScanPermissionDenied -> stringResource(R.string.add_scan_permission)
        AddMessage.BluetoothOff -> stringResource(R.string.home_bluetooth_off_body)
        is AddMessage.ScanFailed -> stringResource(R.string.add_message_scan_failed)
        is AddMessage.PairingFailed -> stringResource(R.string.add_message_pairing_failed, message.reason)
    }
    val isError = message !is AddMessage.AlreadyAdded
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 12.dp))
            Row(Modifier.align(Alignment.End)) {
                if (message == AddMessage.ScanPermissionDenied) {
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.home_permission_settings)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
            }
        }
    }
}

/** Names a new strip. "Flash it" blinks the strip so you know which one this is. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NameStripDialog(
    device: KnownDevice,
    onIdentify: () -> Unit,
    onSave: (String) -> Unit,
    onSkip: () -> Unit,
) {
    val initial = device.displayName
    var value by remember(device.address) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(device.address) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(stringResource(R.string.add_name_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.add_name_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.home_rename_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSave(value.text.trim()) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NAME_SUGGESTIONS.forEach { name ->
                        SuggestionChip(onClick = { onSave(name) }, label = { Text(name) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onIdentify) {
                    Icon(painterResource(R.drawable.ic_lightbulb), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_identify))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = value.text.isNotBlank(), onClick = { onSave(value.text.trim()) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onSkip) { Text(stringResource(R.string.add_name_skip)) } },
    )
}
