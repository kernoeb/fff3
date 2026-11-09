package com.kernoeb.fff3.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernoeb.fff3.R
import com.kernoeb.fff3.ble.BluetoothStatus
import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.data.DeviceGroup
import com.kernoeb.fff3.data.Favourite
import com.kernoeb.fff3.data.KnownDevice
import com.kernoeb.fff3.ui.appViewModel
import com.kernoeb.fff3.ui.common.BluetoothStatusCard

private sealed interface HomeDialog {
    data class RenameDevice(val device: KnownDevice) : HomeDialog
    data class EditGroup(val group: DeviceGroup?) : HomeDialog
    data class RenameGroup(val group: DeviceGroup) : HomeDialog
    data class DeleteGroup(val group: DeviceGroup) : HomeDialog
    data class RenameFavourite(val favourite: Favourite) : HomeDialog
    data class DeleteFavourite(val favourite: Favourite) : HomeDialog
}

/** Your strips and groups, with power and brightness on each card, and your favourites. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    onOpenControl: (ControlTarget) -> Unit,
    onAddDevice: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStripSettings: (address: String) -> Unit,
) {
    val viewModel = appViewModel {
        HomeViewModel(it.repository, it.controller, it.deviceStates, it.bluetooth, it.pairing, it.stripSettings)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<HomeDialog?>(null) }
    var managingFavourites by rememberSaveable { mutableStateOf(false) }
    var chosenTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val favouriteTarget = HomeTarget.decode(chosenTarget)
        ?.takeIf { it == HomeTarget.AllStrips && state.devices.size >= 2 || state.nameOf(it) != null }
        ?: defaultHomeTarget(state.devices.map { it.device }, state.groups.map { it.group })
        ?: HomeTarget.AllStrips

    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeEvent.Applied -> {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(
                        resources.getQuantityString(R.plurals.home_applied, event.count, event.favourite, event.count),
                        duration = SnackbarDuration.Short,
                    )
                }
                is HomeEvent.NothingToApply -> snackbar.showSnackbar(
                    resources.getString(R.string.home_apply_nothing, event.favourite),
                )
                is HomeEvent.Forgotten -> {
                    var undone = false
                    try {
                        val result = snackbar.showSnackbar(
                            message = resources.getString(R.string.home_forgotten, event.device.displayName),
                            actionLabel = resources.getString(R.string.action_undo),
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            undone = true
                            viewModel.undoForget(event)
                        }
                    } finally {
                        if (!undone) viewModel.confirmForget(event)
                    }
                }
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val hasDevices = state.devices.isNotEmpty()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                subtitle = if (hasDevices) {
                    { Text(homeSubtitle(state)) }
                } else {
                    null
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.action_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            if (hasDevices) {
                ExtendedFloatingActionButton(
                    onClick = onAddDevice,
                    expanded = !listState.canScrollBackward,
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    text = { Text(stringResource(R.string.home_add_strip)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loaded && state.bluetooth != BluetoothStatus.Ready) {
                item(key = "bluetooth") {
                    BluetoothStatusCard(
                        status = state.bluetooth,
                        onChanged = viewModel::onBluetoothSettingsChanged,
                        modifier = Modifier.padding(horizontal = 16.dp).animateItem(),
                    )
                }
            }
            if (state.loaded && !hasDevices) {
                item(key = "empty") { EmptyState(onAddDevice, Modifier.animateItem()) }
            }
            state.all?.let { all ->
                item(key = "all") {
                    AllStripsCard(
                        summary = all,
                        hasGroup = state.allGroupId != null,
                        onOpen = state.allGroupId?.let { id -> { onOpenControl(ControlTarget.Group(id)) } },
                        onMakeGroup = { viewModel.createAllGroup { id -> chosenTarget = HomeTarget.One(ControlTarget.Group(id)).encode() } },
                        onPower = { viewModel.setPower(HomeTarget.AllStrips, it) },
                        onBrightness = { viewModel.setBrightness(HomeTarget.AllStrips, it) },
                        modifier = Modifier.padding(horizontal = 16.dp).animateItem(),
                    )
                }
            }
            if (state.favourites.isNotEmpty()) {
                item(key = "favourites") {
                    FavouritesSection(
                        state = state,
                        target = favouriteTarget,
                        onTargetChange = { chosenTarget = it.encode() },
                        onApply = { viewModel.applyFavourite(it, favouriteTarget) },
                        onRename = { dialog = HomeDialog.RenameFavourite(it) },
                        onDelete = { dialog = HomeDialog.DeleteFavourite(it) },
                        onEdit = { managingFavourites = true },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (hasDevices) {
                item(key = "strips-header") {
                    SectionHeader(stringResource(R.string.home_strips), Modifier.animateItem())
                }
                items(state.devices, key = { "device-" + it.device.address }) { item ->
                    val target = HomeTarget.One(ControlTarget.Device(item.device.address))
                    DeviceCard(
                        item = item,
                        onOpen = { onOpenControl(target.target) },
                        onPower = { viewModel.setPower(target, it) },
                        onBrightness = { viewModel.setBrightness(target, it) },
                        actions = listOf(
                            CardAction(stringResource(R.string.action_rename)) { dialog = HomeDialog.RenameDevice(item.device) },
                            CardAction(stringResource(R.string.strip_settings_title)) { onOpenStripSettings(item.device.address) },
                            CardAction(stringResource(R.string.home_forget)) { viewModel.forget(item.device) },
                        ),
                        modifier = Modifier.padding(horizontal = 16.dp).animateItem(),
                    )
                }
                item(key = "groups-header") {
                    SectionHeader(stringResource(R.string.home_groups), Modifier.animateItem()) {
                        TextButton(onClick = { dialog = HomeDialog.EditGroup(null) }) {
                            Icon(painterResource(R.drawable.ic_group_add), contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.home_new_group))
                        }
                    }
                }
                if (state.groups.isEmpty()) {
                    item(key = "groups-empty") {
                        Text(
                            stringResource(R.string.home_groups_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp).animateItem(),
                        )
                    }
                }
                items(state.groups, key = { "group-" + it.group.id }) { item ->
                    val target = HomeTarget.One(ControlTarget.Group(item.group.id))
                    GroupCard(
                        item = item,
                        onOpen = { onOpenControl(target.target) },
                        onPower = { viewModel.setPower(target, it) },
                        onBrightness = { viewModel.setBrightness(target, it) },
                        actions = listOf(
                            CardAction(stringResource(R.string.action_rename)) { dialog = HomeDialog.RenameGroup(item.group) },
                            CardAction(stringResource(R.string.home_group_edit_members)) { dialog = HomeDialog.EditGroup(item.group) },
                            CardAction(stringResource(R.string.action_delete)) { dialog = HomeDialog.DeleteGroup(item.group) },
                        ),
                        modifier = Modifier.padding(horizontal = 16.dp).animateItem(),
                    )
                }
                if (state.favourites.isEmpty()) {
                    item(key = "favourites-hint") {
                        Text(
                            stringResource(R.string.home_favourites_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp).animateItem(),
                        )
                    }
                }
            }
        }
    }

    HomeDialogs(dialog, state, viewModel, onClose = { dialog = null })

    if (managingFavourites) {
        ManageFavouritesSheet(
            favourites = state.favourites,
            onDismiss = { managingFavourites = false },
            onReorder = viewModel::reorderFavourites,
            onRename = { dialog = HomeDialog.RenameFavourite(it) },
            onDelete = { dialog = HomeDialog.DeleteFavourite(it) },
        )
    }
}

@Composable
private fun HomeDialogs(dialog: HomeDialog?, state: HomeUiState, viewModel: HomeViewModel, onClose: () -> Unit) {
    when (dialog) {
        null -> Unit
        is HomeDialog.RenameDevice -> NameDialog(
            title = stringResource(R.string.home_rename_title),
            initial = dialog.device.displayName,
            onDismiss = onClose,
            onSave = {
                viewModel.rename(dialog.device.address, it)
                onClose()
            },
        )
        is HomeDialog.EditGroup -> GroupEditorDialog(
            title = stringResource(if (dialog.group == null) R.string.home_group_title else R.string.home_group_edit_title),
            initialName = dialog.group?.name.orEmpty(),
            initialMembers = dialog.group?.memberAddresses ?: state.devices.map { it.device.address },
            devices = state.devices.map { it.device },
            onDismiss = onClose,
            onSave = { name, members ->
                viewModel.saveGroup(dialog.group?.id, name, members)
                onClose()
            },
        )
        is HomeDialog.RenameGroup -> NameDialog(
            title = stringResource(R.string.home_group_rename_title),
            initial = dialog.group.name,
            onDismiss = onClose,
            onSave = {
                viewModel.saveGroup(dialog.group.id, it, dialog.group.memberAddresses)
                onClose()
            },
        )
        is HomeDialog.DeleteGroup -> ConfirmDialog(
            title = stringResource(R.string.home_group_delete_title, dialog.group.name),
            body = stringResource(R.string.home_group_delete_body),
            confirm = stringResource(R.string.action_delete),
            onDismiss = onClose,
            onConfirm = {
                viewModel.deleteGroup(dialog.group.id)
                onClose()
            },
        )
        is HomeDialog.RenameFavourite -> NameDialog(
            title = stringResource(R.string.home_favourite_rename_title),
            initial = dialog.favourite.name,
            onDismiss = onClose,
            onSave = {
                viewModel.renameFavourite(dialog.favourite, it)
                onClose()
            },
        )
        is HomeDialog.DeleteFavourite -> ConfirmDialog(
            title = stringResource(R.string.home_favourite_delete_title, dialog.favourite.name),
            body = stringResource(R.string.home_favourite_delete_body),
            confirm = stringResource(R.string.action_delete),
            onDismiss = onClose,
            onConfirm = {
                viewModel.deleteFavourite(dialog.favourite.id)
                onClose()
            },
        )
    }
}

@Composable
private fun homeSubtitle(state: HomeUiState): String {
    val on = state.devices.count { it.state.power == true }
    return pluralStringResource(R.plurals.home_subtitle, state.devices.size, state.devices.size, on)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyState(onAddDevice: () -> Unit, modifier: Modifier = Modifier) {
    val glow = MaterialTheme.colorScheme.primary
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(200.dp)
                .drawBehind {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(glow.copy(alpha = 0.35f), Color.Transparent),
                            center = Offset(size.width / 2, size.height / 2),
                            radius = size.minDimension / 2,
                        ),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(112.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Sunny.toShape()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_lightbulb),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.home_empty_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAddDevice, contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp)) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.home_add_first), style = MaterialTheme.typography.titleMedium)
        }
    }
}
