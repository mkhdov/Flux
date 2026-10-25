package com.mkhdov.flux.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.mkhdov.flux.data.local.entity.EventExceptionEntity;

import java.util.List;

@Dao
public interface EventExceptionDao {
    @Query("SELECT * FROM event_exceptions WHERE eventId = :eventId AND deletedAt = 0 ORDER BY occurrenceStart")
    List<EventExceptionEntity> getForEventSync(String eventId);

    @Query("SELECT * FROM event_exceptions WHERE eventId IN (:eventIds) AND deletedAt = 0 ORDER BY occurrenceStart")
    List<EventExceptionEntity> getForEventsSync(List<String> eventIds);

    @Query("SELECT * FROM event_exceptions WHERE eventId = :eventId AND occurrenceStart = :occurrenceStart AND deletedAt = 0 LIMIT 1")
    EventExceptionEntity getOccurrenceSync(String eventId, long occurrenceStart);

    @Query("SELECT * FROM event_exceptions WHERE eventId = :eventId ORDER BY occurrenceStart")
    List<EventExceptionEntity> getAllForEventSync(String eventId);

    @Query("SELECT * FROM event_exceptions WHERE id = :id LIMIT 1")
    EventExceptionEntity getByIdSync(String id);

    @Query("UPDATE event_exceptions SET ownerId = :ownerId WHERE ownerId = 'local'")
    void claimLocalExceptions(String ownerId);

    @Query("UPDATE event_exceptions SET eventId = :newParentId WHERE eventId = :oldParentId AND occurrenceStart >= :from")
    void moveFutureToSeries(String oldParentId, String newParentId, long from);

    @Query("UPDATE event_exceptions SET revision = :revision WHERE id = :id")
    void updateRevision(String id, long revision);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(EventExceptionEntity exception);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<EventExceptionEntity> exceptions);
}
