package com.mkhdov.flux.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "event_exceptions", indices = {
        @Index("eventId"), @Index("ownerId"), @Index("deletedAt"),
        @Index(value = {"eventId", "occurrenceStart"}, unique = true)
})
public class EventExceptionEntity {
    @PrimaryKey @NonNull public String id;
    public String ownerId;
    public String eventId;
    public long occurrenceStart;
    public boolean cancelled;
    public String overrideEventId;
    public long createdAt;
    public long updatedAt;
    public long deletedAt;
    public long revision;

    public EventExceptionEntity() { }
    public boolean isDeleted() { return deletedAt > 0; }
}
