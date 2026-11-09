package com.kernoeb.fff3.system

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.kernoeb.fff3.MainActivity
import com.kernoeb.fff3.appContainer
import com.kernoeb.fff3.ui.navigation.Route
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A long press on the tile lands here. It opens the app on the Control screen of the tile's target. */
class TileSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val data = withTimeoutOrNull(DATA_WAIT_MS) { appContainer.repository.snapshot.filterNotNull().first() }
            val route = data?.let { SystemTarget.resolve(it, QuickSettingsTile.target(this@TileSettingsActivity)).route }
            startActivity(MainActivity.intentFor(this@TileSettingsActivity, route ?: Route.Home))
            finish()
        }
    }

    private companion object {
        const val DATA_WAIT_MS = 1_000L
    }
}
