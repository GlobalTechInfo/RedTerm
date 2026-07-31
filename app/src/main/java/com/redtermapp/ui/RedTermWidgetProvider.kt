package com.redtermapp.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller

class RedTermWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)
        val installer = DistroInstaller(context)
        val distros = installer.getInstalledDistros()

        if (distros.isEmpty()) {
            views.setTextViewText(R.id.widget_distro_name, "No distros installed")
            views.setTextViewText(R.id.widget_distro_status, "Open RedTerm to install")
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                context, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
        } else {
            val distro = distros[0]
            views.setTextViewText(R.id.widget_distro_name, distro.replaceFirstChar { it.uppercase() })
            val rootfsDir = installer.getRootfsDir(distro)
            val sizeBytes = rootfsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val sizeStr = when {
                sizeBytes < 1_000_000 -> "${sizeBytes / 1000} KB"
                sizeBytes < 1_000_000_000 -> "${"%.1f".format(sizeBytes / 1_000_000.0)} MB"
                else -> "${"%.2f".format(sizeBytes / 1_000_000_000.0)} GB"
            }
            views.setTextViewText(R.id.widget_distro_status, sizeStr)
            val launchIntent = Intent(context, TerminalActivity::class.java).apply {
                putExtra(TerminalActivity.EXTRA_DISTRO, distro)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                context, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
        }
        appWidgetManager.updateAppWidget(widgetId, views)
    }
}
