package com.mkhdov.flux.data.repo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.JsonObject;
import com.mkhdov.flux.data.local.AppDatabase;
import com.mkhdov.flux.data.local.entity.AttachmentEntity;
import com.mkhdov.flux.data.local.entity.CalendarEntity;
import com.mkhdov.flux.data.local.entity.EventEntity;
import com.mkhdov.flux.data.local.entity.EventExceptionEntity;
import com.mkhdov.flux.data.local.entity.OutboxEntity;
import com.mkhdov.flux.data.local.entity.ReminderEntity;
import com.mkhdov.flux.data.local.entity.SyncStateEntity;
import com.mkhdov.flux.data.model.DaySnapshot;
import com.mkhdov.flux.data.model.EventInstance;
import com.mkhdov.flux.data.remote.RemoteMapper;
import com.mkhdov.flux.notifications.NotificationScheduler;
import com.mkhdov.flux.recurrence.RecurrenceEngine;
import com.mkhdov.flux.sync.SyncEngine;
import com.mkhdov.flux.util.DataBus;
import com.mkhdov.flux.util.EventPalette;
import com.mkhdov.flux.util.RRuleUtils;
import com.mkhdov.flux.util.TimeUtils;
import com.mkhdov.flux.widget.WidgetUpdateManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CalendarRepository {
    private static final String TAG = "CalendarRepository";
    private static volatile CalendarRepository INSTANCE;

    public enum EditScope { THIS, FUTURE, ALL }
    public enum DeleteScope { THIS, FUTURE, ALL }

    public interface Callback<T> {
        void onSuccess(T value);
        void onError(String message);
    }

    private final Context context;
    private final AppDatabase db;
    private final AuthManager auth;
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Runnable> undoStack = new ArrayDeque<>();

    public static CalendarRepository get(Context context) {
        if (INSTANCE == null) {
            synchronized (CalendarRepository.class) {
                if (INSTANCE == null) INSTANCE = new CalendarRepository(context.getApplicationContext());
            }
        }
        return INSTANCE;
    }

    private CalendarRepository(Context context) {
        this.context = context;
        this.db = AppDatabase.get(context);
        this.auth = AuthManager.get(context);
    }

    public AppDatabase db() { return db; }
    public ExecutorService executor() { return io; }
    public Handler mainHandler() { return main; }
    public AuthManager auth() { return auth; }

    public void seedIfNeeded() {
        io.execute(() -> {
            try {
                if (db.calendarDao().countActive() > 0) return;
                String owner = ownerId();
                long now = System.currentTimeMillis();
                CalendarEntity personal = new CalendarEntity();
                personal.id = UUID.randomUUID().toString();
                personal.ownerId = owner;
                personal.name = "Personal";
                personal.color = "#4285F4";
                personal.visible = true;
                personal.isDefault = true;
                personal.timezone = ZoneId.systemDefault().getId();
                personal.defaultReminderMinutes = 0;
                personal.createdAt = now;
                personal.updatedAt = now;
                CalendarEntity work = new CalendarEntity();
                work.id = UUID.randomUUID().toString();
                work.ownerId = owner;
                work.name = "Work";
                work.color = "#0B8043";
                work.visible = true;
                work.isDefault = false;
                work.timezone = ZoneId.systemDefault().getId();
                work.defaultReminderMinutes = 10;
                work.createdAt = now;
                work.updatedAt = now;
                db.runInTransaction(() -> {
                    db.calendarDao().clearDefaults();
                    db.calendarDao().upsert(personal);
                    db.calendarDao().upsert(work);
                    enqueueLocked("calendars", personal.id, "UPSERT", RemoteMapper.calendar(personal), personal.revision);
                    enqueueLocked("calendars", work.id, "UPSERT", RemoteMapper.calendar(work), work.revision);
                });
                notifyLocalChange();
            } catch (Exception e) {
                Log.e(TAG, "Unable to seed calendars", e);
            }
        });
    }

    public void claimLocalDataIfNeeded() {
        if (!auth.hasSession() || auth.userId() == null) return;
        io.execute(() -> {
            try {
                String owner = auth.userId();
                if (db.calendarDao().countActive() == 0) return;
                boolean hasLocal = false;
                for (CalendarEntity c : db.calendarDao().getAllSync()) {
                    if ("local".equals(c.ownerId)) { hasLocal = true; break; }
                }
                if (!hasLocal) return;
                List<AttachmentEntity> localAttachments = new ArrayList<>();
                for (AttachmentEntity a : db.attachmentDao().getAllActiveSync()) {
                    if ("local".equals(a.ownerId)) localAttachments.add(a);
                }
                db.runInTransaction(() -> {
                    db.calendarDao().claimLocalCalendars(owner);
                    db.eventDao().claimLocalEvents(owner);
                    db.reminderDao().claimLocalReminders(owner);
                    db.exceptionDao().claimLocalExceptions(owner);
                    db.attachmentDao().claimLocalAttachments(owner);
                    for (CalendarEntity c : db.calendarDao().getAllSync()) {
                        enqueueLocked("calendars", c.id, "UPSERT", RemoteMapper.calendar(c), c.revision);
                    }
                    for (EventEntity e : db.eventDao().getAllActiveSync()) {
                        enqueueLocked("events", e.id, "UPSERT", RemoteMapper.event(e), e.revision);
                    }
                    for (ReminderEntity r : db.reminderDao().getAllActiveSync()) {
                        enqueueLocked("event_reminders", r.id, "UPSERT", RemoteMapper.reminder(r), r.revision);
                    }
                    for (AttachmentEntity a : db.attachmentDao().getAllActiveSync()) {
                        enqueueLocked("event_attachments", a.id, "UPSERT", RemoteMapper.attachment(a), a.revision);
                    }
                    for (EventEntity e : db.eventDao().getAllActiveSync()) {
                        for (EventExceptionEntity x : db.exceptionDao().getForEventSync(e.id)) {
                            enqueueLocked("event_exceptions", x.id, "UPSERT", RemoteMapper.exception(x), x.revision);
                        }
                    }
                    db.syncStateDao().put(new SyncStateEntity("cursor", "0", System.currentTimeMillis()));
                });
                for (AttachmentEntity a : localAttachments) {
                    a.uploadStatus = AttachmentEntity.STATUS_QUEUED;
                    a.updatedAt = System.currentTimeMillis();
                    db.attachmentDao().upsert(a);
                    com.mkhdov.flux.attachments.AttachmentManager.get(context).enqueueUpload(a.id);
                }
                notifyLocalChange();
                SyncEngine.get(context).requestSync("account linked");
            } catch (Exception e) {
                Log.e(TAG, "Unable to claim local data", e);
            }
        });
    }

    public String ownerId() {
        if (auth.hasSession() && auth.userId() != null) return auth.userId();
        return "local";
    }

    // ---------------- calendars ----------------

    public void loadCalendars(Callback<List<CalendarEntity>> callback) {
        io.execute(() -> {
            try {
                List<CalendarEntity> list = db.calendarDao().getAllSync();
                main.post(() -> callback.onSuccess(list));
            } catch (Exception e) {
                fail(callback, e);
            }
        });
    }

    public List<CalendarEntity> loadCalendarsSync() { return db.calendarDao().getAllSync(); }

    public void saveCalendar(CalendarEntity calendar, Callback<CalendarEntity> callback) {
        io.execute(() -> {
            try {
                long now = System.currentTimeMillis();
                if (calendar.id == null) calendar.id = UUID.randomUUID().toString();
                calendar.ownerId = ownerId();
                if (calendar.timezone == null) calendar.timezone = ZoneId.systemDefault().getId();
                calendar.color = EventPalette.normalize(calendar.color);
                if (calendar.name == null || calendar.name.trim().isEmpty()) calendar.name = "Calendar";
                calendar.updatedAt = now;
                if (calendar.createdAt == 0) calendar.createdAt = now;
                CalendarEntity before = db.calendarDao().getByIdSync(calendar.id);
                if (before != null) {
                    calendar.revision = before.revision;
                } else if (!calendar.archived && db.calendarDao().countActive() == 0) {
                    calendar.isDefault = true;
                }
                final CalendarEntity saved = calendar;
                db.runInTransaction(() -> {
                    if (saved.isDefault) {
                        db.calendarDao().clearDefaultsForOwner(saved.ownerId);
                    }
                    db.calendarDao().upsert(saved);
                    enqueueLocked("calendars", saved.id, "UPSERT", RemoteMapper.calendar(saved), saved.revision);
                });
                if (saved.isDefault) ensureDefaultExists();
                notifyLocalChange();
                main.post(() -> callback.onSuccess(saved));
            } catch (Exception e) {
                fail(callback, e);
            }
        });
    }

    public void setDefaultCalendar(String calendarId) {
        io.execute(() -> {
            try {
                CalendarEntity c = db.calendarDao().getByIdSync(calendarId);
                if (c == null) return;
                db.runInTransaction(() -> {
                    db.calendarDao().clearDefaultsForOwner(c.ownerId);
                    c.isDefault = true;
                    c.updatedAt = System.currentTimeMillis();
                    db.calendarDao().upsert(c);
                    enqueueLocked("calendars", c.id, "UPSERT", RemoteMapper.calendar(c), c.revision);
                });
                notifyLocalChange();
            } catch (Exception e) { Log.e(TAG, "setDefault", e); }
        });
    }

    public void setCalendarVisible(String calendarId, boolean visible) {
        io.execute(() -> {
            try {
                CalendarEntity c = db.calendarDao().getByIdSync(calendarId);
                if (c == null) return;
                c.visible = visible;
                c.updatedAt = System.currentTimeMillis();
                db.calendarDao().upsert(c);
                enqueue("calendars", c.id, "UPSERT", RemoteMapper.calendar(c), c.revision);
                notifyLocalChange();
            } catch (Exception e) { Log.e(TAG, "setVisible", e); }
        });
    }

    public void setCalendarArchived(String calendarId, boolean archived) {
        io.execute(() -> {
            try {
                CalendarEntity c = db.calendarDao().getByIdSync(calendarId);
                if (c == null) return;
                c.archived = archived;
                c.updatedAt = System.currentTimeMillis();
                db.calendarDao().upsert(c);
                enqueue("calendars", c.id, "UPSERT", RemoteMapper.calendar(c), c.revision);
                notifyLocalChange();
            } catch (Exception e) { Log.e(TAG, "setArchived", e); }
        });
    }

    public void deleteCalendar(String calendarId, boolean deleteEvents, String moveToCalendarId, Callback<Void> callback) {
        io.execute(() -> {
            try {
                CalendarEntity calendar = db.calendarDao().getByIdSync(calendarId);
                if (calendar == null) { main.post(() -> callback.onSuccess(null)); return; }
                long now = System.currentTimeMillis();
                db.runInTransaction(() -> {
                    List<EventEntity> events = db.eventDao().getByCalendarSync(calendarId);
                    if (!deleteEvents && moveToCalendarId != null) {
                        db.eventDao().moveAllToCalendar(calendarId, moveToCalendarId);
                        for (EventEntity event : events) {
                            EventEntity moved = db.eventDao().getByIdSync(event.id);
                            enqueueLocked("events", moved.id, "UPSERT", RemoteMapper.event(moved), moved.revision);
                        }
                    } else {
                        for (EventEntity event : events) {
                            softDeleteEventLocked(event, now);
                        }
                    }
                    calendar.deletedAt = now;
                    calendar.updatedAt = now;
                    calendar.isDefault = false;
                    db.calendarDao().upsert(calendar);
                    enqueueLocked("calendars", calendar.id, "DELETE", RemoteMapper.calendar(calendar), calendar.revision);
                });
                ensureDefaultExists();
                notifyLocalChange();
                main.post(() -> callback.onSuccess(null));
            } catch (Exception e) {
                fail(callback, e);
            }
        });
    }

    private void ensureDefaultExists() {
        CalendarEntity def = db.calendarDao().getDefaultSync();
        if (def != null) return;
        for (CalendarEntity c : db.calendarDao().getAllSync()) {
            if (!c.archived) {
                c.isDefault = true;
                c.updatedAt = System.currentTimeMillis();
                db.calendarDao().upsert(c);
                enqueue("calendars", c.id, "UPSERT", RemoteMapper.calendar(c), c.revision);
                return;
            }
        }
    }

    // ---------------- queries ----------------

    public void loadEvent(String eventId, Callback<EventEntity> callback) {
        io.execute(() -> {
            try {
                EventEntity event = db.eventDao().getByIdSync(eventId);
                main.post(() -> callback.onSuccess(event));
            } catch (Exception e) { fail(callback, e); }
        });
    }

    public void loadReminders(String eventId, Callback<List<ReminderEntity>> callback) {
        io.execute(() -> {
            try {
                List<ReminderEntity> list = db.reminderDao().getForEventSync(eventId);
                main.post(() -> callback.onSuccess(list));
            } catch (Exception e) { fail(callback, e); }
        });
    }

    public void loadAttachments(String eventId, Callback<List<AttachmentEntity>> callback) {
        io.execute(() -> {
            try {
                List<AttachmentEntity> list = db.attachmentDao().getForEventSync(eventId);
                main.post(() -> callback.onSuccess(list));
            } catch (Exception e) { fail(callback, e); }
        });
    }

    public void loadException(String eventId, long occurrenceStart, Callback<EventExceptionEntity> callback) {
        io.execute(() -> {
            try {
                EventExceptionEntity ex = db.exceptionDao().getOccurrenceSync(eventId, occurrenceStart);
                main.post(() -> callback.onSuccess(ex));
            } catch (Exception e) { fail(callback, e); }
        });
    }

    public void loadSnapshot(LocalDate from, LocalDate to, Callback<DaySnapshot> callback) {
        io.execute(() -> {
            try {
                DaySnapshot snapshot = buildSnapshot(from, to);
                main.post(() -> callback.onSuccess(snapshot));
            } catch (Exception e) { fail(callback, e); }
        });
    }

    public DaySnapshot buildSnapshot(LocalDate from, LocalDate to) {
        DaySnapshot snapshot = new DaySnapshot();
        snapshot.generatedAt = System.currentTimeMillis();
        Set<String> visible = new HashSet<>();
        for (CalendarEntity c : db.calendarDao().getAllSync()) {
            snapshot.calendars.put(c.id, c);
            if (c.visible && !c.archived && c.deletedAt == 0) visible.add(c.id);
        }
        ZoneId systemZone = ZoneId.systemDefault();
        long rangeStart = from.atStartOfDay(systemZone).toInstant().toEpochMilli();
        long rangeEnd = to.plusDays(1).atStartOfDay(systemZone).toInstant().toEpochMilli();
        List<EventEntity> events = db.eventDao().getForRangeSync(rangeStart, rangeEnd);
        List<String> ids = new ArrayList<>();
        for (EventEntity e : events) ids.add(e.id);
        List<EventExceptionEntity> exceptions = ids.isEmpty() ? Collections.emptyList() : db.exceptionDao().getForEventsSync(ids);
        List<EventInstance> instances = RecurrenceEngine.expand(events, exceptions, rangeStart, rangeEnd);
        for (EventInstance instance : instances) {
            if (!visible.contains(instance.source.calendarId)) continue;
            ZoneId zone = TimeUtils.zoneOf(instance.source);
            LocalDate startDate = TimeUtils.dateOf(instance.startAt, zone);
            long lastMoment = Math.max(instance.startAt, instance.endAt - 1);
            LocalDate endDate = TimeUtils.dateOf(lastMoment, zone);
            if (endDate.isBefore(startDate)) endDate = startDate;
            LocalDate cursor = startDate;
            int guard = 0;
            while (!cursor.isAfter(endDate) && guard++ < 370) {
                if (!cursor.isBefore(from) && !cursor.isAfter(to)) snapshot.put(instance, cursor);
                cursor = cursor.plusDays(1);
            }
        }
        for (List<EventInstance> list : snapshot.byDay.values()) {
            Collections.sort(list, (a, b) -> {
                if (a.source.allDay != b.source.allDay) return a.source.allDay ? -1 : 1;
                int c = Long.compare(a.startAt, b.startAt);
                if (c != 0) return c;
                return String.valueOf(a.source.title).compareToIgnoreCase(String.valueOf(b.source.title));
            });
        }
        return snapshot;
    }

    public List<EventInstance> upcomingInstances(LocalDate from, int days) {
        DaySnapshot snapshot = buildSnapshot(from, from.plusDays(Math.max(1, days) - 1));
        List<EventInstance> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        LocalDate cursor = from;
        for (int i = 0; i < days; i++) {
            for (EventInstance instance : snapshot.eventsFor(cursor)) {
                if (seen.add(instance.instanceId)) all.add(instance);
            }
            cursor = cursor.plusDays(1);
        }
        Collections.sort(all, (a, b) -> Long.compare(a.startAt, b.startAt));
        return all;
    }

    // ---------------- quick add ----------------

    public void quickAdd(String title, long startAt, long endAt, Callback<EventEntity> callback) {
        io.execute(() -> {
            try {
                CalendarEntity calendar = db.calendarDao().getDefaultSync();
                if (calendar == null) {
                    List<CalendarEntity> visible = db.calendarDao().getVisibleSync();
                    calendar = visible.isEmpty() ? null : visible.get(0);
                }
                if (calendar == null) {
                    main.post(() -> callback.onError("Create a calendar first"));
                    return;
                }
                EventEntity event = new EventEntity();
                event.title = title == null || title.trim().isEmpty() ? "New event" : title.trim();
                event.calendarId = calendar.id;
                event.ownerId = calendar.ownerId;
                event.startAt = startAt;
                event.endAt = Math.max(startAt + 60000L, endAt);
                event.timezone = calendar.timezone;
                event.color = calendar.color;
                event.allDay = false;
                List<ReminderEntity> reminders = new ArrayList<>();
                if (calendar.defaultReminderMinutes >= 0) {
                    ReminderEntity r = new ReminderEntity();
                    r.minutesBefore = Math.max(0, calendar.defaultReminderMinutes);
                    reminders.add(r);
                }
                saveEdited(null, 0, event, reminders, EditScope.ALL, callback);
            } catch (Exception e) {
                fail(callback, e);
            }
        });
    }

    // ---------------- create and edit ----------------

    public void saveEdited(EventEntity base, long occurrenceStart, EventEntity edited,
                           List<ReminderEntity> editedReminders, EditScope scope,
                           Callback<EventEntity> callback) {
        io.execute(() -> {
            try {
                if (edited.title == null || edited.title.trim().isEmpty()) edited.title = "Untitled";
                if (edited.endAt < edited.startAt) edited.endAt = edited.startAt + 3600000L;
                if (edited.timezone == null || edited.timezone.isEmpty()) edited.timezone = ZoneId.systemDefault().getId();
                if (edited.color == null || edited.color.isEmpty()) {
                    CalendarEntity cal = db.calendarDao().getByIdSync(edited.calendarId);
                    edited.color = cal == null ? "#4285F4" : cal.color;
                }
                EventEntity saved;
                if (base == null) {
                    saved = createBase(edited, editedReminders);
                } else if (!base.isRecurring() || scope == EditScope.ALL) {
                    captureEventUndo(base.id);
                    saved = updateBase(base, edited, editedReminders);
                } else if (base.isRecurring() && scope == EditScope.FUTURE
                        && (occurrenceStart <= 0 || occurrenceStart <= base.startAt)) {
                    captureEventUndo(base.id);
                    saved = updateBase(base, edited, editedReminders);
                } else if (scope == EditScope.THIS) {
                    captureEventUndo(base.id);
                    saved = createOrUpdateOverride(base, occurrenceStart, edited, editedReminders);
                } else {
                    captureEventUndo(base.id);
                    saved = splitSeries(base, occurrenceStart, edited, editedReminders);
                }
                notifyLocalChange();
                EventEntity result = saved;
                main.post(() -> callback.onSuccess(result));
            } catch (Exception e) {
                fail(callback, e);
            }
        });
    }

    private EventEntity createBase(EventEntity edited, List<ReminderEntity> reminders) {
        long now = System.currentTimeMillis();
        EventEntity event = edited.copy();
        event.id = edited.id == null ? UUID.randomUUID().toString() : edited.id;
        event.ownerId = resolveOwner(edited);
        event.createdAt = now;
        event.updatedAt = now;
        event.deletedAt = 0;
        event.revision = 0;
        if (event.isRecurring()) event.seriesId = event.id;
        if (RRuleUtils.isValid(event.rrule)) event.rrule = RRuleUtils.normalize(event.rrule);
        db.runInTransaction(() -> {
            db.eventDao().upsert(event);
            replaceRemindersLocked(event, reminders, true);
            enqueueLocked("events", event.id, "UPSERT", RemoteMapper.event(event), event.revision);
        });
        return event;
    }

    private EventEntity updateBase(EventEntity base, EventEntity edited, List<ReminderEntity> reminders) {
        EventEntity before = base.copy();
        long now = System.currentTimeMillis();
        EventEntity event = edited.copy();
        event.id = base.id;
        event.ownerId = base.ownerId;
        event.createdAt = base.createdAt;
        event.updatedAt = now;
        event.deletedAt = 0;
        event.revision = base.revision;
        if (event.isRecurring()) {
            if (RRuleUtils.isValid(event.rrule)) event.rrule = RRuleUtils.normalize(event.rrule);
            event.seriesId = base.seriesId != null ? base.seriesId : base.id;
        } else {
            event.seriesId = null;
        }
        final EventEntity saved = event;
        final boolean clearOverrides = base.isRecurring() && (!event.isRecurring()
                || (base.rrule != null && !base.rrule.equals(event.rrule)));
        db.runInTransaction(() -> {
            if (clearOverrides) {
                softDeleteOverridesLocked(base.id, now);
            }
            db.eventDao().upsert(saved);
            replaceRemindersLocked(saved, reminders, true);
            enqueueLocked("events", saved.id, "UPSERT", RemoteMapper.event(saved), saved.revision);
        });
        return saved;
    }

    private EventEntity createOrUpdateOverride(EventEntity base, long occurrenceStart, EventEntity edited,
                                               List<ReminderEntity> reminders) {
        long now = System.currentTimeMillis();
        EventExceptionEntity exception = db.exceptionDao().getOccurrenceSync(base.id, occurrenceStart);
        EventEntity override = null;
        if (exception != null && exception.overrideEventId != null) {
            override = db.eventDao().getByIdSync(exception.overrideEventId);
        }
        if (override == null) {
            override = edited.copy();
            override.id = UUID.randomUUID().toString();
            override.createdAt = now;
            override.revision = 0;
        }
        override.ownerId = base.ownerId;
        override.calendarId = edited.calendarId;
        override.title = edited.title;
        override.description = edited.description;
        override.location = edited.location;
        override.startAt = edited.startAt;
        override.endAt = edited.endAt;
        override.allDay = edited.allDay;
        override.timezone = edited.timezone;
        override.color = edited.color;
        override.completed = edited.completed;
        override.rrule = null;
        override.seriesId = base.seriesId != null ? base.seriesId : base.id;
        override.recurrenceParentId = base.id;
        override.originalStart = occurrenceStart;
        override.updatedAt = now;
        override.deletedAt = 0;
        if (override.createdAt == 0) override.createdAt = now;
        if (exception == null) {
            exception = new EventExceptionEntity();
            exception.id = UUID.randomUUID().toString();
            exception.ownerId = base.ownerId;
            exception.eventId = base.id;
            exception.occurrenceStart = occurrenceStart;
            exception.createdAt = now;
            exception.revision = 0;
        }
        exception.cancelled = false;
        exception.overrideEventId = override.id;
        exception.updatedAt = now;
        exception.deletedAt = 0;
        final EventEntity savedOverride = override;
        final EventExceptionEntity savedException = exception;
        db.runInTransaction(() -> {
            db.eventDao().upsert(savedOverride);
            db.exceptionDao().upsert(savedException);
            if (reminders == null) {
                List<ReminderEntity> baseReminders = db.reminderDao().getForEventSync(base.id);
                replaceRemindersLocked(savedOverride, cloneReminders(baseReminders, savedOverride), true);
            } else {
                replaceRemindersLocked(savedOverride, reminders, true);
            }
            enqueueLocked("events", savedOverride.id, "UPSERT", RemoteMapper.event(savedOverride), savedOverride.revision);
            enqueueLocked("event_exceptions", savedException.id, "UPSERT", RemoteMapper.exception(savedException), savedException.revision);
        });
        return savedOverride;
    }

    private EventEntity splitSeries(EventEntity base, long occurrenceStart, EventEntity edited,
                                    List<ReminderEntity> reminders) {
        long now = System.currentTimeMillis();
        long until = occurrenceStart - 1000L;
        EventEntity old = base.copy();
        old.rrule = RRuleUtils.limitUntil(base.rrule, until);
        old.updatedAt = now;
        EventEntity future = edited.copy();
        future.id = UUID.randomUUID().toString();
        future.ownerId = base.ownerId;
        future.createdAt = now;
        future.updatedAt = now;
        future.deletedAt = 0;
        future.revision = 0;
        future.seriesId = future.id;
        future.recurrenceParentId = null;
        future.originalStart = 0;
        if (future.rrule == null || future.rrule.isEmpty()) {
            future.rrule = base.rrule;
        }
        if (RRuleUtils.isValid(future.rrule)) future.rrule = RRuleUtils.normalize(future.rrule);
        else future.rrule = null;
        final EventEntity savedOld = old;
        final EventEntity savedFuture = future;
        List<ReminderEntity> baseReminders = db.reminderDao().getForEventSync(base.id);
        db.runInTransaction(() -> {
            db.eventDao().upsert(savedOld);
            enqueueLocked("events", savedOld.id, "UPSERT", RemoteMapper.event(savedOld), savedOld.revision);
            db.eventDao().upsert(savedFuture);
            replaceRemindersLocked(savedFuture, reminders == null
                    ? cloneReminders(baseReminders, savedFuture) : reminders, true);
            enqueueLocked("events", savedFuture.id, "UPSERT", RemoteMapper.event(savedFuture), savedFuture.revision);
            for (EventExceptionEntity ex : db.exceptionDao().getForEventSync(base.id)) {
                if (ex.occurrenceStart >= occurrenceStart) {
                    ex.eventId = savedFuture.id;
                    ex.ownerId = savedFuture.ownerId;
                    ex.updatedAt = now;
                    db.exceptionDao().upsert(ex);
                    enqueueLocked("event_exceptions", ex.id, "UPSERT", RemoteMapper.exception(ex), ex.revision);
                }
            }
            for (EventEntity override : db.eventDao().getOverridesForParent(base.id)) {
                if (override.originalStart >= occurrenceStart) {
                    override.recurrenceParentId = savedFuture.id;
                    override.seriesId = savedFuture.id;
                    override.updatedAt = now;
                    db.eventDao().upsert(override);
                    enqueueLocked("events", override.id, "UPSERT", RemoteMapper.event(override), override.revision);
                }
            }
        });
        return savedFuture;
    }

    private void replaceRemindersLocked(EventEntity event, List<ReminderEntity> requested, boolean enqueue) {
        long now = System.currentTimeMillis();
        List<ReminderEntity> old = db.reminderDao().getAllForEventSync(event.id);
        Map<String, ReminderEntity> oldById = new HashMap<>();
        for (ReminderEntity r : old) oldById.put(r.id, r);
        Set<String> keep = new HashSet<>();
        if (requested != null) {
            for (ReminderEntity r : requested) {
                ReminderEntity reminder;
                if (r.id != null && oldById.containsKey(r.id)) {
                    reminder = oldById.get(r.id);
                    reminder.minutesBefore = Math.max(0, r.minutesBefore);
                    reminder.ownerId = event.ownerId;
                    reminder.eventId = event.id;
                    reminder.deletedAt = 0;
                    reminder.updatedAt = now;
                } else {
                    reminder = new ReminderEntity();
                    reminder.id = UUID.randomUUID().toString();
                    reminder.ownerId = event.ownerId;
                    reminder.eventId = event.id;
                    reminder.minutesBefore = Math.max(0, r.minutesBefore);
                    reminder.channel = "local";
                    reminder.createdAt = now;
                    reminder.updatedAt = now;
                    reminder.revision = 0;
                }
                keep.add(reminder.id);
                db.reminderDao().upsert(reminder);
                if (enqueue) enqueueLocked("event_reminders", reminder.id, "UPSERT", RemoteMapper.reminder(reminder), reminder.revision);
            }
        }
        for (ReminderEntity r : old) {
            if (r.deletedAt == 0 && !keep.contains(r.id)) {
                r.deletedAt = now;
                r.updatedAt = now;
                db.reminderDao().upsert(r);
                if (enqueue) enqueueLocked("event_reminders", r.id, "DELETE", RemoteMapper.reminder(r), r.revision);
            }
        }
    }

    private List<ReminderEntity> cloneReminders(List<ReminderEntity> original, EventEntity target) {
        List<ReminderEntity> out = new ArrayList<>();
        for (ReminderEntity r : original) {
            ReminderEntity copy = new ReminderEntity();
            copy.ownerId = target.ownerId;
            copy.eventId = target.id;
            copy.minutesBefore = r.minutesBefore;
            copy.channel = "local";
            copy.id = UUID.randomUUID().toString();
            copy.createdAt = System.currentTimeMillis();
            copy.updatedAt = copy.createdAt;
            out.add(copy);
        }
        return out;
    }

    // ---------------- dragging and resizing ----------------

    public void moveOccurrence(EventInstance instance, long newStart, long newEnd, Callback<EventEntity> callback) {
        io.execute(() -> {
            try {
                EventEntity base = instance.source;
                EventEntity edited = base.copy();
                EventEntity result;
                if (instance.recurringOccurrence) {
                    EventExceptionEntity exception = db.exceptionDao().getOccurrenceSync(base.id, instance.occurrenceStart);
                    if (exception != null && exception.overrideEventId != null) {
                        EventEntity override = db.eventDao().getByIdSync(exception.overrideEventId);
                        if (override != null) edited = override.copy();
                    }
                    captureEventUndo(edited.id);
                    edited.startAt = newStart;
                    edited.endAt = newEnd;
                    basedOn(base, edited);
                    List<ReminderEntity> reminders = db.reminderDao().getForEventSync(base.id);
                    result = createOrUpdateOverride(base, instance.occurrenceStart, edited, cloneReminders(reminders, edited));
                } else {
                    captureEventUndo(base.id);
                    EventEntity event = base.copy();
                    event.startAt = newStart;
                    event.endAt = newEnd;
                    event.updatedAt = System.currentTimeMillis();
                    db.eventDao().upsert(event);
                    enqueue("events", event.id, "UPSERT", RemoteMapper.event(event), event.revision);
                    result = event;
                }
                notifyLocalChange();
                final EventEntity callbackResult = result;
                main.post(() -> callback.onSuccess(callbackResult));
            } catch (Exception e) { fail(callback, e); }
        });
    }

    private void basedOn(EventEntity base, EventEntity edited) {
        edited.calendarId = base.calendarId;
        edited.color = base.color;
        edited.timezone = base.timezone;
        edited.allDay = base.allDay;
        if (edited.title == null) edited.title = base.title;
    }

    public void toggleCompleted(String eventId, boolean completed) {
        io.execute(() -> {
            try {
                EventEntity e = db.eventDao().getByIdSync(eventId);
                if (e == null) return;
                e.completed = completed;
                e.updatedAt = System.currentTimeMillis();
                db.eventDao().upsert(e);
                enqueue("events", e.id, "UPSERT", RemoteMapper.event(e), e.revision);
                notifyLocalChange();
            } catch (Exception ex) { Log.e(TAG, "toggleCompleted", ex); }
        });
    }

    public void moveEventToCalendar(String eventId, String calendarId) {
        io.execute(() -> {
            try {
                EventEntity e = db.eventDao().getByIdSync(eventId);
                CalendarEntity cal = db.calendarDao().getByIdSync(calendarId);
                if (e == null || cal == null) return;
                e.calendarId = calendarId;
                if (e.color == null || e.color.isEmpty()) e.color = cal.color;
                e.updatedAt = System.currentTimeMillis();
                db.eventDao().upsert(e);
                enqueue("events", e.id, "UPSERT", RemoteMapper.event(e), e.revision);
                notifyLocalChange();
            } catch (Exception ex) { Log.e(TAG, "moveCalendar", ex); }
        });
    }

    // ---------------- deletion ----------------

    public void deleteEvent(EventEntity event, long occurrenceStart, DeleteScope scope, Callback<Void> call) {
        io.execute(() -> {
            try {
                captureDeleteUndo(event, occurrenceStart, scope);
                long now = System.currentTimeMillis();
                if (scope == DeleteScope.THIS && event.isRecurring() && occurrenceStart > 0) {
                    db.runInTransaction(() -> excludeOccurrenceLocked(event, occurrenceStart, now));
                } else if (scope == DeleteScope.FUTURE && event.isRecurring() && occurrenceStart > event.startAt) {
                    EventEntity old = event.copy();
                    old.rrule = RRuleUtils.limitUntil(event.rrule, occurrenceStart - 1000L);
                    old.updatedAt = now;
                    db.runInTransaction(() -> {
                        db.eventDao().upsert(old);
                        enqueueLocked("events", old.id, "UPSERT", RemoteMapper.event(old), old.revision);
                        for (EventEntity override : db.eventDao().getOverridesForParent(event.id)) {
                            if (override.originalStart >= occurrenceStart) softDeleteEventLocked(override, now);
                        }
                        for (EventExceptionEntity ex : db.exceptionDao().getForEventSync(event.id)) {
                            if (ex.occurrenceStart >= occurrenceStart) {
                                ex.deletedAt = now; ex.updatedAt = now;
                                db.exceptionDao().upsert(ex);
                                enqueueLocked("event_exceptions", ex.id, "DELETE", RemoteMapper.exception(ex), ex.revision);
                            }
                        }
                    });
                } else {
                    db.runInTransaction(() -> softDeleteEventLocked(event, now));
                }
                notifyLocalChange();
                main.post(() -> call.onSuccess(null));
            } catch (Exception e) { fail(call, e); }
        });
    }

    private void excludeOccurrenceLocked(EventEntity base, long occurrenceStart, long now) {
        EventExceptionEntity exception = db.exceptionDao().getOccurrenceSync(base.id, occurrenceStart);
        if (exception != null && exception.overrideEventId != null) {
            EventEntity override = db.eventDao().getByIdSync(exception.overrideEventId);
            if (override != null) softDeleteEventLocked(override, now);
        }
        if (exception == null) {
            exception = new EventExceptionEntity();
            exception.id = UUID.randomUUID().toString();
            exception.ownerId = base.ownerId;
            exception.eventId = base.id;
            exception.occurrenceStart = occurrenceStart;
            exception.createdAt = now;
            exception.revision = 0;
        }
        exception.cancelled = true;
        exception.overrideEventId = null;
        exception.updatedAt = now;
        exception.deletedAt = 0;
        db.exceptionDao().upsert(exception);
        enqueueLocked("event_exceptions", exception.id, "UPSERT", RemoteMapper.exception(exception), exception.revision);
    }

    private void softDeleteEventLocked(EventEntity event, long now) {
        EventEntity e = event.copy();
        e.deletedAt = now;
        e.updatedAt = now;
        db.eventDao().upsert(e);
        enqueueLocked("events", e.id, "DELETE", RemoteMapper.event(e), e.revision);
        for (ReminderEntity r : db.reminderDao().getForEventSync(e.id)) {
            r.deletedAt = now; r.updatedAt = now;
            db.reminderDao().upsert(r);
            enqueueLocked("event_reminders", r.id, "DELETE", RemoteMapper.reminder(r), r.revision);
        }
        for (AttachmentEntity a : db.attachmentDao().getForEventSync(e.id)) {
            a.deletedAt = now; a.updatedAt = now;
            db.attachmentDao().upsert(a);
            enqueueLocked("event_attachments", a.id, "DELETE", RemoteMapper.attachment(a), a.revision);
        }
        for (EventExceptionEntity ex : db.exceptionDao().getForEventSync(e.id)) {
            ex.deletedAt = now; ex.updatedAt = now;
            db.exceptionDao().upsert(ex);
            enqueueLocked("event_exceptions", ex.id, "DELETE", RemoteMapper.exception(ex), ex.revision);
        }
        for (EventEntity override : db.eventDao().getOverridesForParent(e.id)) {
            if (override.deletedAt == 0) softDeleteEventLocked(override, now);
        }
    }

    private void softDeleteOverridesLocked(String parentId, long now) {
        for (EventEntity override : db.eventDao().getOverridesForParent(parentId)) {
            if (override.deletedAt == 0) softDeleteEventLocked(override, now);
        }
        for (EventExceptionEntity ex : db.exceptionDao().getForEventSync(parentId)) {
            ex.deletedAt = now; ex.updatedAt = now;
            db.exceptionDao().upsert(ex);
            enqueueLocked("event_exceptions", ex.id, "DELETE", RemoteMapper.exception(ex), ex.revision);
        }
    }

    // ---------------- undo ----------------

    public boolean canUndo() { synchronized (undoStack) { return !undoStack.isEmpty(); } }

    public void undoLast(Callback<Void> callback) {
        final Runnable action;
        synchronized (undoStack) { action = undoStack.pollLast(); }
        if (action == null) { main.post(() -> callback.onError("Nothing to undo")); return; }
        io.execute(() -> {
            try {
                action.run();
                notifyLocalChange();
                main.post(() -> callback.onSuccess(null));
            } catch (Exception e) { fail(callback, e); }
        });
    }

    private void captureEventUndo(String eventId) {
        EventEntity event = db.eventDao().getByIdSync(eventId);
        if (event == null) return;
        EventEntity copy = event.copy();
        List<ReminderEntity> reminders = new ArrayList<>();
        for (ReminderEntity r : db.reminderDao().getForEventSync(eventId)) {
            ReminderEntity rc = new ReminderEntity();
            rc.id = r.id; rc.ownerId = r.ownerId; rc.eventId = r.eventId; rc.minutesBefore = r.minutesBefore;
            rc.channel = r.channel; rc.createdAt = r.createdAt; rc.updatedAt = r.updatedAt;
            rc.deletedAt = r.deletedAt; rc.revision = r.revision;
            reminders.add(rc);
        }
        pushUndo(() -> {
            copy.updatedAt = System.currentTimeMillis();
            db.runInTransaction(() -> {
                db.eventDao().upsert(copy);
                enqueueLocked("events", copy.id, "UPSERT", RemoteMapper.event(copy), copy.revision);
                replaceRemindersLocked(copy, reminders, true);
            });
        });
    }

    private void captureDeleteUndo(EventEntity event, long occurrenceStart, DeleteScope scope) {
        EventEntity copy = event.copy();
        List<ReminderEntity> reminders = new ArrayList<>(db.reminderDao().getForEventSync(event.id));
        List<EventExceptionEntity> exceptions = new ArrayList<>(db.exceptionDao().getForEventSync(event.id));
        List<EventEntity> overrides = new ArrayList<>(db.eventDao().getOverridesForParent(event.id));
        pushUndo(() -> {
            long now = System.currentTimeMillis();
            copy.deletedAt = 0;
            copy.updatedAt = now;
            db.runInTransaction(() -> {
                db.eventDao().upsert(copy);
                enqueueLocked("events", copy.id, "UPSERT", RemoteMapper.event(copy), copy.revision);
                for (ReminderEntity r : reminders) {
                    r.deletedAt = 0; r.updatedAt = now;
                    db.reminderDao().upsert(r);
                    enqueueLocked("event_reminders", r.id, "UPSERT", RemoteMapper.reminder(r), r.revision);
                }
                for (EventExceptionEntity ex : exceptions) {
                    ex.deletedAt = 0; ex.updatedAt = now;
                    db.exceptionDao().upsert(ex);
                    enqueueLocked("event_exceptions", ex.id, "UPSERT", RemoteMapper.exception(ex), ex.revision);
                }
                for (EventEntity override : overrides) {
                    override.deletedAt = 0; override.updatedAt = now;
                    db.eventDao().upsert(override);
                    enqueueLocked("events", override.id, "UPSERT", RemoteMapper.event(override), override.revision);
                }
            });
        });
    }

    private void pushUndo(Runnable action) {
        synchronized (undoStack) {
            undoStack.push(action);
            while (undoStack.size() > 10) undoStack.removeLast();
        }
    }

    // ---------------- search ----------------

    public void search(String query, String calendarId, String color, long from, long to, Callback<List<EventEntity>> callback) {
        io.execute(() -> {
            try {
                String q = "%" + (query == null ? "" : query.trim()) + "%";
                List<EventEntity> result = db.eventDao().search(q, calendarId, color, from, to);
                main.post(() -> callback.onSuccess(result));
            } catch (Exception e) { fail(callback, e); }
        });
    }

    // ---------------- ICS ----------------

    public void exportIcs(OutputStream outputStream, Callback<Integer> callback) {
        io.execute(() -> {
            try {
                List<EventEntity> events = db.eventDao().getAllActiveSync();
                Map<String, CalendarEntity> calendars = new HashMap<>();
                for (CalendarEntity c : db.calendarDao().getAllSync()) calendars.put(c.id, c);
                OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
                writer.write("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Flux//Calendar//EN\r\nCALSCALE:GREGORIAN\r\n");
                DateTimeFormatter utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US);
                DateTimeFormatter date = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US);
                for (EventEntity e : events) {
                    CalendarEntity cal = calendars.get(e.calendarId);
                    ZoneId zone = TimeUtils.zoneOf(e);
                    writer.write("BEGIN:VEVENT\r\n");
                    writer.write("UID:" + e.id + "@flux\r\n");
                    writer.write("DTSTAMP:" + utc.format(Instant.now().atZone(ZoneOffset.UTC)) + "\r\n");
                    if (e.allDay) {
                        LocalDate start = TimeUtils.dateOf(e.startAt, zone);
                        LocalDate end = TimeUtils.dateOf(Math.max(e.startAt, e.endAt - 1), zone).plusDays(1);
                        writer.write("DTSTART;VALUE=DATE:" + date.format(start) + "\r\n");
                        writer.write("DTEND;VALUE=DATE:" + date.format(end) + "\r\n");
                    } else {
                        writer.write("DTSTART:" + utc.format(Instant.ofEpochMilli(e.startAt).atZone(ZoneOffset.UTC)) + "\r\n");
                        writer.write("DTEND:" + utc.format(Instant.ofEpochMilli(e.endAt).atZone(ZoneOffset.UTC)) + "\r\n");
                    }
                    writer.write("SUMMARY:" + escape(e.title) + "\r\n");
                    if (e.description != null) writer.write("DESCRIPTION:" + escape(e.description) + "\r\n");
                    if (e.location != null) writer.write("LOCATION:" + escape(e.location) + "\r\n");
                    if (cal != null) writer.write("CATEGORIES:" + escape(cal.name) + "\r\n");
                    if (e.color != null) writer.write("X-FLUX-COLOR:" + e.color + "\r\n");
                    if (e.rrule != null) writer.write("RRULE:" + RRuleUtils.stripPrefix(e.rrule) + "\r\n");
                    writer.write("END:VEVENT\r\n");
                }
                writer.write("END:VCALENDAR\r\n");
                writer.flush();
                int count = events.size();
                main.post(() -> callback.onSuccess(count));
            } catch (Exception e) { fail(callback, e); }
        });
    }

    public void importIcs(InputStream inputStream, String calendarId, Callback<Integer> callback) {
        io.execute(() -> {
            try {
                CalendarEntity calendar = calendarId == null ? db.calendarDao().getDefaultSync() : db.calendarDao().getByIdSync(calendarId);
                if (calendar == null) { main.post(() -> callback.onError("Create a calendar first")); return; }
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                List<String> lines = new ArrayList<>();
                String line;
                String previous = null;
                while ((line = reader.readLine()) != null) {
                    if ((line.startsWith(" ") || line.startsWith("\t")) && previous != null) {
                        lines.set(lines.size() - 1, previous + line.substring(1));
                        previous = lines.get(lines.size() - 1);
                    } else {
                        lines.add(line);
                        previous = line;
                    }
                }
                int imported = 0;
                Map<String, String> fields = new HashMap<>();
                boolean inEvent = false;
                for (String raw : lines) {
                    String l = raw.trim();
                    if (l.equalsIgnoreCase("BEGIN:VEVENT")) { inEvent = true; fields.clear(); continue; }
                    if (l.equalsIgnoreCase("END:VEVENT") && inEvent) {
                        EventEntity e = eventFromIcs(fields, calendar);
                        if (e != null) {
                            createBase(e, defaultReminderList(calendar));
                            imported++;
                        }
                        inEvent = false;
                        continue;
                    }
                    if (inEvent) {
                        int idx = l.indexOf(':');
                        if (idx > 0) {
                            String key = l.substring(0, idx);
                            String value = l.substring(idx + 1);
                            String baseKey = key.contains(";") ? key.substring(0, key.indexOf(';')) : key;
                            fields.put(baseKey.toUpperCase(Locale.US), value);
                            if (key.toUpperCase(Locale.US).startsWith("DTSTART;TZID")) {
                                fields.put("DTSTART_TZID", key.substring(key.indexOf('=') + 1));
                            }
                        }
                    }
                }
                int count = imported;
                notifyLocalChange();
                main.post(() -> callback.onSuccess(count));
            } catch (Exception e) { fail(callback, e); }
        });
    }

    private List<ReminderEntity> defaultReminderList(CalendarEntity calendar) {
        List<ReminderEntity> list = new ArrayList<>();
        if (calendar.defaultReminderMinutes >= 0) {
            ReminderEntity r = new ReminderEntity();
            r.minutesBefore = calendar.defaultReminderMinutes;
            list.add(r);
        }
        return list;
    }

    private EventEntity eventFromIcs(Map<String, String> fields, CalendarEntity calendar) {
        try {
            String summary = unescape(fields.get("SUMMARY"));
            if (summary == null) summary = "Imported event";
            String description = unescape(fields.get("DESCRIPTION"));
            String location = unescape(fields.get("LOCATION"));
            String dtStart = fields.get("DTSTART");
            String dtEnd = fields.get("DTEND");
            if (dtStart == null) return null;
            ZoneId zone = ZoneId.systemDefault();
            String tzid = fields.get("DTSTART_TZID");
            if (tzid != null) {
                try { zone = ZoneId.of(tzid); } catch (Exception ignored) { }
            }
            boolean allDay = dtStart.length() == 8;
            long start;
            long end;
            if (allDay) {
                LocalDate date = LocalDate.parse(dtStart, DateTimeFormatter.ofPattern("yyyyMMdd"));
                start = date.atStartOfDay(zone).toInstant().toEpochMilli();
                if (dtEnd != null && dtEnd.length() == 8) {
                    LocalDate endDate = LocalDate.parse(dtEnd, DateTimeFormatter.ofPattern("yyyyMMdd"));
                    end = endDate.atStartOfDay(zone).toInstant().toEpochMilli();
                } else end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            } else {
                start = parseIcsDateTime(dtStart, zone);
                end = dtEnd == null ? start + 3600000L : parseIcsDateTime(dtEnd, zone);
            }
            EventEntity e = new EventEntity();
            e.title = summary;
            e.description = description;
            e.location = location;
            e.startAt = start;
            e.endAt = Math.max(start, end);
            e.allDay = allDay;
            e.timezone = zone.getId();
            e.calendarId = calendar.id;
            e.ownerId = calendar.ownerId;
            e.color = calendar.color;
            e.rrule = RRuleUtils.normalize(fields.get("RRULE"));
            if (e.rrule != null && !RRuleUtils.isValid(e.rrule)) e.rrule = null;
            return e;
        } catch (Exception ex) { return null; }
    }

    private long parseIcsDateTime(String value, ZoneId zone) {
        if (value.endsWith("Z")) {
            LocalDateTime ldt = LocalDateTime.parse(value.substring(0, value.length() - 1), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
            return ldt.toInstant(ZoneOffset.UTC).toEpochMilli();
        }
        LocalDateTime ldt = LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
        return ldt.atZone(zone).toInstant().toEpochMilli();
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;");
    }

    private String unescape(String value) {
        if (value == null) return null;
        return value.replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
    }

    // ---------------- sync helpers ----------------

    public void enqueue(String table, String id, String operation, JsonObject payload, long baseRevision) {
        try {
            db.runInTransaction(() -> enqueueLocked(table, id, operation, payload, baseRevision));
        } catch (Exception e) { Log.e(TAG, "enqueue", e); }
    }

    public void enqueueLocked(String table, String id, String operation, JsonObject payload, long baseRevision) {
        if (!auth.hasSession()) return;
        OutboxEntity existing = db.outboxDao().getPendingForEntity(table, id);
        long base = existing != null ? existing.baseRevision : baseRevision;
        db.outboxDao().deletePendingForEntity(table, id);
        OutboxEntity op = new OutboxEntity();
        op.id = UUID.randomUUID().toString();
        op.entityTable = table;
        op.entityId = id;
        op.operation = operation;
        op.payload = payload.toString();
        op.baseRevision = base;
        op.createdAt = System.currentTimeMillis();
        op.nextAttemptAt = 0;
        db.outboxDao().insert(op);
    }

    public void notifyLocalChange() {
        DataBus.notifyChanged();
        NotificationScheduler.scheduleDebounced(context);
        WidgetUpdateManager.requestUpdate(context);
        try { SyncEngine.get(context).requestSync("local change"); } catch (Exception ignored) { }
    }

    public void forceSync() {
        SyncEngine.get(context).requestSync("manual");
    }

    private String resolveOwner(EventEntity event) {
        CalendarEntity cal = db.calendarDao().getByIdSync(event.calendarId);
        if (cal != null && cal.ownerId != null) return cal.ownerId;
        return ownerId();
    }

    private <T> void fail(Callback<T> callback, Exception e) {
        Log.e(TAG, "Operation failed", e);
        String message = e.getMessage() == null ? "Operation failed" : e.getMessage();
        main.post(() -> callback.onError(message));
    }
}
