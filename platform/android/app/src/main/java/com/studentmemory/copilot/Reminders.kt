package com.studentmemory.copilot

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

object Reminders {
    private const val CHANNEL = "student_reminders"
    private fun intent(context: Context, id: String) = Intent(context, ReminderReceiver::class.java)
        .setData(Uri.parse("studentmemory://reminder/" + Uri.encode(id)))
    fun schedule(context: Context, plans: JSONArray) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Class reminders", NotificationManager.IMPORTANCE_HIGH))
        val alarms = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)
        val previous = prefs.getStringSet("scheduled", emptySet()) ?: emptySet()
        for (id in previous) {
            val pending = PendingIntent.getBroadcast(context, 0, intent(context, id), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pending != null) { alarms.cancel(pending); pending.cancel() }
        }
        val scheduled = mutableSetOf<String>()
        for (i in 0 until plans.length()) {
            val plan = plans.getJSONObject(i)
            val id = plan.getString("id")
            if (prefs.getBoolean("delivered:$id", false)) continue
            val at = LocalDateTime.ofEpochSecond(plan.getLong("fireAt") * 60, 0, ZoneOffset.UTC)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (at <= System.currentTimeMillis()) continue
            val pending = PendingIntent.getBroadcast(context, 0, intent(context, id).putExtra("plan", plan.toString()),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // Inexact device alarms avoid exact-alarm permission and battery-heavy polling.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            scheduled.add(id)
        }
        prefs.edit().putStringSet("scheduled", scheduled).apply()
        val refresh = PendingIntent.getBroadcast(context, 0, Intent(context, RefreshReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + AlarmManager.INTERVAL_DAY,
            AlarmManager.INTERVAL_DAY, refresh)
    }
    fun show(context: Context, plan: JSONObject) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val id = plan.getString("id")
        val prefs = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)
        if (prefs.getBoolean("delivered:$id", false)) return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(NotificationManager::class.java).notify(id, 0,
            Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(plan.getString("title")).setContentText(plan.getString("body"))
                .setStyle(Notification.BigTextStyle().bigText(plan.getString("body")))
                .setContentIntent(open).setAutoCancel(true).build())
        prefs.edit().putBoolean("delivered:$id", true).apply()
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra("plan") ?: return
        Reminders.show(context, JSONObject(raw))
    }
}
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        val pending = goAsync()
        Thread {
            try {
                val result = CoreStore(context).execute(JSONObject().put("action", "view"))
                Reminders.schedule(context, result.getJSONObject("view").getJSONArray("notifications"))
            } catch (error: Exception) {
                android.util.Log.e("StudentMemory", "Could not restore reminder schedule", error)
            } finally { pending.finish() }
        }.start()
    }
}

class RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                val result = CoreStore(context).execute(JSONObject().put("action", "view"))
                Reminders.schedule(context, result.getJSONObject("view").getJSONArray("notifications"))
            } catch (error: Exception) {
                android.util.Log.e("StudentMemory", "Could not refresh reminder schedule", error)
            } finally { pending.finish() }
        }.start()
    }
}
