package com.kernoeb.fff3.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Bundle

/**
 * Shortcuts must start an activity. This one shows nothing: it hands the command to
 * [SystemActionReceiver] and closes at once.
 */
class ShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent
        if (source != null && savedInstanceState == null) {
            val forward = Intent(this, SystemActionReceiver::class.java).setAction(source.action)
            source.extras?.let(forward::putExtras)
            SystemActionReceiver.sendNow(this, forward)
            source.getStringExtra(EXTRA_SHORTCUT_ID)?.let {
                getSystemService(ShortcutManager::class.java)?.reportShortcutUsed(it)
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_SHORTCUT_ID = "com.kernoeb.fff3.extra.SHORTCUT_ID"

        fun favouriteIntent(context: Context, favouriteId: String, shortcutId: String): Intent =
            Intent(context, ShortcutActivity::class.java)
                .setAction(SystemActionReceiver.ACTION_FAVOURITE)
                .putExtra(SystemActionReceiver.EXTRA_FAVOURITE_ID, favouriteId)
                .putExtra(EXTRA_SHORTCUT_ID, shortcutId)
    }
}
