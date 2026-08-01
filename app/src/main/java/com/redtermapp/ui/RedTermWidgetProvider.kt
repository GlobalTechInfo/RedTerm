package com.redtermapp.ui

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller

class RedTermWidgetProvider : AppWidgetProvider() {

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                android.content.ComponentName(context, RedTermWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            val provider = RedTermWidgetProvider()
            for (widgetId in ids) {
                provider.updateWidget(context, manager, widgetId)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)
        val installer = DistroInstaller(context)
        val distros = installer.getInstalledDistros()

        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val chosen = prefs.getString("widget_distro_$widgetId", null)
        val distro = when {
            distros.isEmpty() -> null
            chosen != null && distros.contains(chosen) -> chosen
            else -> distros.first()
        }

        if (distro == null) {
            views.setTextViewText(R.id.widget_distro_name, "No distros installed")
            views.setTextViewText(R.id.widget_distro_status, "Open RedTerm to install")
            views.setTextViewText(R.id.widget_session_count, "")
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                context, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
        } else {
            views.setTextViewText(R.id.widget_distro_name, distro.replaceFirstChar { it.uppercase() })
            val rootfsDir = installer.getRootfsDir(distro)
            val sizeBytes = rootfsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val sizeStr = when {
                sizeBytes < 1_000_000 -> "${sizeBytes / 1000} KB"
                sizeBytes < 1_000_000_000 -> "${"%.1f".format(sizeBytes / 1_000_000.0)} MB"
                else -> "${"%.2f".format(sizeBytes / 1_000_000_000.0)} GB"
            }
            val sessionCount = TerminalViewModel.get(
                context.applicationContext as android.app.Application
            ).sessions.value.size
            views.setTextViewText(R.id.widget_distro_status, "$sizeStr · $sessionCount session(s)")
            views.setTextViewText(
                R.id.widget_session_count,
                if (sessionCount > 0) "Tap to resume" else "Tap to launch"
            )
            val launchIntent = Intent(context, TerminalActivity::class.java).apply {
                putExtra(TerminalActivity.EXTRA_DISTRO, distro)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                context, widgetId, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
        }
        appWidgetManager.updateAppWidget(widgetId, views)
    }
}
