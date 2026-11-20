package io.agentscope.reme.diary

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import java.time.OffsetDateTime
import kotlinx.coroutines.*
import org.json.JSONObject

class Reminders(private val context: Context, private val dao: DiaryDao) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun exactAllowed(): Boolean =
        android.os.Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    private fun intent(scope: String, id: String, action: String = "fire"): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderReceiver::class.java)
                .setAction(action)
                .setData(
                    Uri.parse("reme://reminder/${Uri.encode(scope)}/${Uri.encode(id)}/$action")
                )
                .putExtra("scope", scope)
                .putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    suspend fun replace(scope: String, values: List<JSONObject>, pending: List<Pending>) {
        val blocked =
            pending
                .filter { it.job == "android_update" }
                .map { JSONObject(it.json).str("id") }
                .toSet()
        val accepted =
            values.filter { it.str("task_id") !in blocked }.associateBy { it.getString("id") }
        for (record in dao.records(scope).filter { it.kind == "reminder" }) {
            if (record.id !in accepted) {
                alarms.cancel(intent(scope, record.id))
                dao.delete(scope, "reminder", record.id)
            }
        }
        for ((id, value) in accepted) {
            val prior = dao.find(scope, "reminder", id)?.value()
            if (prior?.optBoolean("posted") == true) value.put("posted", true)
            if (prior?.optBoolean("registered") == true) value.put("registered", true)
            dao.put(Record(scope, "reminder", id, value.toString()))
            schedule(scope, value)
            if (!value.optBoolean("registered")) {
                val repo = context.repository()
                val ack =
                    JSONObject()
                        .put("operation_id", newId())
                        .put("device_id", repo.settings.deviceId)
                        .put("reminder_id", id)
                        .put("state", "registered")
                dao.enqueue(
                    Pending(scope, ack.getString("operation_id"), "android_ack", ack.toString())
                )
                dao.put(Record(scope, "reminder", id, value.put("registered", true).toString()))
            }
        }
    }

    private fun schedule(scope: String, value: JSONObject) {
        if (value.optBoolean("posted")) return
        val at =
            runCatching { OffsetDateTime.parse(value.getString("at")).toInstant().toEpochMilli() }
                .getOrNull() ?: return
        val time = at.coerceAtLeast(System.currentTimeMillis() + 1000)
        val pending = intent(scope, value.getString("id"))
        try {
            if (exactAllowed())
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
        }
    }

    suspend fun restore(scope: String) {
        dao.records(scope).filter { it.kind == "reminder" }.forEach { schedule(scope, it.value()) }
    }

    suspend fun suppress(scope: String, task: String) {
        dao.records(scope)
            .filter { it.kind == "reminder" && it.value().str("task_id") == task }
            .forEach {
                alarms.cancel(intent(scope, it.id))
                dao.delete(scope, "reminder", it.id)
                context.getSystemService(NotificationManager::class.java).cancel(it.id, 0)
            }
    }

    suspend fun cancelAll(scope: String) {
        dao.records(scope)
            .filter { it.kind == "reminder" }
            .forEach { alarms.cancel(intent(scope, it.id)) }
    }

    suspend fun fire(scope: String, id: String) {
        val repository = context.repository()
        if (repository.connection()?.scope != scope) return
        val record = dao.find(scope, "reminder", id) ?: return
        val value = record.value()
        if (value.optBoolean("posted")) return
        if (
            android.os.Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return
        val task = dao.find(scope, "task", value.getString("task_id"))?.value() ?: return
        if (task.str("status") != "open") return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("reminders", "日记待办提醒", NotificationManager.IMPORTANCE_HIGH)
        )
        val open =
            PendingIntent.getActivity(
                context,
                id.hashCode(),
                Intent(context, MainActivity::class.java)
                    .setData(Uri.parse("reme://task/${task.getString("id")}"))
                    .putExtra("task_id", task.getString("id")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(context, "reminders")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(task.getString("title"))
                .setContentText("到了你安排的时间")
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(0, "完成", intent(scope, id, "complete"))
                .addAction(0, "稍后 10 分钟", intent(scope, id, "snooze"))
                .build()
        manager.notify(id, 0, notification)
        dao.put(record.copy(json = value.put("posted", true).toString()))
        val ack =
            JSONObject()
                .put("operation_id", newId())
                .put("device_id", repository.settings.deviceId)
                .put("reminder_id", id)
                .put("state", "posted")
        dao.enqueue(Pending(scope, ack.getString("operation_id"), "android_ack", ack.toString()))
        repository.kick()
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = context.repository()
                val scope =
                    intent.getStringExtra("scope") ?: repo.connection()?.scope ?: return@launch
                val id = intent.getStringExtra("id")
                if (id == null) {
                    repo.reminders.restore(scope)
                    return@launch
                }
                if (intent.action == "fire") repo.reminders.fire(scope, id)
                else {
                    if (repo.connection()?.scope != scope) return@launch
                    val reminder = repo.dao.find(scope, "reminder", id)?.value() ?: return@launch
                    val task =
                        repo.dao.find(scope, "task", reminder.getString("task_id"))?.value()
                            ?: return@launch
                    if (intent.action == "complete") repo.update(task, "complete")
                    else if (intent.action == "snooze")
                        repo.update(
                            task,
                            "reschedule",
                            JSONObject()
                                .put("scheduled_at", task.opt("scheduled_at"))
                                .put("due_at", task.opt("due_at"))
                                .put("remind_at", OffsetDateTime.now().plusMinutes(10).toString()),
                        )
                    context.getSystemService(NotificationManager::class.java).cancel(id, 0)
                }
            } finally {
                result.finish()
            }
        }
    }
}
