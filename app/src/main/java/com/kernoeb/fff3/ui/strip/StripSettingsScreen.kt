package com.kernoeb.fff3.ui.strip

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernoeb.fff3.R
import com.kernoeb.fff3.protocol.ChannelOrder
import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.protocol.ScheduleAction
import com.kernoeb.fff3.ui.appViewModel
import com.kernoeb.fff3.ui.theme.Fff3Theme
import com.kernoeb.fff3.ui.theme.toColor
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Settings stored on one strip: name, clock, schedules, wire order. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StripSettingsScreen(address: String, onBack: () -> Unit, onForgotten: () -> Unit) {
    val viewModel = appViewModel(key = "strip-settings/$address") {
        StripSettingsViewModel(address, it.repository, it.controller, it.stripSettings, it.clockSync, it.deviceStates, it.pairing)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val notReached = stringResource(R.string.strip_not_reached)
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(notReached) }
    }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var forgetting by rememberSaveable { mutableStateOf(false) }
    val device = state.device

    Fff3Theme(seed = state.color?.toColor()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(device?.displayName ?: stringResource(R.string.strip_settings_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (device == null) return@Column
                SectionCard {
                    ListItem(
                        modifier = Modifier.clickable { renaming = true },
                        colors = transparentItem(),
                        supportingContent = { Text(device.displayName) },
                    ) { Text(stringResource(R.string.action_rename)) }
                    ListItem(
                        colors = transparentItem(),
                        supportingContent = { Text(clockText(state.clockSyncedAt)) },
                        trailingContent = {
                            TextButton(onClick = viewModel::syncClock) { Text(stringResource(R.string.strip_clock_sync_now)) }
                        },
                    ) { Text(stringResource(R.string.strip_clock_title)) }
                }

                if (device.family.supports(Command.Schedule(ScheduleAction.TurnOn, 0, 0, emptySet()))) {
                    SectionTitle(stringResource(R.string.strip_schedules_title))
                    Text(
                        stringResource(R.string.strip_schedules_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (action in ScheduleAction.entries) {
                        ScheduleCard(
                            schedule = state.settings.schedule(action) ?: StripSettingsViewModel.defaultSchedule(action),
                            onChange = viewModel::saveSchedule,
                        )
                    }
                }

                if (device.family.hasWireOrder) {
                    SectionTitle(stringResource(R.string.strip_wire_order_title))
                    WireOrderCard(
                        selected = state.settings.wireOrder,
                        testing = state.testing,
                        onSelect = viewModel::setWireOrder,
                        onTest = viewModel::testWireOrder,
                    )
                }

                OutlinedButton(
                    onClick = { forgetting = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.strip_forget), color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.padding(8.dp))
            }
        }

        if (renaming && device != null) {
            RenameDialog(
                initial = device.label ?: device.displayName,
                onDismiss = { renaming = false },
                onSave = { viewModel.rename(it); renaming = false },
            )
        }
        if (forgetting && device != null) {
            AlertDialog(
                onDismissRequest = { forgetting = false },
                title = { Text(stringResource(R.string.strip_forget_title, device.displayName)) },
                text = { Text(stringResource(R.string.strip_forget_body)) },
                confirmButton = {
                    TextButton(onClick = { forgetting = false; viewModel.forget(onForgotten) }) {
                        Text(stringResource(R.string.strip_forget))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { forgetting = false }) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }
    }
}

@Composable
private fun clockText(syncedAt: Long?): String {
    if (syncedAt == null) return stringResource(R.string.strip_clock_not_synced)
    val time = java.time.Instant.ofEpochMilli(syncedAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    return stringResource(R.string.strip_clock_synced, time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) { Column(Modifier.padding(vertical = 8.dp)) { content() } }
}

@Composable
private fun transparentItem() = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScheduleCard(schedule: Command.Schedule, onChange: (Command.Schedule) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val time = LocalTime.of(schedule.hour, schedule.minute)
    SectionCard {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        if (schedule.action == ScheduleAction.TurnOn) R.string.strip_schedule_on else R.string.strip_schedule_off,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)),
                    style = MaterialTheme.typography.displaySmall,
                    color = if (schedule.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { picking = true },
                )
            }
            Switch(
                checked = schedule.enabled,
                onCheckedChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    onChange(schedule.copy(enabled = it))
                },
            )
        }
        FlowRow(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (day in DayOfWeek.entries) {
                FilterChip(
                    selected = day in schedule.days,
                    onClick = {
                        val days = if (day in schedule.days) schedule.days - day else schedule.days + day
                        onChange(schedule.copy(days = days))
                    },
                    label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                )
            }
        }
    }
    if (picking) {
        val is24h = DateFormat.is24HourFormat(LocalContext.current)
        val pickerState = rememberTimePickerState(schedule.hour, schedule.minute, is24h)
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    onChange(schedule.copy(hour = pickerState.hour, minute = pickerState.minute, enabled = true))
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_cancel)) } },
            text = { TimePicker(state = pickerState) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WireOrderCard(
    selected: ChannelOrder?,
    testing: com.kernoeb.fff3.protocol.Rgb?,
    onSelect: (ChannelOrder) -> Unit,
    onTest: (ChannelOrder) -> Unit,
) {
    val current = selected ?: ChannelOrder.RGB
    SectionCard {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.strip_wire_order_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (order in ChannelOrder.entries) {
                    FilterChip(selected = order == current, onClick = { onSelect(order) }, label = { Text(order.name) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (testing == null) {
                    FilledTonalButton(onClick = { onTest(current) }) { Text(stringResource(R.string.strip_wire_order_test)) }
                } else {
                    Box(Modifier.size(32.dp).background(testing.toColor(), CircleShape))
                    Text(stringResource(R.string.strip_wire_order_showing, stringResource(colourName(testing))))
                }
            }
        }
    }
}

private fun colourName(rgb: com.kernoeb.fff3.protocol.Rgb): Int = when (rgb) {
    StripSettingsViewModel.TEST_COLOURS[0] -> R.string.colour_red
    StripSettingsViewModel.TEST_COLOURS[1] -> R.string.colour_green
    else -> R.string.colour_blue
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_rename)) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { Button(onClick = { onSave(name) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
