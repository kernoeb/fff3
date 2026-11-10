package com.kernoeb.fff3

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kernoeb.fff3.ble.LedSession
import com.kernoeb.fff3.ui.Fff3App
import com.kernoeb.fff3.ui.navigation.Route

class MainActivity : ComponentActivity() {

    private var session: LedSession? = null
    private var link by mutableStateOf<Route?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) link = routeOf(intent)
        setContent {
            Fff3App(link = link, onLinkHandled = { link = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        link = routeOf(intent)
    }

    override fun onStart() {
        super.onStart()
        appContainer.bluetooth.refresh()
        session = appContainer.controller.openSession()
    }

    override fun onStop() {
        session?.close()
        session = null
        // A swipe from recents can kill the process before the next timed save.
        appContainer.deviceStates.flush()
        super.onStop()
    }

    private fun routeOf(intent: Intent?): Route? = Route.decode(intent?.getStringExtra(EXTRA_ROUTE))

    companion object {
        /** A [Route.encode] string. Shortcuts, the tile and the widget use it to open a screen. */
        const val EXTRA_ROUTE = "com.kernoeb.fff3.extra.ROUTE"

        fun intentFor(context: Context, route: Route): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_ROUTE, route.encode())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
