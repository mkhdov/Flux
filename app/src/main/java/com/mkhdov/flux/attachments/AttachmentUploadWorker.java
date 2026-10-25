package com.mkhdov.flux.attachments;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.mkhdov.flux.data.local.AppDatabase;
import com.mkhdov.flux.data.local.entity.AttachmentEntity;
import com.mkhdov.flux.data.remote.RemoteMapper;
import com.mkhdov.flux.data.remote.SupabaseApi;
import com.mkhdov.flux.data.repo.AuthManager;
import com.mkhdov.flux.data.repo.CalendarRepository;
import com.mkhdov.flux.util.DataBus;
import com.mkhdov.flux.widget.WidgetUpdateManager;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public class AttachmentUploadWorker extends Worker {
    private static final String TAG = "AttachmentUpload";
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public AttachmentUploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        String id = getInputData().getString("attachmentId");
        if (id == null) return Result.failure();
        AppDatabase db = AppDatabase.get(getApplicationContext());
        AuthManager auth = AuthManager.get(getApplicationContext());
        AttachmentManager manager = AttachmentManager.get(getApplicationContext());
        AttachmentEntity attachment = db.attachmentDao().getByIdSync(id);
        if (attachment == null || attachment.deletedAt > 0) { manager.removeProgress(id); return Result.success(); }
        if (attachment.isUploaded()) { manager.removeProgress(id); return Result.success(); }
        File file = attachment.localPath == null ? null : new File(attachment.localPath);
        if (file == null || !file.exists()) {
            attachment.uploadStatus = AttachmentEntity.STATUS_FAILED;
            attachment.updatedAt = System.currentTimeMillis();
            db.attachmentDao().upsert(attachment);
            manager.removeProgress(id);
            DataBus.notifyChanged();
            return Result.failure();
        }
        String token = auth.getAccessTokenBlocking();
        if (token == null) {
            attachment.uploadStatus = AttachmentEntity.STATUS_QUEUED;
            attachment.updatedAt = System.currentTimeMillis();
            db.attachmentDao().upsert(attachment);
            return Result.retry();
        }
        String owner = auth.userId();
        if (owner != null && (attachment.storagePath == null || attachment.storagePath.startsWith("local/"))) {
            String name = AttachmentManager.sanitize(attachment.fileName);
            attachment.storagePath = owner + "/" + attachment.eventId + "/" + attachment.id + "_" + name;
        }
        attachment.uploadStatus = AttachmentEntity.STATUS_UPLOADING;
        attachment.updatedAt = System.currentTimeMillis();
        db.attachmentDao().upsert(attachment);
        try {
            String session = auth.api().uploadResumable(file, AttachmentManager.BUCKET, attachment.storagePath,
                    attachment.mimeType, token, attachment.uploadSession, (percent, sessionUrl) -> {
                        manager.reportProgress(id, percent);
                        if (sessionUrl != null && !sessionUrl.equals(attachment.uploadSession)) {
                            attachment.uploadSession = sessionUrl;
                            attachment.updatedAt = System.currentTimeMillis();
                            db.attachmentDao().upsert(attachment);
                        }
                        if (isStopped()) cancelled.set(true);
                    }, cancelled);
            attachment.uploadStatus = AttachmentEntity.STATUS_UPLOADED;
            attachment.uploadSession = session;
            attachment.updatedAt = System.currentTimeMillis();
            db.attachmentDao().upsert(attachment);
            manager.removeProgress(id);
            CalendarRepository.get(getApplicationContext()).enqueue("event_attachments", attachment.id, "UPSERT",
                    RemoteMapper.attachment(attachment), attachment.revision);
            DataBus.notifyChanged();
            WidgetUpdateManager.requestUpdate(getApplicationContext());
            CalendarRepository.get(getApplicationContext()).forceSync();
            return Result.success();
        } catch (SupabaseApi.UploadCancelledException e) {
            attachment.uploadSession = e.sessionUrl;
            attachment.uploadStatus = AttachmentEntity.STATUS_FAILED;
            attachment.updatedAt = System.currentTimeMillis();
            db.attachmentDao().upsert(attachment);
            manager.removeProgress(id);
            DataBus.notifyChanged();
            return Result.failure();
        } catch (Exception e) {
            Log.w(TAG, "Upload failed", e);
            if (e instanceof SupabaseApi.UploadInterruptedException) {
                attachment.uploadSession = ((SupabaseApi.UploadInterruptedException) e).sessionUrl;
            }
            attachment.uploadStatus = AttachmentEntity.STATUS_FAILED;
            attachment.updatedAt = System.currentTimeMillis();
            db.attachmentDao().upsert(attachment);
            manager.reportProgress(id, 0);
            DataBus.notifyChanged();
            return Result.retry();
        }
    }

    @Override public void onStopped() {
        cancelled.set(true);
        super.onStopped();
    }
}
