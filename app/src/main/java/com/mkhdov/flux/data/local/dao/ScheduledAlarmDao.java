package com.mkhdov.flux.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.mkhdov.flux.data.local.entity.ScheduledAlarmEntity;

import java.util.List;

@Dao
public interface ScheduledAlarmDao {
    @Query("SELECT * FROM scheduled_alarms ORDER BY triggerAt")
    List<ScheduledAlarmEntity> getAllSync();

    @Query("SELECT * FROM scheduled_alarms WHERE key = :key LIMIT 1")
    ScheduledAlarmEntity getByKey(String key);

    @Query("DELETE FROM scheduled_alarms WHERE key = :key")
    void deleteByKey(String key);

    @Query("DELETE FROM scheduled_alarms")
    void deleteAll();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<ScheduledAlarmEntity> alarms);
}
