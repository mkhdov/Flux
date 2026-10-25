package com.mkhdov.flux.notifications;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.mkhdov.flux.data.local.AppDatabase;
import com.mkhdov.flux.data.local.entity.EventEntity;
import com.mkhdov.flux.data.local.entity.EventExceptionEntity;
import com.mkhdov.flux.data.local.entity.ReminderEntity;
import com.mkhdov.flux.data.local.entity.ScheduledAlarmEntity;
import com.mkhdov.flux.data.model.EventInstance;
import com.mkhdov.flux.recurrence.RecurrenceEngine;
import com.mkhdov.flux.util.HashUtils;
import com.mkhdov.flux.widget.WidgetUpdateManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NotificationScheduler {
    private static final String TAG = "NotificationScheduler";
    private static final long HORIZON_DAYS = 45;
    private static final int MAX_ALARMS = 300;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean PENDING = new AtomicBoolean(false);

    private NotificationScheduler() { }

    public static void scheduleDebounced(Context context) {
        if (!PENDING.compareAndSet(false, true)) return;
        MAIN.postDelayed(() -> {
            PENDING.set(false);
            EXECUTOR.execute(() -> rescheduleSync(context.getApplicationContext()));
        }, 600);
    }

    public static void rescheduleSync(Context context) {
        try {
            AppDatabase db = AppDatabase.get(context);
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) return;
            List<ScheduledAlarmEntity> old = db.scheduledAlarmDao().getAllSync();
            for (ScheduledAlarmEntity alarm : old) cancel(context, alarmManager, alarm);
            db.scheduledAlarmDao().deleteAll();

            long now = System.currentTimeMillis();
            long horizon = now + HORIZON_DAYS * 86400000L;
            List<EventEntity> events = db.eventDao().getForRangeSync(now - 86400000L, horizon);
            List<String> eventIds = new ArrayList<>();
            for (EventEntity e : events) eventIds.add(e.id);
            Map<String, List<ReminderEntity>> reminders = new HashMap<>();
            if (!eventIds.isEmpty()) {
                for (ReminderEntity r : db.reminderDao().getForEventsSync(eventIds)) {
                    reminders.computeIfAbsent(r.eventId, k -> new ArrayList<>()).add(r);
                }
            }
            List<EventExceptionEntity> exceptions = eventIds.isEmpty() ? Collections.emptyList() : db.exceptionDao().getForEventsSync(eventIds);
            List<EventInstance> instances = RecurrenceEngine.expand(events, exceptions, now - 86400000L, horizon);
            List<ScheduledAlarmEntity> desired = new ArrayList<>();
            for (EventInstance instance : instances) {
                List<ReminderEntity> eventReminders = reminders.get(instance.source.id);
                if (eventReminders == null || eventReminders.isEmpty()) continue;
                for (ReminderEntity reminder : eventReminders) {
                    long triggerAt = instance.startAt - reminder.minutesBefore * 60000L;
                    if (triggerAt <= now + 1000L) continue;
                    ScheduledAlarmEntity alarm = new ScheduledAlarmEntity();
                    alarm.key = reminder.id + "|" + instance.occurrenceStart;
                    alarm.reminderId = reminder.id;
                    alarm.eventId = instance.source.id;
                    alarm.occurrenceStart = instance.occurrenceStart;
                    alarm.triggerAt = triggerAt;
                    alarm.notificationId = HashUtils.positiveId(alarm.key);
                    alarm.eventStartAt = instance.startAt;
                    alarm.eventTitle = instance.source.title;
                    desired.add(alarm);
                }
            }
            desired.sort((a, b) -> Long.compare(a.triggerAt, b.triggerAt));
            if (desired.size() > MAX_ALARMS) desired = new ArrayList<>(desired.subList(0, MAX_ALARMS));
            List<ScheduledAlarmEntity> stored = new ArrayList<>();
            for (ScheduledAlarmEntity alarm : desired) {
                try {
                    schedule(context, alarmManager, alarm);
                    stored.add(alarm);
                } catch (SecurityException e) {
                    Log.w(TAG, "Alarm permission denied", e);
                }
            }
            db.scheduledAlarmDao().insertAll(stored);
            WidgetUpdateManager.requestUpdate(context);
            Log.d(TAG, "Scheduled " + stored.size() + " reminders");
        } catch (Exception e) {
            Log.e(TAG, "Unable to reschedule reminders", e);
        }
    }

    private static void schedule(Context context, AlarmManager manager, ScheduledAlarmEntity alarm) {
        PendingIntent pending = pendingIntent(context, alarm);
        boolean exact = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms();
        if (exact) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.triggerAt, pending);
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.triggerAt, pending);
        }
    }

    private static void cancel(Context context, AlarmManager manager, ScheduledAlarmEntity alarm) {
        try { manager.cancel(pendingIntent(context, alarm)); } catch (Exception ignored) { }
    }

    private static PendingIntent pendingIntent(Context context, ScheduledAlarmEntity alarm) {
        Intent intent = new Intent(context, ReminderReceiver.class)
                .setAction("com.mkhdov.flux.REMINDER")
                .setData(android.net.Uri.parse("flux://reminder/" + android.net.Uri.encode(alarm.key)))
                .putExtra("key", alarm.key)
                .putExtra("reminderId", alarm.reminderId)
                .putExtra("eventId", alarm.eventId)
                .putExtra("occurrenceStart", alarm.occurrenceStart)
                .putExtra("notificationId", alarm.notificationId);
        return PendingIntent.getBroadcast(context, alarm.notificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static boolean canScheduleExact(Context context) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return manager != null && manager.canScheduleExactAlarms();
    }

    public static int scheduledCount(Context context) {
        return AppDatabase.get(context).scheduledAlarmDao().getAllSync().size();
    }
}
