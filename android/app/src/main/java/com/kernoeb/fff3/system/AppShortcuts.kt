package com.kernoeb.fff3.system

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.PersistableBundle
import com.kernoeb.fff3.R
import com.kernoeb.fff3.data.Favourite

/** Dynamic launcher shortcuts for the first favourites. The static "All on" and "All off" are in `res/xml/shortcuts.xml`. */
internal object AppShortcuts {

    private const val STATIC_COUNT = 2
    private const val MAX_FAVOURITES = 3
    private const val ID_PREFIX = "favourite:"
    private const val KEY_COLOR = "color"
    private const val ICON_PX = 216

    fun update(context: Context, favourites: List<Favourite>) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val count = (manager.maxShortcutCountPerActivity - STATIC_COUNT).coerceIn(0, MAX_FAVOURITES)
        val wanted = favourites.take(count)

        // Publishing is rate limited in the background, so skip it when nothing changed.
        val current = manager.dynamicShortcuts.map { Triple(it.id, it.shortLabel?.toString(), it.extras?.getInt(KEY_COLOR, -1)) }
        val next = wanted.map { Triple(ID_PREFIX + it.id, it.name, it.color?.packed ?: -1) }
        if (current != next) manager.dynamicShortcuts = wanted.mapIndexed { rank, favourite -> build(context, favourite, rank) }

        val ids = favourites.map { ID_PREFIX + it.id }.toSet()
        val gone = manager.pinnedShortcuts.filter { it.id.startsWith(ID_PREFIX) && it.id !in ids && it.isEnabled }.map { it.id }
        if (gone.isNotEmpty()) manager.disableShortcuts(gone, context.getString(R.string.shortcut_favourite_gone))
    }

    private fun build(context: Context, favourite: Favourite, rank: Int): ShortcutInfo {
        val id = ID_PREFIX + favourite.id
        val color = favourite.color
        return ShortcutInfo.Builder(context, id)
            .setShortLabel(favourite.name)
            .setLongLabel(favourite.name)
            .setRank(rank)
            .setIcon(color?.let { iconFor(context, it.packed) } ?: Icon.createWithResource(context, R.drawable.shortcut_favourite))
            .setIntent(ShortcutActivity.favouriteIntent(context, favourite.id, id))
            .setExtras(PersistableBundle().apply { putInt(KEY_COLOR, color?.packed ?: -1) })
            .build()
    }

    /** The favourite's colour as the icon background, with a bulb in a colour that stays readable on it. */
    private fun iconFor(context: Context, rgb: Int): Icon {
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF000000.toInt() or rgb)
        val bulb = context.getDrawable(R.drawable.ic_lightbulb)!!.mutate()
        // Adaptive icons show the middle 2/3; the bulb takes about 40 % of the full size.
        val inset = ICON_PX * 3 / 10
        bulb.setBounds(inset, inset, ICON_PX - inset, ICON_PX - inset)
        bulb.setTint(if (luminance(rgb) > 0.55f) 0xDD000000.toInt() else 0xFFFFFFFF.toInt())
        bulb.draw(canvas)
        return Icon.createWithAdaptiveBitmap(bitmap)
    }
}

/** Rough perceived lightness of a `0xRRGGBB` colour, 0 to 1. */
internal fun luminance(rgb: Int): Float {
    val r = (rgb shr 16) and 0xFF
    val g = (rgb shr 8) and 0xFF
    val b = rgb and 0xFF
    return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
}
