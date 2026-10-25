package com.mkhdov.flux.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.mkhdov.flux.data.local.entity.OutboxEntity;

import java.util.List;

@Dao
public interface OutboxDao {
    @Query("SELECT * FROM outbox WHERE state IN ('PENDING','FAILED') AND nextAttemptAt <= :now ORDER BY createdAt LIMIT :limit")
    List<OutboxEntity> getDue(long now, int limit);

    @Query("SELECT * FROM outbox WHERE entityTable = :table AND entityId = :entityId AND state IN ('PENDING','FAILED','IN_FLIGHT') ORDER BY createdAt DESC LIMIT 1")
    OutboxEntity getPendingForEntity(String table, String entityId);

    @Query("SELECT * FROM outbox ORDER BY createdAt")
    List<OutboxEntity> getAllSync();

    @Query("SELECT COUNT(*) FROM outbox WHERE state IN ('PENDING','FAILED','IN_FLIGHT')")
    int pendingCount();

    @Query("SELECT COUNT(*) FROM outbox WHERE state = 'CONFLICT'")
    int conflictCount();

    @Query("SELECT COUNT(*) FROM outbox WHERE state = 'CONFLICT'")
    LiveData<Integer> observeConflictCount();

    @Query("SELECT COUNT(*) FROM outbox WHERE state IN ('PENDING','FAILED','IN_FLIGHT')")
    LiveData<Integer> observePendingCount();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(OutboxEntity operation);

    @Query("DELETE FROM outbox WHERE id = :id")
    void deleteById(String id);

    @Query("DELETE FROM outbox WHERE entityTable = :table AND entityId = :entityId AND state IN ('PENDING','FAILED')")
    void deletePendingForEntity(String table, String entityId);

    @Query("UPDATE outbox SET state = :state, attempts = :attempts, lastError = :error, nextAttemptAt = :nextAttemptAt WHERE id = :id")
    void updateState(String id, String state, int attempts, String error, long nextAttemptAt);

    @Query("UPDATE outbox SET state = 'PENDING', attempts = 0, nextAttemptAt = 0, lastError = NULL WHERE state = 'CONFLICT'")
    void requeueConflicts();

    @Query("UPDATE outbox SET state = 'PENDING', attempts = 0, nextAttemptAt = 0, lastError = NULL, baseRevision = -1 WHERE state = 'CONFLICT'")
    void forceRequeueConflicts();
}
