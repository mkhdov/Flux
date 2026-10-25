package com.mkhdov.flux.sync;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mkhdov.flux.BuildConfig;
import com.mkhdov.flux.data.local.AppDatabase;
import com.mkhdov.flux.data.local.entity.AttachmentEntity;
import com.mkhdov.flux.data.local.entity.CalendarEntity;
import com.mkhdov.flux.data.local.entity.EventEntity;
import com.mkhdov.flux.data.local.entity.EventExceptionEntity;
import com.mkhdov.flux.data.local.entity.OutboxEntity;
import com.mkhdov.flux.data.local.entity.ReminderEntity;
import com.mkhdov.flux.data.local.entity.SyncStateEntity;
import com.mkhdov.flux.data.remote.RemoteMapper;
import com.mkhdov.flux.data.remote.SupabaseApi;
import com.mkhdov.flux.data.remote.SupabaseException;
import com.mkhdov.flux.data.repo.AuthManager;
import com.mkhdov.flux.data.repo.CalendarRepository;
import com.mkhdov.flux.notifications.NotificationScheduler;
import com.mkhdov.flux.util.DataBus;
import com.mkhdov.flux.widget.WidgetUpdateManager;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SyncEngine {
    private static final String TAG = "SyncEngine";
    private static volatile SyncEngine INSTANCE;
    private final Context context;
    private final AppDatabase db;
    private final AuthManager auth;
    private final SupabaseApi api;
    private final CalendarRepository repository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<SyncStatus> status = new MutableLiveData<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean rerun = new AtomicBoolean(false);
    private long debounceToken;
    private RealtimeClient realtime;
    private boolean networkRegistered;

    public static SyncEngine get(Context context) {
        if (INSTANCE == null) {
            synchronized (SyncEngine.class) {
                if (INSTANCE == null) INSTANCE = new SyncEngine(context.getApplicationContext());
            }
        }
        return INSTANCE;
    }

    private SyncEngine(Context context) {
        this.context = context;
        this.db = AppDatabase.get(context);
        this.auth = AuthManager.get(context);
        this.repository = CalendarRepository.get(context);
        this.api = auth.api();
        executor.execute(() -> publish(currentState("Ready")));
        registerNetwork();
    }

    public LiveData<SyncStatus> status() { return status; }

    public void schedulePeriodic() {
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(SyncWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag("flux-sync")
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("flux-periodic-sync",
                ExistingPeriodicWorkPolicy.KEEP, request);
    }

    public void requestSync(String reason) {
        if (!auth.hasSession()) {
            executor.execute(() -> publish(currentState(auth.isLocalOnly() ? "Local-only mode" : "Sign in to sync")));
            return;
        }
        if (!isNetworkAvailable()) {
            executor.execute(() -> publish(currentState("Offline — changes queued")));
            return;
        }
        long token = System.currentTimeMillis();
        synchronized (this) { debounceToken = token; }
        executor.execute(() -> {
            try { Thread.sleep(450); } catch (InterruptedException ignored) { }
            synchronized (SyncEngine.this) {
                if (debounceToken != token) return;
            }
            performSyncInternal(reason);
        });
    }

    public void syncNow(String reason) {
        executor.execute(() -> performSyncInternal(reason));
    }

    public SyncStatus performSyncBlocking(String reason) {
        performSyncInternal(reason);
        SyncStatus s = status.getValue();
        return s == null ? currentState("Ready") : s;
    }

    private void performSyncInternal(String reason) {
        if (!running.compareAndSet(false, true)) { rerun.set(true); return; }
        boolean changed = false;
        try {
            if (!auth.hasSession()) { publish(currentState("Sign in to sync")); return; }
            if (!isNetworkAvailable()) {
                publish(new SyncStatus(SyncStatus.State.OFFLINE, "Offline — changes queued", pending(), conflicts(), lastSync(), realtime != null && realtime.isConnected()));
                return;
            }
            publish(new SyncStatus(SyncStatus.State.SYNCING, "Syncing…", pending(), conflicts(), lastSync(), realtime != null && realtime.isConnected()));
            String token = auth.getAccessTokenBlocking();
            if (token == null) {
                publish(new SyncStatus(SyncStatus.State.NEEDS_ATTENTION, "Session expired. Sign in to sync.", pending(), conflicts(), lastSync(), false));
                return;
            }
            boolean pushed = push(token);
            if (pushed) changed = true;
            boolean pulled = pull(token);
            if (pulled) changed = true;
            updateLastSync();
            registerDevice(token);
            startRealtime(token);
            int pending = pending();
            int conflicts = conflicts();
            if (conflicts > 0) {
                publish(new SyncStatus(SyncStatus.State.NEEDS_ATTENTION, "Resolve " + conflicts + " conflicting change(s)", pending, conflicts, lastSync(), realtime != null && realtime.isConnected()));
            } else {
                publish(new SyncStatus(SyncStatus.State.SYNCED, "All changes synced", pending, 0, lastSync(), realtime != null && realtime.isConnected()));
            }
            if (changed) {
                DataBus.notifyChanged();
                NotificationScheduler.scheduleDebounced(context);
                WidgetUpdateManager.requestUpdate(context);
            }
        } catch (OfflineException e) {
            publish(new SyncStatus(SyncStatus.State.OFFLINE, "Offline — changes queued", pending(), conflicts(), lastSync(), false));
        } catch (Exception e) {
            Log.e(TAG, "Sync failed (" + reason + ")", e);
            publish(new SyncStatus(SyncStatus.State.NEEDS_ATTENTION, "Sync failed: " + safeMessage(e), pending(), conflicts(), lastSync(), false));
        } finally {
            running.set(false);
            if (rerun.getAndSet(false)) requestSync("queued change");
        }
    }

    private boolean push(String token) throws OfflineException {
        List<OutboxEntity> operations = db.outboxDao().getDue(System.currentTimeMillis(), 50);
        boolean changed = false;
        for (OutboxEntity op : operations) {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("p_operation_id", op.id);
                params.addProperty("p_entity", op.entityTable);
                params.addProperty("p_operation", op.operation);
                params.add("p_data", JsonParser.parseString(op.payload));
                params.addProperty("p_base_revision", op.baseRevision);
                JsonElement result;
                try {
                    result = api.rpc("apply_mutation", params, token);
                } catch (SupabaseException e) {
                    if (e.status == 401) {
                        auth.forceRefresh();
                        String fresh = auth.getAccessTokenBlocking();
                        if (fresh == null) throw e;
                        token = fresh;
                        result = api.rpc("apply_mutation", params, token);
                    } else throw e;
                }
                JsonObject obj = result != null && result.isJsonObject() ? result.getAsJsonObject() : new JsonObject();
                if (obj.has("conflict") && obj.get("conflict").getAsBoolean()) {
                    db.outboxDao().updateState(op.id, OutboxEntity.STATE_CONFLICT, op.attempts,
                            "Another device changed this item. Review and resolve.", System.currentTimeMillis());
                    continue;
                }
                if (obj.has("ok") && !obj.get("ok").getAsBoolean()) {
                    throw new IOException(obj.has("error") ? obj.get("error").getAsString() : "Server rejected change");
                }
                db.outboxDao().deleteById(op.id);
                if (op.operation.equals("UPSERT") && obj.has("revision")) {
                    long revision = obj.get("revision").getAsLong();
                    updateLocalRevision(op.entityTable, op.entityId, revision);
                }
                changed = true;
            } catch (IOException e) {
                scheduleRetry(op, e);
                throw new OfflineException(e.getMessage());
            } catch (SupabaseException e) {
                if (e.status == 401) {
                    publish(new SyncStatus(SyncStatus.State.NEEDS_ATTENTION, "Session expired. Sign in to sync.", pending(), conflicts(), lastSync(), false));
                    return changed;
                }
                scheduleRetry(op, e);
                if (e.status == 0 || e.status >= 500) throw new OfflineException(e.getMessage());
            }
        }
        return changed;
    }

    private boolean pull(String token) throws OfflineException {
        String cursorValue = db.syncStateDao().get("cursor");
        long cursor = parseLong(cursorValue, 0);
        int batches = 0;
        boolean changed = false;
        while (batches++ < 50) {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("p_cursor", cursor);
                params.addProperty("p_limit", 200);
                JsonElement result;
                try {
                    result = api.rpc("pull_changes", params, token);
                } catch (SupabaseException e) {
                    if (e.status == 401) {
                        auth.forceRefresh();
                        String fresh = auth.getAccessTokenBlocking();
                        if (fresh == null) throw e;
                        token = fresh;
                        result = api.rpc("pull_changes", params, token);
                    } else throw e;
                }
                if (result == null || !result.isJsonArray()) break;
                JsonArray array = result.getAsJsonArray();
                if (array.size() == 0) break;
                long maxSeq = cursor;
                boolean conflictFound = false;
                for (JsonElement element : array) {
                    if (!element.isJsonObject()) continue;
                    JsonObject change = element.getAsJsonObject();
                    long seq = change.has("seq") ? change.get("seq").getAsLong() : cursor;
                    conflictFound |= applyChange(change);
                    maxSeq = Math.max(maxSeq, seq);
                }
                db.syncStateDao().put(new SyncStateEntity("cursor", String.valueOf(maxSeq), System.currentTimeMillis()));
                cursor = maxSeq;
                changed = true;
                try { api.ackCursor(deviceId(), cursor, token); } catch (Exception ignored) { }
                if (array.size() < 200) break;
            } catch (IOException e) {
                throw new OfflineException(e.getMessage());
            } catch (SupabaseException e) {
                if (e.status == 401) {
                    publish(new SyncStatus(SyncStatus.State.NEEDS_ATTENTION, "Session expired. Sign in to sync.", pending(), conflicts(), lastSync(), false));
                    return changed;
                }
                if (e.status == 0 || e.status >= 500) throw new OfflineException(e.getMessage());
                Log.e(TAG, "Pull failed", e);
                break;
            }
        }
        return changed;
    }

    private boolean applyChange(JsonObject change) {
        String table = RemoteMapper.str(change, "entity_table");
        String operation = RemoteMapper.str(change, "operation");
        JsonElement payloadElement = change.get("payload");
        if (table == null || payloadElement == null || !payloadElement.isJsonObject()) return false;
        JsonObject payload = payloadElement.getAsJsonObject();
        String entityId = RemoteMapper.str(change, "entity_id");
        if (entityId == null) entityId = RemoteMapper.str(payload, "id");
        OutboxEntity pending = db.outboxDao().getPendingForEntity(table, entityId);
        if (pending != null) {
            db.outboxDao().updateState(pending.id, OutboxEntity.STATE_CONFLICT, pending.attempts,
                    "Remote change arrived while local edits were pending.", System.currentTimeMillis());
            return true;
        }
        try {
            db.runInTransaction(() -> {
                switch (table) {
                    case RemoteMapper.T_CALENDARS: {
                        CalendarEntity c = RemoteMapper.calendar(payload);
                        if (c.isDefault && c.deletedAt == 0) db.calendarDao().clearDefaultsForOwner(c.ownerId);
                        db.calendarDao().upsert(c);
                        break;
                    }
                    case RemoteMapper.T_EVENTS: {
                        EventEntity e = RemoteMapper.event(payload);
                        db.eventDao().upsert(e);
                        break;
                    }
                    case RemoteMapper.T_REMINDERS: {
                        ReminderEntity r = RemoteMapper.reminder(payload);
                        db.reminderDao().upsert(r);
                        break;
                    }
                    case RemoteMapper.T_EXCEPTIONS: {
                        EventExceptionEntity x = RemoteMapper.exception(payload);
                        db.exceptionDao().upsert(x);
                        break;
                    }
                    case RemoteMapper.T_ATTACHMENTS: {
                        AttachmentEntity remote = RemoteMapper.attachment(payload);
                        AttachmentEntity local = db.attachmentDao().getByIdSync(remote.id);
                        if (local != null) {
                            remote.localPath = local.localPath;
                            remote.uploadSession = local.uploadSession;
                            if (local.localPath != null && remote.uploadStatus.equals(AttachmentEntity.STATUS_UPLOADED) && remote.deletedAt == 0) {
                                remote.localPath = local.localPath;
                                remote.uploadStatus = AttachmentEntity.STATUS_UPLOADED;
                            }
                        }
                        db.attachmentDao().upsert(remote);
                        break;
                    }
                }
            });
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply remote change " + table + "/" + entityId, e);
            return true;
        }
    }

    private void updateLocalRevision(String table, String id, long revision) {
        try {
            switch (table) {
                case RemoteMapper.T_CALENDARS: db.calendarDao().updateRevision(id, revision); break;
                case RemoteMapper.T_EVENTS: db.eventDao().updateRevision(id, revision); break;
                case RemoteMapper.T_REMINDERS: db.reminderDao().updateRevision(id, revision); break;
                case RemoteMapper.T_EXCEPTIONS: db.exceptionDao().updateRevision(id, revision); break;
                case RemoteMapper.T_ATTACHMENTS: db.attachmentDao().updateRevision(id, revision); break;
            }
        } catch (Exception ignored) { }
    }

    public void resolveConflictsKeepLocal() {
        executor.execute(() -> {
            db.outboxDao().forceRequeueConflicts();
            requestSync("conflicts resolved locally");
        });
    }

    public void retryFailed() {
        executor.execute(() -> {
            db.outboxDao().requeueConflicts();
            requestSync("retry");
        });
    }

    private void scheduleRetry(OutboxEntity op, Exception error) {
        int attempts = op.attempts + 1;
        long backoff = Math.min(TimeUnit.HOURS.toMillis(1), (long) (Math.pow(2, Math.min(8, attempts)) * 2000L));
        long jitter = (long) (Math.random() * 1000);
        db.outboxDao().updateState(op.id, OutboxEntity.STATE_FAILED, attempts,
                safeMessage(error), System.currentTimeMillis() + backoff + jitter);
    }

    private void registerDevice(String token) {
        try {
            JsonObject device = new JsonObject();
            device.addProperty("owner_id", auth.userId());
            device.addProperty("device_id", deviceId());
            device.addProperty("device_name", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            device.addProperty("platform", "android");
            device.addProperty("app_version", BuildConfig.VERSION_NAME);
            device.addProperty("last_seen", com.mkhdov.flux.util.TimeUtils.iso(System.currentTimeMillis()));
            String fcm = com.mkhdov.flux.notifications.PushManager.getToken(context);
            if (fcm != null && !fcm.isEmpty()) device.addProperty("fcm_token", fcm);
            api.registerDevice(device, token);
        } catch (Exception e) {
            Log.w(TAG, "Device registration failed", e);
        }
    }

    private void startRealtime(String token) {
        if (realtime == null) realtime = new RealtimeClient(api.baseUrl(), BuildConfig.SUPABASE_ANON_KEY, () -> requestSync("realtime"));
        realtime.start(token);
    }

    public void stop() {
        if (realtime != null) realtime.stop();
    }

    public String deviceId() {
        String id = db.syncStateDao().get("device_id");
        if (id == null) {
            id = UUID.randomUUID().toString();
            db.syncStateDao().put(new SyncStateEntity("device_id", id, System.currentTimeMillis()));
        }
        return id;
    }

    private void updateLastSync() {
        db.syncStateDao().put(new SyncStateEntity("last_sync", String.valueOf(System.currentTimeMillis()), System.currentTimeMillis()));
    }

    public long lastSync() {
        return parseLong(db.syncStateDao().get("last_sync"), 0);
    }

    public int pending() { try { return db.outboxDao().pendingCount(); } catch (Exception e) { return 0; } }
    public int conflicts() { try { return db.outboxDao().conflictCount(); } catch (Exception e) { return 0; } }

    private SyncStatus currentState(String message) {
        if (!auth.hasSession()) {
            return new SyncStatus(auth.isLocalOnly() ? SyncStatus.State.LOCAL_ONLY : SyncStatus.State.SIGNED_OUT,
                    message, pending(), conflicts(), lastSync(), false);
        }
        if (!isNetworkAvailable()) return new SyncStatus(SyncStatus.State.OFFLINE, "Offline — changes queued", pending(), conflicts(), lastSync(), false);
        return new SyncStatus(SyncStatus.State.SYNCED, message, pending(), conflicts(), lastSync(), realtime != null && realtime.isConnected());
    }

    private void publish(SyncStatus s) {
        status.postValue(s);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network active = cm.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void registerNetwork() {
        if (networkRegistered) return;
        networkRegistered = true;
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        try {
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    if (auth.hasSession()) requestSync("network available");
                }
                @Override public void onLost(Network network) {
                    publish(new SyncStatus(SyncStatus.State.OFFLINE, "Offline — changes queued", pending(), conflicts(), lastSync(), false));
                }
            });
        } catch (Exception ignored) { }
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); } catch (Exception e) { return fallback; }
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static class OfflineException extends Exception {
        OfflineException(String message) { super(message); }
    }
}
