package com.mkhdov.flux.notifications;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.mkhdov.flux.sync.SyncEngine;
import com.mkhdov.flux.util.HashUtils;

import java.util.Map;

public class FluxMessagingService extends FirebaseMessagingService {
    @Override public void onNewToken(@NonNull String token) {
        PushManager.storeToken(this, token);
        PushManager.initialize(this);
        SyncEngine.get(this).requestSync("push token updated");
    }

    @Override public void onMessageReceived(@NonNull RemoteMessage message) {
        Map<String, String> data = message.getData();
        String eventId = data.get("event_id");
        String reminderId = data.get("reminder_id");
        String occurrence = data.get("occurrence_start");
        if (eventId == null) return;
        long occurrenceStart = 0;
        try { occurrenceStart = occurrence == null ? 0 : Long.parseLong(occurrence); } catch (Exception ignored) { }
        int id = reminderId == null ? HashUtils.positiveId(eventId + "|" + occurrenceStart)
                : HashUtils.positiveId(reminderId + "|" + occurrenceStart);
        NotificationHelper.showReminder(this, eventId, reminderId, occurrenceStart, id);
    }
}
