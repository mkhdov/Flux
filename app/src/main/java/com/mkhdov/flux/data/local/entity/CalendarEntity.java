package com.mkhdov.flux.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "calendars", indices = {
        @Index("ownerId"),
        @Index(value = {"ownerId", "deletedAt"})
})
public class CalendarEntity {
    @PrimaryKey @NonNull public String id;
    public String ownerId;
    public String name;
    public String color;
    public boolean visible = true;
    public boolean archived = false;
    public boolean isDefault = false;
    public String timezone = "UTC";
    public int defaultReminderMinutes = 0;
    public long createdAt;
    public long updatedAt;
    public long deletedAt;
    public long revision;

    public CalendarEntity() { }

    public boolean isDeleted() { return deletedAt > 0; }
}
