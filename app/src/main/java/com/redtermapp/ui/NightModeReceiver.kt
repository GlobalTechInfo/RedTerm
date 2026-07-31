package com.redtermapp.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import java.util.Calendar

class NightModeReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CHANGED = "com.redtermapp.NIGHT_MODE_CHANGED"
        private const val NIGHT_START_HOUR = 18
        private const val NIGHT_END_HOUR = 6

        fun isNightModeActive(prefs: SharedPreferences): Boolean {
            if (!prefs.getBoolean("auto_night", false)) return false
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
        }

        fun effectiveTheme(prefs: SharedPreferences): String {
            if (isNightModeActive(prefs)) return "amoled"
            return prefs.getString("theme", "amoled") ?: "amoled"
        }

        fun scheduleNightMode(context: Context, prefs: SharedPreferences) {
            if (!prefs.getBoolean("auto_night", false)) {
                cancelNightMode(context)
                return
            }
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, NightModeReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val now = Calendar.getInstance()
            val next = Calendar.getInstance().apply {
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val currentlyNight = isNightModeActive(prefs)
            val targetHour = if (currentlyNight) NIGHT_END_HOUR else NIGHT_START_HOUR
            next.set(Calendar.HOUR_OF_DAY, targetHour)
            next.set(Calendar.MINUTE, 0)
            if (!next.after(now)) next.add(Calendar.DAY_OF_YEAR, 1)
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pi)
        }

        fun cancelNightMode(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, NightModeReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarm.cancel(pi)
        }

        fun notifyChanged(context: Context, prefs: SharedPreferences) {
            val nowNight = isNightModeActive(prefs)
            prefs.edit().putBoolean("night_flag", nowNight).apply()
            val i = Intent(ACTION_CHANGED).apply {
                putExtra("is_night", nowNight)
                setPackage(context.packageName)
            }
            context.sendBroadcast(i)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        scheduleNightMode(context, prefs)
        if (!prefs.getBoolean("auto_night", false)) return

        val wasNight = prefs.getBoolean("night_flag", false)
        val nowNight = isNightModeActive(prefs)
        if (nowNight != wasNight) {
            notifyChanged(context, prefs)
        }
    }
}
