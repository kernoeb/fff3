package com.kernoeb.fff3.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kernoeb.fff3.R
import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.data.Favourite
import com.kernoeb.fff3.ui.theme.toColor

private val Rainbow = listOf(
    Color(0xFFFF3B30), Color(0xFFFFCC00), Color(0xFF34C759), Color(0xFF00C7FF),
    Color(0xFF5856D6), Color(0xFFFF2D92), Color(0xFFFF3B30),
)

/** A colour dot for plain colours, a rainbow for effects and scenes. */
@Composable
fun FavouriteSwatch(favourite: Favourite, size: Int = 52) {
    val colour = favourite.previewColour()
    val shape = CircleShape
    val modifier = Modifier
        .size(size.dp)
        .clip(shape)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
    if (colour != null) {
        Box(modifier.background(colour.toColor()))
    } else {
        Box(modifier.background(Brush.sweepGradient(Rainbow)))
    }
}

/** Favourites in a row. A tap applies one to the chosen strip or group. */
@Composable
fun FavouritesSection(
    state: HomeUiState,
    target: HomeTarget,
    onTargetChange: (HomeTarget) -> Unit,
    onApply: (Favourite) -> Unit,
    onRename: (Favourite) -> Unit,
    onDelete: (Favourite) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionHeader(stringResource(R.string.home_favourites)) {
            TextButton(onClick = onEdit) { Text(stringResource(R.string.home_favourites_edit)) }
        }
        TargetPicker(state, target, onTargetChange, Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.favourites, key = { it.id }) { favourite ->
                FavouriteTile(
                    favourite = favourite,
                    onApply = { onApply(favourite) },
                    onRename = { onRename(favourite) },
                    onDelete = { onDelete(favourite) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavouriteTile(
    favourite: Favourite,
    onApply: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            modifier = Modifier
                .width(88.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .combinedClickable(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onApply()
                    },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                )
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FavouriteSwatch(favourite)
            Spacer(Modifier.height(8.dp))
            Text(
                favourite.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, shape = MaterialTheme.shapes.large) {
            DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = {
                menuOpen = false
                onRename()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = {
                menuOpen = false
                onDelete()
            })
        }
    }
}

/** "Apply to: Desk" chip with a menu of every strip and group. */
@Composable
private fun TargetPicker(
    state: HomeUiState,
    target: HomeTarget,
    onTargetChange: (HomeTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val allLabel = stringResource(R.string.home_all_strips)
    val options = buildList {
        if (state.devices.size >= 2) add(HomeTarget.AllStrips to allLabel)
        state.groups.forEach { add(HomeTarget.One(ControlTarget.Group(it.group.id)) to it.group.name) }
        state.devices.forEach { add(HomeTarget.One(ControlTarget.Device(it.device.address)) to it.device.displayName) }
    }
    val current = state.nameOf(target) ?: allLabel
    Box(modifier) {
        FilterChip(
            selected = true,
            onClick = { open = true },
            label = { Text(stringResource(R.string.home_apply_to, current)) },
            trailingIcon = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null) },
            shape = CircleShape,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.large) {
            options.forEach { (option, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = if (option == target) {
                        { Icon(painterResource(R.drawable.ic_check), contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onTargetChange(option)
                    },
                )
            }
        }
    }
}

/** Reorder, rename and delete favourites. Drag a row by its handle to move it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageFavouritesSheet(
    favourites: List<Favourite>,
    onDismiss: () -> Unit,
    onReorder: (List<String>) -> Unit,
    onRename: (Favourite) -> Unit,
    onDelete: (Favourite) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var order by remember { mutableStateOf(favourites) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(1) }
    LaunchedEffect(favourites) {
        if (draggingId == null) order = favourites
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                stringResource(R.string.home_favourites_manage),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (order.isEmpty()) {
                Text(
                    stringResource(R.string.home_favourites_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
            order.forEach { favourite ->
                val dragging = favourite.id == draggingId
                Surface(
                    color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                    shape = RoundedCornerShape(20.dp),
                    shadowElevation = if (dragging) 6.dp else 0.dp,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                        .onSizeChanged { rowHeight = it.height.coerceAtLeast(1) },
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FavouriteSwatch(favourite, size = 36)
                        Spacer(Modifier.width(16.dp))
                        Text(
                            favourite.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { onRename(favourite) }) { Text(stringResource(R.string.action_rename)) }
                        TextButton(onClick = { onDelete(favourite) }) { Text(stringResource(R.string.action_delete)) }
                        IconButton(
                            onClick = {},
                            modifier = Modifier.pointerInput(favourite.id) {
                                detectDragGestures(
                                    onDragStart = {
                                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                        draggingId = favourite.id
                                        dragOffset = 0f
                                    },
                                    onDragEnd = {
                                        draggingId = null
                                        dragOffset = 0f
                                        onReorder(order.map { it.id })
                                    },
                                    onDragCancel = {
                                        draggingId = null
                                        dragOffset = 0f
                                        order = favourites
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        val index = order.indexOfFirst { it.id == favourite.id }
                                        // Swap with a neighbour once the row has moved past half of it.
                                        if (dragOffset > rowHeight / 2f && index < order.lastIndex) {
                                            order = order.moved(index, index + 1)
                                            dragOffset -= rowHeight
                                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        } else if (dragOffset < -rowHeight / 2f && index > 0) {
                                            order = order.moved(index, index - 1)
                                            dragOffset += rowHeight
                                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        }
                                    },
                                )
                            },
                        ) {
                            Icon(painterResource(R.drawable.ic_drag_indicator), stringResource(R.string.home_favourites_move))
                        }
                    }
                }
            }
        }
    }
}
