package com.kernoeb.fff3.system

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.kernoeb.fff3.MainActivity
import com.kernoeb.fff3.R
import com.kernoeb.fff3.appContainer
import com.kernoeb.fff3.data.AppData
import com.kernoeb.fff3.data.LedState
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Home-screen widget for every strip: power, current colour and the first favourites. */
class LedWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        val container = context.appContainer
        container.appScope.launch {
            try {
                runCatching {
                    val data = container.repository.snapshot.filterNotNull().first()
                    LedWidgets.push(context, LedWidgets.model(data, container.deviceStates.states.value))
                }
            } finally {
                pending.finish()
            }
        }
    }
}

internal object LedWidgets {

    private const val MAX_FAVOURITES = 4
    private val favouriteViews = listOf(
        Triple(R.id.widget_favourite_0, R.id.widget_favourite_dot_0, R.id.widget_favourite_name_0),
        Triple(R.id.widget_favourite_1, R.id.widget_favourite_dot_1, R.id.widget_favourite_name_1),
        Triple(R.id.widget_favourite_2, R.id.widget_favourite_dot_2, R.id.widget_favourite_name_2),
        Triple(R.id.widget_favourite_3, R.id.widget_favourite_dot_3, R.id.widget_favourite_name_3),
    )

    data class FavouriteModel(val id: String, val name: String, val color: Int?)

    data class Model(val target: SystemTarget, val on: Boolean, val color: Int?, val favourites: List<FavouriteModel>)

    fun model(data: AppData, states: Map<String, LedState>): Model {
        val target = SystemTarget.resolve(data, chosen = null)
        return Model(
            target = target,
            on = target.isOn(states),
            color = target.color(states)?.packed,
            favourites = data.favourites.take(MAX_FAVOURITES).map { FavouriteModel(it.id, it.name, it.color?.packed) },
        )
    }

    fun push(context: Context, model: Model) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, LedWidgetProvider::class.java))
        if (ids.isEmpty()) return
        // The launcher picks the largest layout that fits, so resizing needs no update from us.
        val views = RemoteViews(
            mapOf(
                SizeF(40f, 40f) to render(context, model, showText = false, showFavourites = false),
                SizeF(180f, 40f) to render(context, model, showText = true, showFavourites = false),
                SizeF(180f, 100f) to render(context, model, showText = true, showFavourites = true),
            ),
        )
        manager.updateAppWidget(ids, views)
    }

    private fun render(context: Context, model: Model, showText: Boolean, showFavourites: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_led)
        val ready = !model.target.isEmpty

        val power = if (model.on) SystemActionReceiver.POWER_OFF else SystemActionReceiver.POWER_ON
        views.setOnClickPendingIntent(
            R.id.widget_power,
            if (ready) broadcast(context, 0, SystemActionReceiver.powerIntent(context, power)) else openApp(context, model),
        )
        if (model.on) {
            val fill = model.color?.let { 0xFF000000.toInt() or it }
            if (fill != null) {
                views.setInt(R.id.widget_power_fill, "setColorFilter", fill)
                views.setInt(R.id.widget_glow, "setColorFilter", fill)
                views.setInt(R.id.widget_power_icon, "setColorFilter", if (luminance(fill) > 0.55f) 0xDD000000.toInt() else -1)
            } else {
                views.setColor(R.id.widget_power_fill, "setColorFilter", R.color.widget_accent)
                views.setColor(R.id.widget_glow, "setColorFilter", R.color.widget_accent)
                views.setColor(R.id.widget_power_icon, "setColorFilter", R.color.widget_on_accent)
            }
            views.setViewVisibility(R.id.widget_glow, View.VISIBLE)
        } else {
            views.setColor(R.id.widget_power_fill, "setColorFilter", R.color.widget_off)
            views.setColor(R.id.widget_power_icon, "setColorFilter", R.color.widget_on_surface_variant)
            views.setViewVisibility(R.id.widget_glow, View.GONE)
        }
        views.setContentDescription(
            R.id.widget_power,
            context.getString(if (model.on) R.string.widget_turn_off else R.string.widget_turn_on),
        )

        views.setViewVisibility(R.id.widget_text, if (showText) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.widget_title, model.target.name ?: context.getString(R.string.tile_all_strips))
        views.setTextViewText(
            R.id.widget_status,
            context.getString(
                when {
                    !ready -> R.string.tile_no_strips
                    model.on -> R.string.control_on
                    else -> R.string.control_off
                },
            ),
        )
        views.setOnClickPendingIntent(R.id.widget_text, openApp(context, model))

        val favourites = if (showFavourites && ready) model.favourites else emptyList()
        views.setViewVisibility(R.id.widget_favourites, if (favourites.isEmpty()) View.GONE else View.VISIBLE)
        favouriteViews.forEachIndexed { index, (chip, dot, name) ->
            val favourite = favourites.getOrNull(index)
            if (favourite == null) {
                views.setViewVisibility(chip, View.GONE)
                return@forEachIndexed
            }
            views.setViewVisibility(chip, View.VISIBLE)
            views.setTextViewText(name, favourite.name)
            if (favourite.color != null) {
                views.setViewVisibility(dot, View.VISIBLE)
                views.setInt(dot, "setColorFilter", 0xFF000000.toInt() or favourite.color)
            } else {
                views.setViewVisibility(dot, View.GONE)
            }
            views.setOnClickPendingIntent(
                chip,
                broadcast(context, 1 + index, SystemActionReceiver.favouriteIntent(context, favourite.id)),
            )
        }
        return views
    }

    private fun broadcast(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            // A distinct data URI keeps each button's PendingIntent separate.
            intent.setData(Uri.parse("fff3://widget/$requestCode")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun openApp(context: Context, model: Model): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            MainActivity.intentFor(context, model.target.route),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
