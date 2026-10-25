package com.mkhdov.flux.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "event_reminders", indices = {
        @Index("eventId"), @Index("ownerId"), @Index("deletedAt")
})
public class ReminderEntity {
    @PrimaryKey @NonNull public String id;
    public String ownerId;
    public String eventId;
    public int minutesBefore;
    public String channel = "local";
    public long createdAt;
    public long updatedAt;
    public long deletedAt;
    public long revision;

    public ReminderEntity() { }
    public boolean isDeleted() { return deletedAt > 0; }
}
