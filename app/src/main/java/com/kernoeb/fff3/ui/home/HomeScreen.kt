package com.kernoeb.fff3.ui.home

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernoeb.fff3.R
import com.kernoeb.fff3.ble.BluetoothStatus
import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.data.KnownDevice
import com.kernoeb.fff3.ui.appViewModel
import com.kernoeb.fff3.ui.common.labelRes
import com.kernoeb.fff3.ui.theme.toColor

/** Placeholder home: strips and groups with connection state and a power switch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenControl: (ControlTarget) -> Unit,
    onAddDevice: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel = appViewModel {
        HomeViewModel(it.repository, it.controller, it.deviceStates, it.bluetooth, it.pairing)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<KnownDevice?>(null) }
    var removing by remember { mutableStateOf<KnownDevice?>(null) }
    var creatingGroup by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.action_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddDevice,
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text(stringResource(R.string.home_add_strip)) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 88.dp,
            ),
        ) {
            if (state.bluetooth != BluetoothStatus.Ready) {
                item { BluetoothBanner(state.bluetooth, onChanged = viewModel::onBluetoothSettingsChanged) }
            }
            if (state.loaded && state.devices.isEmpty()) {
                item { EmptyState() }
            }
            if (state.devices.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.home_strips)) }
                items(state.devices, key = { it.device.address }) { item ->
                    DeviceRow(
                        item = item,
                        onOpen = { onOpenControl(ControlTarget.Device(item.device.address)) },
                        onPower = { on -> viewModel.setPower(ControlTarget.Device(item.device.address), on) },
                        onRename = { renaming = item.device },
                        onRemove = { removing = item.device },
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionHeader(stringResource(R.string.home_groups), Modifier.weight(1f))
                        TextButton(onClick = { creatingGroup = true }, modifier = Modifier.padding(end = 8.dp)) {
                            Icon(painterResource(R.drawable.ic_group_add), contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.home_new_group))
                        }
                    }
                }
                items(state.groups, key = { it.id }) { group ->
                    GroupRow(
                        group = group,
                        onOpen = { onOpenControl(ControlTarget.Group(group.id)) },
                        onPower = { on -> viewModel.setPower(ControlTarget.Group(group.id), on) },
                        onDelete = { viewModel.deleteGroup(group.id) },
                    )
                }
            }
        }
    }

    renaming?.let { device ->
        RenameDialog(
            initial = device.displayName,
            onDismiss = { renaming = null },
            onSave = { name ->
                viewModel.rename(device.address, name)
                renaming = null
            },
        )
    }
    removing?.let { device ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.home_remove_title, device.displayName)) },
            text = { Text(stringResource(R.string.home_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(device)
                    removing = null
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (creatingGroup) {
        NewGroupDialog(
            devices = state.devices.map { it.device },
            onDismiss = { creatingGroup = false },
            onCreate = { name, members ->
                viewModel.createGroup(name, members)
                creatingGroup = false
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun DeviceRow(
    item: DeviceItem,
    onOpen: () -> Unit,
    onPower: (Boolean) -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = { ColourDot(item.state.color?.toColor()) },
        supportingContent = {
            Text("${item.device.family.label} · ${stringResource(item.connection.labelRes())}")
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = item.state.power == true, onCheckedChange = onPower)
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_rename)) },
                            onClick = {
                                menuOpen = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_remove)) },
                            onClick = {
                                menuOpen = false
                                onRemove()
                            },
                        )
                    }
                }
            }
        },
    ) { Text(item.device.displayName) }
}

@Composable
private fun GroupRow(group: GroupItem, onOpen: () -> Unit, onPower: (Boolean) -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        supportingContent = {
            Text(stringResource(R.string.home_group_summary, group.memberCount, group.connectedCount))
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = group.allOn, onCheckedChange = onPower)
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete)) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        },
    ) { Text(group.name) }
}

@Composable
private fun ColourDot(color: Color?) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier
            .size(28.dp)
            .background(color ?: MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            .border(1.dp, outline, CircleShape),
    )
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp)) {
        Icon(
            painterResource(R.drawable.ic_lightbulb),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BluetoothBanner(status: BluetoothStatus, onChanged: () -> Unit) {
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        onChanged()
    }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onChanged() }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            val body = when (status) {
                BluetoothStatus.PermissionMissing -> R.string.home_permission_body
                BluetoothStatus.Off -> R.string.home_bluetooth_off_body
                else -> R.string.home_unsupported_body
            }
            Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
            when (status) {
                BluetoothStatus.PermissionMissing -> {
                    Spacer(Modifier.height(12.dp))
                    if (denied) {
                        FilledTonalButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.fromParts("package", context.packageName, null)),
                            )
                        }) { Text(stringResource(R.string.home_permission_settings)) }
                    } else {
                        FilledTonalButton(onClick = { permission.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                            Text(stringResource(R.string.home_permission_allow))
                        }
                    }
                }
                BluetoothStatus.Off -> {
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) {
                        Text(stringResource(R.string.home_bluetooth_on))
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.home_rename_label)) },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun NewGroupDialog(
    devices: List<KnownDevice>,
    onDismiss: () -> Unit,
    onCreate: (String, List<String>) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>().apply { addAll(devices.map { it.address }) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_group_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.home_group_name)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                devices.forEach { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (device.address in selected) selected.remove(device.address) else selected.add(device.address)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = device.address in selected, onCheckedChange = null)
                        Spacer(Modifier.size(8.dp))
                        Text(device.displayName)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && selected.isNotEmpty(),
                onClick = { onCreate(name, selected.toList()) },
            ) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
