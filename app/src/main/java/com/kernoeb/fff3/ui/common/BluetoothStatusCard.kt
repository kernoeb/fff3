package com.kernoeb.fff3.ui.common

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kernoeb.fff3.R
import com.kernoeb.fff3.ble.BluetoothStatus

/**
 * Explains why the strips cannot be reached and offers the one action that fixes it.
 * Shows nothing when Bluetooth is ready. [onChanged] runs after the user returns from a system prompt.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BluetoothStatusCard(status: BluetoothStatus, onChanged: () -> Unit, modifier: Modifier = Modifier) {
    if (status == BluetoothStatus.Ready) return
    val context = LocalContext.current
    val activity = LocalActivity.current
    // After a refusal Android may stop showing the prompt, so we send the user to the app settings instead.
    var blocked by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        blocked = !granted && activity?.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT) == false
        onChanged()
    }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onChanged() }

    val (title, body) = when (status) {
        BluetoothStatus.PermissionMissing -> R.string.status_permission_title to R.string.home_permission_body
        BluetoothStatus.Off -> R.string.status_bluetooth_off_title to R.string.home_bluetooth_off_body
        else -> R.string.status_unsupported_title to R.string.home_unsupported_body
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.secondary, MaterialShapes.Cookie9Sided.toShape()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_bluetooth_searching),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondary,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(if (blocked) R.string.status_permission_blocked_body else body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (status == BluetoothStatus.PermissionMissing || status == BluetoothStatus.Off) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            status == BluetoothStatus.Off -> Button(onClick = {
                                enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                            }) { Text(stringResource(R.string.home_bluetooth_on)) }
                            blocked -> Button(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(Uri.fromParts("package", context.packageName, null)),
                                )
                            }) { Text(stringResource(R.string.home_permission_settings)) }
                            else -> Button(onClick = { permission.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                                Text(stringResource(R.string.home_permission_allow))
                            }
                        }
                        if (blocked) {
                            TextButton(onClick = { permission.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                                Text(stringResource(R.string.status_try_again))
                            }
                        }
                    }
                }
            }
        }
    }
}
