package com.mkhdov.flux.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.mkhdov.flux.data.local.entity.SyncStateEntity;

@Dao
public interface SyncStateDao {
    @Query("SELECT value FROM sync_state WHERE key = :key LIMIT 1")
    String get(String key);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void put(SyncStateEntity state);
}
