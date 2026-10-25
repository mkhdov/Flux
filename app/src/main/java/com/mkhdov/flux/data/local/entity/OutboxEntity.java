package com.mkhdov.flux.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "outbox", indices = {
        @Index("state"), @Index(value = {"entityTable", "entityId"}), @Index("createdAt")
})
public class OutboxEntity {
    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_IN_FLIGHT = "IN_FLIGHT";
    public static final String STATE_FAILED = "FAILED";
    public static final String STATE_CONFLICT = "CONFLICT";

    @PrimaryKey @NonNull public String id;
    public String entityTable;
    public String entityId;
    public String operation;
    public String payload;
    public long baseRevision;
    public long createdAt;
    public int attempts;
    public String state = STATE_PENDING;
    public String lastError;
    public long nextAttemptAt;

    public OutboxEntity() { }
}
