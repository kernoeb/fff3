package com.kernoeb.fff3.ui.control

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kernoeb.fff3.R

/** Stands in for the phone music screen until it exists. Replace its entry in `AppNavigation`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlaceholderScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.music_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Text(
            stringResource(R.string.music_placeholder),
            modifier = Modifier.padding(padding).padding(24.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
