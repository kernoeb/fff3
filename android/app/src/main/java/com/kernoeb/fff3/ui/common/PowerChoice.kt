package com.kernoeb.fff3.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kernoeb.fff3.R

/**
 * Two connected buttons, On and Off. The strips cannot report their state, so each tap sends its
 * command even when it is already selected. [power] is what fff3 last sent; null selects neither.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PowerChoice(
    power: Boolean?,
    onPower: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val height = if (compact) 40.dp else 56.dp
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        listOf(true, false).forEach { on ->
            ToggleButton(
                checked = power == on,
                onCheckedChange = {
                    haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    onPower(on)
                },
                shapes = if (on) ButtonGroupDefaults.connectedLeadingButtonShapes()
                else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                contentPadding = if (compact) PaddingValues(horizontal = 14.dp) else ToggleButtonDefaults.contentPaddingFor(height),
                modifier = Modifier.height(height).then(if (compact) Modifier else Modifier.weight(1f)),
            ) {
                if (!compact) {
                    Icon(
                        painterResource(R.drawable.ic_power_settings_new),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp).align(Alignment.CenterVertically),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    stringResource(if (on) R.string.control_on else R.string.control_off),
                    style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
