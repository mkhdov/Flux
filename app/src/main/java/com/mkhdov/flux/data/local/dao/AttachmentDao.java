package com.mkhdov.flux.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.mkhdov.flux.data.local.entity.AttachmentEntity;

import java.util.List;

@Dao
public interface AttachmentDao {
    @Query("SELECT * FROM event_attachments WHERE eventId = :eventId AND deletedAt = 0 ORDER BY createdAt")
    List<AttachmentEntity> getForEventSync(String eventId);

    @Query("SELECT * FROM event_attachments WHERE eventId = :eventId AND deletedAt = 0 ORDER BY createdAt")
    LiveData<List<AttachmentEntity>> observeForEvent(String eventId);

    @Query("SELECT * FROM event_attachments WHERE deletedAt = 0")
    List<AttachmentEntity> getAllActiveSync();

    @Query("SELECT * FROM event_attachments WHERE id = :id LIMIT 1")
    AttachmentEntity getByIdSync(String id);

    @Query("SELECT * FROM event_attachments WHERE uploadStatus IN ('LOCAL','QUEUED','FAILED','UPLOADING') AND deletedAt = 0")
    List<AttachmentEntity> getPendingUploads();

    @Query("SELECT COUNT(*) FROM event_attachments WHERE eventId = :eventId AND deletedAt = 0")
    int countActiveForEvent(String eventId);

    @Query("SELECT COUNT(*) FROM event_attachments WHERE deletedAt = 0")
    int countActive();

    @Query("UPDATE event_attachments SET ownerId = :ownerId WHERE ownerId = 'local'")
    void claimLocalAttachments(String ownerId);

    @Query("UPDATE event_attachments SET revision = :revision WHERE id = :id")
    void updateRevision(String id, long revision);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(AttachmentEntity attachment);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<AttachmentEntity> attachments);
}
