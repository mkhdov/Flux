package com.mkhdov.flux.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "event_attachments", indices = {
        @Index("eventId"), @Index("ownerId"), @Index("deletedAt"), @Index("uploadStatus")
})
public class AttachmentEntity {
    public static final String STATUS_LOCAL = "LOCAL";
    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_UPLOADING = "UPLOADING";
    public static final String STATUS_UPLOADED = "UPLOADED";
    public static final String STATUS_FAILED = "FAILED";

    @PrimaryKey @NonNull public String id;
    public String ownerId;
    public String eventId;
    public String fileName;
    public String mimeType;
    public long sizeBytes;
    public String storagePath;
    public String localPath;
    public String uploadStatus = STATUS_LOCAL;
    public String uploadSession;
    public long createdAt;
    public long updatedAt;
    public long deletedAt;
    public long revision;

    public AttachmentEntity() { }
    public boolean isDeleted() { return deletedAt > 0; }
    public boolean isUploaded() { return STATUS_UPLOADED.equals(uploadStatus); }
}
