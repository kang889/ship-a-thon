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
    // Tracks which account namespace currently owns scheduled alarms, so a switch can cancel the
    // previous account's alarms even if the new account's data (e.g. cloud fetch) is unavailable.
    private const val ACTIVE = "reminder_active_scope"

    // Per-account alarm state (scheduled ids + delivered flags) lives in its own prefs file, so
    // one account's state can never suppress or cancel another account's reminders.
    private fun prefs(context: Context, ns: String) =
        context.getSharedPreferences("alarms-$ns", Context.MODE_PRIVATE)

    // PendingIntent identity is namespaced by account so alarms with the same reminder id in two
    // accounts remain distinct. The namespace is carried in an extra so delivery reads the right
    // per-account delivered flags regardless of who is signed in when the alarm fires.
    private fun intent(context: Context, ns: String, id: String) = Intent(context, ReminderReceiver::class.java)
        .setData(Uri.parse("studentmemory://reminder/" + Uri.encode(ns) + "/" + Uri.encode(id)))
        .putExtra("ns", ns)

    private fun cancelScheduled(context: Context, ns: String) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val store = prefs(context, ns)
        for (id in store.getStringSet("scheduled", emptySet()) ?: emptySet()) {
            val pending = PendingIntent.getBroadcast(context, 0, intent(context, ns, id),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pending != null) { alarms.cancel(pending); pending.cancel() }
        }
        store.edit().putStringSet("scheduled", emptySet()).apply()
    }

    fun schedule(context: Context, plans: JSONArray, userId: String? = null) {
        val ns = AccountScope.namespace(userId)
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Class reminders", NotificationManager.IMPORTANCE_HIGH))
        val alarms = context.getSystemService(AlarmManager::class.java)
        // If the active account changed, cancel the previous account's alarms first so User A's
        // reminders never remain active once User B is signed in — even before any cloud hydration.
        val active = context.getSharedPreferences(ACTIVE, Context.MODE_PRIVATE)
        val previousNs = active.getString("ns", null)
        if (previousNs != null && previousNs != ns) cancelScheduled(context, previousNs)
        active.edit().putString("ns", ns).apply()

        val prefs = prefs(context, ns)
        // Cancel this account's own previously scheduled alarms, then reschedule from the new plans.
        for (id in prefs.getStringSet("scheduled", emptySet()) ?: emptySet()) {
            val pending = PendingIntent.getBroadcast(context, 0, intent(context, ns, id), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
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
            val pending = PendingIntent.getBroadcast(context, 0, intent(context, ns, id).putExtra("plan", plan.toString()),
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
    fun show(context: Context, plan: JSONObject, ns: String) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val id = plan.getString("id")
        // Delivered flags are per-account, so one account's delivered reminder cannot suppress
        // another account's reminder that happens to share the same id.
        val prefs = prefs(context, ns)
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
        // The alarm carries the account namespace it was scheduled under, so delivery reads the
        // correct per-account delivered flags no matter who is signed in when it fires.
        val ns = intent.getStringExtra("ns") ?: "anon"
        Reminders.show(context, JSONObject(raw), ns)
    }
}
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        val pending = goAsync()
        Thread {
            try {
                // Reschedule from the signed-in user's own store and reminder namespace
                // (anonymous when signed out); never restore another user's alarms.
                com.studentmemory.copilot.services.Account.initialize(context)
                val userId = com.studentmemory.copilot.services.Account.userId()
                val store = CoreStore(context, userId)
                val result = store.execute(JSONObject().put("action", "view"))
                Reminders.schedule(context, result.getJSONObject("view").getJSONArray("notifications"), userId)
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
                // Reschedule from the signed-in user's own store and reminder namespace
                // (anonymous when signed out); never restore another user's alarms.
                com.studentmemory.copilot.services.Account.initialize(context)
                val userId = com.studentmemory.copilot.services.Account.userId()
                val store = CoreStore(context, userId)
                val result = store.execute(JSONObject().put("action", "view"))
                Reminders.schedule(context, result.getJSONObject("view").getJSONArray("notifications"), userId)
            } catch (error: Exception) {
                android.util.Log.e("StudentMemory", "Could not refresh reminder schedule", error)
            } finally { pending.finish() }
        }.start()
    }
}
