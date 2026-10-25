package com.mkhdov.flux.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "scheduled_alarms", indices = {@Index("triggerAt")})
public class ScheduledAlarmEntity {
    @PrimaryKey @NonNull public String key;
    public String reminderId;
    public String eventId;
    public long occurrenceStart;
    public long triggerAt;
    public int notificationId;
    public long eventStartAt;
    public String eventTitle;

    public ScheduledAlarmEntity() { }
}
