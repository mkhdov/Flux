package com.mkhdov.flux.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "sync_state")
public class SyncStateEntity {
    @PrimaryKey @NonNull public String key;
    public String value;
    public long updatedAt;

    public SyncStateEntity() { }
    public SyncStateEntity(String key, String value, long updatedAt) {
        this.key = key; this.value = value; this.updatedAt = updatedAt;
    }
}
