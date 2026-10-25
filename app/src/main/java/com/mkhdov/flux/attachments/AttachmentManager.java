package com.mkhdov.flux.attachments;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.mkhdov.flux.BuildConfig;
import com.mkhdov.flux.data.local.AppDatabase;
import com.mkhdov.flux.data.local.entity.AttachmentEntity;
import com.mkhdov.flux.data.remote.SupabaseApi;
import com.mkhdov.flux.data.repo.AuthManager;
import com.mkhdov.flux.data.repo.CalendarRepository;
import com.mkhdov.flux.util.DataBus;
import com.mkhdov.flux.widget.WidgetUpdateManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class AttachmentManager {
    public static final long MAX_FILE_SIZE = 50L * 1024L * 1024L;
    public static final int MAX_ATTACHMENTS_PER_EVENT = 10;
    public static final String BUCKET = "attachments";
    public static final String WORK_PREFIX = "flux-attachment-";
    private static final String TAG = "AttachmentManager";
    private static volatile AttachmentManager INSTANCE;

    private final Context context;
    private final AppDatabase db;
    private final AuthManager auth;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final MutableLiveData<Map<String, Integer>> progress = new MutableLiveData<>(new HashMap<>());
    private final Map<String, Integer> progressMap = new ConcurrentHashMap<>();

    public interface Callback<T> { void onSuccess(T value); void onError(String message); }

    public static AttachmentManager get(Context context) {
        if (INSTANCE == null) {
            synchronized (AttachmentManager.class) {
                if (INSTANCE == null) INSTANCE = new AttachmentManager(context.getApplicationContext());
            }
        }
        return INSTANCE;
    }

    private AttachmentManager(Context context) {
        this.context = context;
        this.db = AppDatabase.get(context);
        this.auth = AuthManager.get(context);
    }

    public LiveData<Map<String, Integer>> progress() { return progress; }

    public void addAttachment(Uri uri, String eventId, Callback<AttachmentEntity> callback) {
        executor.execute(() -> {
            try {
                String owner = auth.hasSession() && auth.userId() != null ? auth.userId() : "local";
                boolean localMode = "local".equals(owner);
                int count = db.attachmentDao().countActiveForEvent(eventId);
                if (count >= MAX_ATTACHMENTS_PER_EVENT) {
                    mainError(callback, "You can attach up to " + MAX_ATTACHMENTS_PER_EVENT + " files per event");
                    return;
                }
                String name = queryName(uri);
                long declared = querySize(uri);
                if (declared > MAX_FILE_SIZE) {
                    mainError(callback, "Files must be 50 MB or smaller");
                    return;
                }
                String mime = context.getContentResolver().getType(uri);
                if (mime == null) mime = "application/octet-stream";
                String id = UUID.randomUUID().toString();
                File dir = new File(context.getFilesDir(), "attachments");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Unable to create attachment directory");
                File target = new File(dir, id + "_" + sanitize(name));
                copy(uri, target);
                if (target.length() > MAX_FILE_SIZE) {
                    target.delete();
                    mainError(callback, "Files must be 50 MB or smaller");
                    return;
                }
                long now = System.currentTimeMillis();
                AttachmentEntity attachment = new AttachmentEntity();
                attachment.id = id;
                attachment.ownerId = owner;
                attachment.eventId = eventId;
                attachment.fileName = name;
                attachment.mimeType = mime;
                attachment.sizeBytes = target.length();
                attachment.localPath = target.getAbsolutePath();
                attachment.storagePath = owner + "/" + eventId + "/" + id + "_" + sanitize(name);
                attachment.uploadStatus = localMode ? AttachmentEntity.STATUS_LOCAL : AttachmentEntity.STATUS_QUEUED;
                attachment.createdAt = now;
                attachment.updatedAt = now;
                db.attachmentDao().upsert(attachment);
                if (!localMode) enqueueUpload(id);
                DataBus.notifyChanged();
                WidgetUpdateManager.requestUpdate(context);
                CalendarRepository.get(context).mainHandler().post(() -> callback.onSuccess(attachment));
            } catch (Exception e) {
                mainError(callback, friendly(e));
            }
        });
    }

    public void enqueueUpload(String attachmentId) {
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AttachmentUploadWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
                .setInputData(new androidx.work.Data.Builder().putString("attachmentId", attachmentId).build())
                .addTag(WORK_PREFIX + attachmentId)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_PREFIX + attachmentId,
                ExistingWorkPolicy.KEEP, request);
    }

    public void cancelUpload(String attachmentId) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_PREFIX + attachmentId);
        executor.execute(() -> {
            try {
                AttachmentEntity a = db.attachmentDao().getByIdSync(attachmentId);
                if (a != null) {
                    a.uploadStatus = AttachmentEntity.STATUS_FAILED;
                    a.updatedAt = System.currentTimeMillis();
                    db.attachmentDao().upsert(a);
                }
                progressMap.remove(attachmentId);
                publishProgress();
                DataBus.notifyChanged();
            } catch (Exception ignored) { }
        });
    }

    public void retry(String attachmentId) {
        executor.execute(() -> {
            try {
                AttachmentEntity a = db.attachmentDao().getByIdSync(attachmentId);
                if (a == null) return;
                a.uploadStatus = AttachmentEntity.STATUS_QUEUED;
                a.updatedAt = System.currentTimeMillis();
                db.attachmentDao().upsert(a);
                enqueueUpload(attachmentId);
                DataBus.notifyChanged();
            } catch (Exception ignored) { }
        });
    }

    public void deleteAttachment(String attachmentId, Callback<Void> callback) {
        executor.execute(() -> {
            try {
                AttachmentEntity a = db.attachmentDao().getByIdSync(attachmentId);
                if (a == null) { mainSuccess(callback, null); return; }
                WorkManager.getInstance(context).cancelUniqueWork(WORK_PREFIX + attachmentId);
                long now = System.currentTimeMillis();
                a.deletedAt = now;
                a.updatedAt = now;
                db.attachmentDao().upsert(a);
                CalendarRepository.get(context).enqueue("event_attachments", a.id, "DELETE",
                        com.mkhdov.flux.data.remote.RemoteMapper.attachment(a), a.revision);
                if (a.localPath != null) { try { new File(a.localPath).delete(); } catch (Exception ignored) { } }
                if (a.isUploaded() && auth.hasSession() && a.storagePath != null) {
                    try {
                        String token = auth.getAccessTokenBlocking();
                        if (token != null) auth.api().deleteObject(BUCKET, a.storagePath, token);
                    } catch (Exception ignored) { }
                }
                progressMap.remove(attachmentId);
                publishProgress();
                DataBus.notifyChanged();
                WidgetUpdateManager.requestUpdate(context);
                CalendarRepository.get(context).mainHandler().post(() -> callback.onSuccess(null));
            } catch (Exception e) { mainError(callback, friendly(e)); }
        });
    }

    public void openAttachment(Activity activity, String attachmentId, Callback<Void> callback) {
        executor.execute(() -> {
            try {
                AttachmentEntity a = db.attachmentDao().getByIdSync(attachmentId);
                if (a == null || a.deletedAt > 0) { mainError(callback, "Attachment not found"); return; }
                File file = null;
                if (a.localPath != null) {
                    File local = new File(a.localPath);
                    if (local.exists() && local.length() > 0) file = local;
                }
                if (file == null) {
                    if (auth.getAccessTokenBlocking() == null) {
                        mainError(callback, "Sign in to download this attachment");
                        return;
                    }
                    String token = auth.getAccessTokenBlocking();
                    String url = auth.api().signedUrl(BUCKET, a.storagePath, 3600, token);
                    if (url == null) { mainError(callback, "Unable to create secure download link"); return; }
                    byte[] bytes = auth.api().download(url);
                    File cacheDir = new File(context.getCacheDir(), "attachments");
                    if (!cacheDir.exists()) cacheDir.mkdirs();
                    File target = new File(cacheDir, a.id + "_" + sanitize(a.fileName));
                    try (FileOutputStream out = new FileOutputStream(target)) { out.write(bytes); }
                    file = target;
                }
                final File finalFile = file;
                Uri uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".files", finalFile);
                Intent intent = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, a.mimeType == null ? "*/*" : a.mimeType)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                CalendarRepository.get(context).mainHandler().post(() -> {
                    try {
                        activity.startActivity(intent);
                        callback.onSuccess(null);
                    } catch (Exception e) {
                        callback.onError("No app can open this file");
                    }
                });
            } catch (Exception e) { mainError(callback, friendly(e)); }
        });
    }

    public void reportProgress(String attachmentId, int percent) {
        Integer previous = progressMap.get(attachmentId);
        if (previous != null && previous == percent) return;
        progressMap.put(attachmentId, Math.max(0, Math.min(100, percent)));
        publishProgress();
    }

    public void removeProgress(String attachmentId) {
        progressMap.remove(attachmentId);
        publishProgress();
    }

    private void publishProgress() {
        progress.postValue(new HashMap<>(progressMap));
    }

    private String queryName(Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String value = cursor.getString(idx);
                    if (value != null && !value.trim().isEmpty()) return value;
                }
            }
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment();
        return last == null ? "attachment" : last;
    }

    private long querySize(Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !cursor.isNull(idx)) return cursor.getLong(idx);
            }
        } catch (Exception ignored) { }
        return -1;
    }

    private void copy(Uri source, File target) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IOException("Unable to read selected file");
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > MAX_FILE_SIZE) throw new IOException("Files must be 50 MB or smaller");
                out.write(buffer, 0, read);
            }
        }
    }

    public static String sanitize(String name) {
        if (name == null || name.trim().isEmpty()) return "attachment";
        String value = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (value.length() > 120) value = value.substring(value.length() - 120);
        return value;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void mainError(Callback<?> callback, String message) {
        CalendarRepository.get(context).mainHandler().post(() -> callback.onError(message));
    }

    private <T> void mainSuccess(Callback<T> callback, T value) {
        CalendarRepository.get(context).mainHandler().post(() -> callback.onSuccess(value));
    }

    private String friendly(Exception e) {
        if (e.getMessage() == null) return "Attachment operation failed";
        return e.getMessage();
    }
}
