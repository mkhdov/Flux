package com.mkhdov.flux.notifications;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.mkhdov.flux.data.local.AppDatabase;
import com.mkhdov.flux.data.local.entity.ScheduledAlarmEntity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReminderReceiver extends BroadcastReceiver {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Override public void onReceive(Context context, Intent intent) {
        String key = intent.getStringExtra("key");
        String reminderId = intent.getStringExtra("reminderId");
        String eventId = intent.getStringExtra("eventId");
        long occurrenceStart = intent.getLongExtra("occurrenceStart", 0);
        int notificationId = intent.getIntExtra("notificationId", 0);
        if (eventId == null) return;
        PendingResult pending = goAsync();
        EXECUTOR.execute(() -> {
            try {
                if (key != null) {
                    AppDatabase db = AppDatabase.get(context);
                    ScheduledAlarmEntity alarm = db.scheduledAlarmDao().getByKey(key);
                    if (alarm == null) return;
                    NotificationHelper.showReminder(context, eventId, reminderId, occurrenceStart, notificationId);
                    db.scheduledAlarmDao().deleteByKey(key);
                }
            } finally {
                pending.finish();
            }
        });
    }
}
