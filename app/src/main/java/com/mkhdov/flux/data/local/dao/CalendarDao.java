package com.mkhdov.flux.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.mkhdov.flux.data.local.entity.CalendarEntity;

import java.util.List;

@Dao
public interface CalendarDao {
    @Query("SELECT * FROM calendars WHERE deletedAt = 0 ORDER BY isDefault DESC, archived ASC, name COLLATE NOCASE")
    LiveData<List<CalendarEntity>> observeAll();

    @Query("SELECT * FROM calendars WHERE deletedAt = 0 ORDER BY isDefault DESC, archived ASC, name COLLATE NOCASE")
    List<CalendarEntity> getAllSync();

    @Query("SELECT * FROM calendars WHERE ownerId = :ownerId AND deletedAt = 0 ORDER BY isDefault DESC, archived ASC, name COLLATE NOCASE")
    List<CalendarEntity> getForOwnerSync(String ownerId);

    @Query("SELECT * FROM calendars WHERE id = :id LIMIT 1")
    CalendarEntity getByIdSync(String id);

    @Query("SELECT * FROM calendars WHERE deletedAt = 0 AND isDefault = 1 ORDER BY createdAt LIMIT 1")
    CalendarEntity getDefaultSync();

    @Query("SELECT * FROM calendars WHERE deletedAt = 0 AND visible = 1 ORDER BY isDefault DESC, name COLLATE NOCASE")
    List<CalendarEntity> getVisibleSync();

    @Query("UPDATE calendars SET revision = :revision WHERE id = :id")
    void updateRevision(String id, long revision);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(CalendarEntity calendar);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<CalendarEntity> calendars);

    @Query("UPDATE calendars SET isDefault = 0")
    void clearDefaults();

    @Query("UPDATE calendars SET isDefault = 0 WHERE ownerId = :ownerId")
    void clearDefaultsForOwner(String ownerId);

    @Query("UPDATE calendars SET ownerId = :ownerId WHERE ownerId = 'local'")
    void claimLocalCalendars(String ownerId);

    @Query("SELECT COUNT(*) FROM calendars WHERE deletedAt = 0")
    int countActive();
}
