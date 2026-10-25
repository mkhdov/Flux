package com.mkhdov.flux.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "events", indices = {
        @Index("ownerId"),
        @Index("calendarId"),
        @Index("startAt"),
        @Index("deletedAt"),
        @Index("seriesId"),
        @Index("recurrenceParentId"),
        @Index(value = {"calendarId", "startAt"})
})
public class EventEntity {
    @PrimaryKey @NonNull public String id;
    public String ownerId;
    public String calendarId;
    public String title;
    public String description;
    public String location;
    public long startAt;
    public long endAt;
    public boolean allDay;
    public String timezone = "UTC";
    public String color;
    public String rrule;
    public String seriesId;
    public String recurrenceParentId;
    public long originalStart;
    public boolean completed;
    public long createdAt;
    public long updatedAt;
    public long deletedAt;
    public long revision;

    public EventEntity() { }

    public boolean isDeleted() { return deletedAt > 0; }
    public boolean isRecurring() { return rrule != null && !rrule.trim().isEmpty(); }
    public boolean isOverride() { return recurrenceParentId != null && !recurrenceParentId.isEmpty(); }
    public long duration() { return Math.max(0L, endAt - startAt); }

    public EventEntity copy() {
        EventEntity e = new EventEntity();
        e.id = id; e.ownerId = ownerId; e.calendarId = calendarId; e.title = title;
        e.description = description; e.location = location; e.startAt = startAt; e.endAt = endAt;
        e.allDay = allDay; e.timezone = timezone; e.color = color; e.rrule = rrule;
        e.seriesId = seriesId; e.recurrenceParentId = recurrenceParentId; e.originalStart = originalStart;
        e.completed = completed; e.createdAt = createdAt; e.updatedAt = updatedAt;
        e.deletedAt = deletedAt; e.revision = revision;
        return e;
    }
}
