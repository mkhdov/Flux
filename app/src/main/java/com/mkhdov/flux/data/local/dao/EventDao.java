package com.mkhdov.flux.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.mkhdov.flux.data.local.entity.EventEntity;

import java.util.List;

@Dao
public interface EventDao {
    @Query("SELECT * FROM events WHERE deletedAt = 0 AND (" +
            "(rrule IS NULL AND startAt < :rangeEnd AND endAt > :rangeStart) OR " +
            "(rrule IS NOT NULL AND startAt < :rangeEnd)) ORDER BY startAt")
    List<EventEntity> getForRangeSync(long rangeStart, long rangeEnd);

    @Query("SELECT * FROM events WHERE id = :id LIMIT 1")
    EventEntity getByIdSync(String id);

    @Query("SELECT * FROM events WHERE deletedAt = 0 ORDER BY startAt")
    List<EventEntity> getAllActiveSync();

    @Query("SELECT * FROM events WHERE seriesId = :seriesId AND deletedAt = 0 ORDER BY startAt")
    List<EventEntity> getBySeriesSync(String seriesId);

    @Query("SELECT * FROM events WHERE recurrenceParentId = :parentId AND deletedAt = 0")
    List<EventEntity> getOverridesForParent(String parentId);

    @Query("SELECT * FROM events WHERE recurrenceParentId = :parentId")
    List<EventEntity> getAllOverridesForParent(String parentId);

    @Query("SELECT * FROM events WHERE calendarId = :calendarId AND deletedAt = 0")
    List<EventEntity> getByCalendarSync(String calendarId);

    @Query("SELECT * FROM events WHERE deletedAt = 0 AND startAt >= :from AND startAt <= :to ORDER BY startAt")
    List<EventEntity> getStartingBetween(long from, long to);

    @Query("SELECT DISTINCT e.* FROM events e LEFT JOIN event_attachments a ON a.eventId = e.id AND a.deletedAt = 0 " +
            "WHERE e.deletedAt = 0 AND (" +
            "e.title LIKE :query OR e.description LIKE :query OR e.location LIKE :query OR a.fileName LIKE :query) " +
            "AND (:calendarId IS NULL OR e.calendarId = :calendarId) " +
            "AND (:color IS NULL OR e.color = :color) " +
            "AND e.startAt >= :from AND e.startAt <= :to " +
            "ORDER BY e.startAt LIMIT 300")
    List<EventEntity> search(String query, String calendarId, String color, long from, long to);

    @Query("SELECT COUNT(*) FROM events WHERE deletedAt = 0")
    int countActive();

    @Query("SELECT COUNT(*) FROM events WHERE calendarId = :calendarId AND deletedAt = 0")
    int countForCalendar(String calendarId);


    @Query("UPDATE events SET ownerId = :ownerId WHERE ownerId = 'local'")
    void claimLocalEvents(String ownerId);

    @Query("UPDATE events SET calendarId = :targetId WHERE calendarId = :sourceId")
    void moveAllToCalendar(String sourceId, String targetId);

    @Query("UPDATE events SET revision = :revision WHERE id = :id")
    void updateRevision(String id, long revision);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(EventEntity event);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<EventEntity> events);
}
