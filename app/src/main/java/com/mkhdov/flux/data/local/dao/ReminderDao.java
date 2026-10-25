package com.mkhdov.flux.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.mkhdov.flux.data.local.entity.ReminderEntity;

import java.util.List;

@Dao
public interface ReminderDao {
    @Query("SELECT * FROM event_reminders WHERE eventId = :eventId AND deletedAt = 0 ORDER BY minutesBefore")
    List<ReminderEntity> getForEventSync(String eventId);

    @Query("SELECT * FROM event_reminders WHERE deletedAt = 0 ORDER BY minutesBefore")
    List<ReminderEntity> getAllActiveSync();

    @Query("SELECT * FROM event_reminders WHERE id = :id LIMIT 1")
    ReminderEntity getByIdSync(String id);

    @Query("SELECT * FROM event_reminders WHERE eventId IN (:eventIds) AND deletedAt = 0")
    List<ReminderEntity> getForEventsSync(List<String> eventIds);

    @Query("SELECT * FROM event_reminders WHERE eventId = :eventId")
    List<ReminderEntity> getAllForEventSync(String eventId);

    @Query("SELECT COUNT(*) FROM event_reminders WHERE eventId = :eventId AND deletedAt = 0")
    int countForEvent(String eventId);

    @Query("UPDATE event_reminders SET ownerId = :ownerId WHERE ownerId = 'local'")
    void claimLocalReminders(String ownerId);

    @Query("UPDATE event_reminders SET revision = :revision WHERE id = :id")
    void updateRevision(String id, long revision);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(ReminderEntity reminder);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<ReminderEntity> reminders);
}
