package com.mkhdov.flux.notifications;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.mkhdov.flux.R;
import com.mkhdov.flux.data.local.AppDatabase;
import com.mkhdov.flux.data.local.entity.CalendarEntity;
import com.mkhdov.flux.data.local.entity.EventEntity;
import com.mkhdov.flux.data.local.entity.ReminderEntity;
import com.mkhdov.flux.ui.event.EventEditorActivity;
import com.mkhdov.flux.util.EventPalette;
import com.mkhdov.flux.util.TimeUtils;

import java.time.ZoneId;

public final class NotificationHelper {
    public static final String CHANNEL_REMINDERS = "event_reminders_v2";
    public static final String CHANNEL_GENERAL = "flux_general_v1";

    private NotificationHelper() { }

    public static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel reminders = new NotificationChannel(CHANNEL_REMINDERS,
                context.getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_HIGH);
        reminders.setDescription(context.getString(R.string.notification_channel_desc));
        reminders.enableVibration(true);
        reminders.setLockscreenVisibility(NotificationCompat.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(reminders);
        NotificationChannel general = new NotificationChannel(CHANNEL_GENERAL,
                "Flux", NotificationManager.IMPORTANCE_DEFAULT);
        general.setDescription("Sync status and general messages");
        manager.createNotificationChannel(general);
    }

    public static boolean canPost(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        return NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    public static void showReminder(Context context, String eventId, String reminderId, long occurrenceStart, int notificationId) {
        AppDatabase db = AppDatabase.get(context);
        EventEntity event = db.eventDao().getByIdSync(eventId);
        if (event == null || event.deletedAt > 0) return;
        CalendarEntity calendar = db.calendarDao().getByIdSync(event.calendarId);
        String title = event.title == null || event.title.trim().isEmpty() ? context.getString(R.string.untitled) : event.title;
        String when = event.allDay ? "All day" : TimeUtils.formatTimeRange(event.startAt, event.endAt, TimeUtils.zoneOf(event));
        String text = when;
        if (event.location != null && !event.location.trim().isEmpty()) text += " · " + event.location;
        int color = EventPalette.parse(event.color, calendar == null ? EventPalette.parse("#039BE5", 0xFF039BE5) : EventPalette.parse(calendar.color, 0xFF039BE5));
        Intent open = new Intent(context, EventEditorActivity.class)
                .putExtra(EventEditorActivity.EXTRA_EVENT_ID, eventId)
                .putExtra(EventEditorActivity.EXTRA_OCCURRENCE_START, occurrenceStart)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(context, notificationId, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_REMINDERS)
                .setSmallIcon(R.drawable.ic_notification_small)
                .setColor(color)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text + (event.description != null && !event.description.isEmpty() ? "\n" + event.description : "")))
                .setContentIntent(content)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setWhen(event.startAt)
                .setShowWhen(true)
                .setOnlyAlertOnce(true);
        try {
            if (canPost(context)) NotificationManagerCompat.from(context).notify(notificationId, builder.build());
        } catch (SecurityException ignored) { }
    }

    public static void showTestNotification(Context context) {
        Intent open = new Intent(context, com.mkhdov.flux.ui.main.MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(context, 908001, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_GENERAL)
                .setSmallIcon(R.drawable.ic_notification_small)
                .setColor(0xFFFF3B30)
                .setContentTitle("Flux notifications are working")
                .setContentText("Local reminders are scheduled on this device. Exact delivery depends on Android battery settings.")
                .setStyle(new NotificationCompat.BigTextStyle().bigText("Local reminders are scheduled on this device, even when you are offline. Android may delay delivery if notifications or exact alarms are disabled."))
                .setContentIntent(content)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        try {
            if (canPost(context)) NotificationManagerCompat.from(context).notify(908001, builder.build());
        } catch (SecurityException ignored) { }
    }
}
