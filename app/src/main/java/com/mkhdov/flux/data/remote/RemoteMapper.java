package com.mkhdov.flux.data.remote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mkhdov.flux.data.local.entity.AttachmentEntity;
import com.mkhdov.flux.data.local.entity.CalendarEntity;
import com.mkhdov.flux.data.local.entity.EventEntity;
import com.mkhdov.flux.data.local.entity.EventExceptionEntity;
import com.mkhdov.flux.data.local.entity.ReminderEntity;
import com.mkhdov.flux.util.TimeUtils;

public final class RemoteMapper {
    public static final String T_CALENDARS = "calendars";
    public static final String T_EVENTS = "events";
    public static final String T_REMINDERS = "event_reminders";
    public static final String T_EXCEPTIONS = "event_exceptions";
    public static final String T_ATTACHMENTS = "event_attachments";

    private RemoteMapper() { }

    public static String tableFor(String entityType) {
        switch (entityType) {
            case "calendar": return T_CALENDARS;
            case "event": return T_EVENTS;
            case "reminder": return T_REMINDERS;
            case "exception": return T_EXCEPTIONS;
            case "attachment": return T_ATTACHMENTS;
            default: return entityType;
        }
    }

    public static JsonObject calendar(CalendarEntity c) {
        JsonObject o = base(c.id, c.ownerId, c.createdAt, c.updatedAt, c.deletedAt, c.revision);
        o.addProperty("name", c.name);
        o.addProperty("color", c.color);
        o.addProperty("visible", c.visible);
        o.addProperty("archived", c.archived);
        o.addProperty("is_default", c.isDefault);
        o.addProperty("timezone", c.timezone);
        o.addProperty("default_reminder_minutes", c.defaultReminderMinutes);
        return o;
    }

    public static JsonObject event(EventEntity e) {
        JsonObject o = base(e.id, e.ownerId, e.createdAt, e.updatedAt, e.deletedAt, e.revision);
        o.addProperty("calendar_id", e.calendarId);
        o.addProperty("title", e.title);
        o.addProperty("description", e.description);
        o.addProperty("location", e.location);
        o.addProperty("start_at", TimeUtils.iso(e.startAt));
        o.addProperty("end_at", TimeUtils.iso(e.endAt));
        o.addProperty("all_day", e.allDay);
        o.addProperty("timezone", e.timezone);
        o.addProperty("color", e.color);
        o.addProperty("rrule", e.rrule);
        o.addProperty("series_id", e.seriesId);
        o.addProperty("recurrence_parent_id", e.recurrenceParentId);
        o.addProperty("original_start", e.originalStart > 0 ? TimeUtils.iso(e.originalStart) : null);
        o.addProperty("completed", e.completed);
        return o;
    }

    public static JsonObject reminder(ReminderEntity r) {
        JsonObject o = base(r.id, r.ownerId, r.createdAt, r.updatedAt, r.deletedAt, r.revision);
        o.addProperty("event_id", r.eventId);
        o.addProperty("minutes_before", r.minutesBefore);
        o.addProperty("channel", r.channel);
        return o;
    }

    public static JsonObject exception(EventExceptionEntity e) {
        JsonObject o = base(e.id, e.ownerId, e.createdAt, e.updatedAt, e.deletedAt, e.revision);
        o.addProperty("event_id", e.eventId);
        o.addProperty("occurrence_start", TimeUtils.iso(e.occurrenceStart));
        o.addProperty("cancelled", e.cancelled);
        o.addProperty("override_event_id", e.overrideEventId);
        return o;
    }

    public static JsonObject attachment(AttachmentEntity a) {
        JsonObject o = base(a.id, a.ownerId, a.createdAt, a.updatedAt, a.deletedAt, a.revision);
        o.addProperty("event_id", a.eventId);
        o.addProperty("file_name", a.fileName);
        o.addProperty("mime_type", a.mimeType);
        o.addProperty("size_bytes", a.sizeBytes);
        o.addProperty("storage_path", a.storagePath);
        o.addProperty("upload_status", a.uploadStatus);
        return o;
    }

    private static JsonObject base(String id, String owner, long created, long updated, long deleted, long revision) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("owner_id", owner);
        o.addProperty("created_at", TimeUtils.iso(created));
        o.addProperty("updated_at", TimeUtils.iso(updated));
        o.addProperty("deleted_at", deleted > 0 ? TimeUtils.iso(deleted) : null);
        o.addProperty("revision", revision);
        return o;
    }

    public static CalendarEntity calendar(JsonObject o) {
        CalendarEntity c = new CalendarEntity();
        c.id = str(o, "id");
        c.ownerId = str(o, "owner_id");
        c.name = str(o, "name");
        c.color = str(o, "color");
        c.visible = bool(o, "visible", true);
        c.archived = bool(o, "archived", false);
        c.isDefault = bool(o, "is_default", false);
        c.timezone = str(o, "timezone");
        c.defaultReminderMinutes = integer(o, "default_reminder_minutes", 0);
        c.createdAt = TimeUtils.parseIso(str(o, "created_at"), 0);
        c.updatedAt = TimeUtils.parseIso(str(o, "updated_at"), c.createdAt);
        c.deletedAt = TimeUtils.parseIso(str(o, "deleted_at"), 0);
        c.revision = longValue(o, "revision", 0);
        if (c.timezone == null) c.timezone = "UTC";
        return c;
    }

    public static EventEntity event(JsonObject o) {
        EventEntity e = new EventEntity();
        e.id = str(o, "id");
        e.ownerId = str(o, "owner_id");
        e.calendarId = str(o, "calendar_id");
        e.title = str(o, "title");
        e.description = str(o, "description");
        e.location = str(o, "location");
        e.startAt = TimeUtils.parseIso(str(o, "start_at"), 0);
        e.endAt = TimeUtils.parseIso(str(o, "end_at"), e.startAt);
        e.allDay = bool(o, "all_day", false);
        e.timezone = str(o, "timezone");
        e.color = str(o, "color");
        e.rrule = str(o, "rrule");
        e.seriesId = str(o, "series_id");
        e.recurrenceParentId = str(o, "recurrence_parent_id");
        e.originalStart = TimeUtils.parseIso(str(o, "original_start"), 0);
        e.completed = bool(o, "completed", false);
        e.createdAt = TimeUtils.parseIso(str(o, "created_at"), 0);
        e.updatedAt = TimeUtils.parseIso(str(o, "updated_at"), e.createdAt);
        e.deletedAt = TimeUtils.parseIso(str(o, "deleted_at"), 0);
        e.revision = longValue(o, "revision", 0);
        if (e.timezone == null) e.timezone = "UTC";
        if (e.endAt < e.startAt) e.endAt = e.startAt;
        return e;
    }

    public static ReminderEntity reminder(JsonObject o) {
        ReminderEntity r = new ReminderEntity();
        r.id = str(o, "id");
        r.ownerId = str(o, "owner_id");
        r.eventId = str(o, "event_id");
        r.minutesBefore = integer(o, "minutes_before", 0);
        r.channel = str(o, "channel");
        if (r.channel == null) r.channel = "local";
        r.createdAt = TimeUtils.parseIso(str(o, "created_at"), 0);
        r.updatedAt = TimeUtils.parseIso(str(o, "updated_at"), r.createdAt);
        r.deletedAt = TimeUtils.parseIso(str(o, "deleted_at"), 0);
        r.revision = longValue(o, "revision", 0);
        return r;
    }

    public static EventExceptionEntity exception(JsonObject o) {
        EventExceptionEntity e = new EventExceptionEntity();
        e.id = str(o, "id");
        e.ownerId = str(o, "owner_id");
        e.eventId = str(o, "event_id");
        e.occurrenceStart = TimeUtils.parseIso(str(o, "occurrence_start"), 0);
        e.cancelled = bool(o, "cancelled", false);
        e.overrideEventId = str(o, "override_event_id");
        e.createdAt = TimeUtils.parseIso(str(o, "created_at"), 0);
        e.updatedAt = TimeUtils.parseIso(str(o, "updated_at"), e.createdAt);
        e.deletedAt = TimeUtils.parseIso(str(o, "deleted_at"), 0);
        e.revision = longValue(o, "revision", 0);
        return e;
    }

    public static AttachmentEntity attachment(JsonObject o) {
        AttachmentEntity a = new AttachmentEntity();
        a.id = str(o, "id");
        a.ownerId = str(o, "owner_id");
        a.eventId = str(o, "event_id");
        a.fileName = str(o, "file_name");
        a.mimeType = str(o, "mime_type");
        a.sizeBytes = longValue(o, "size_bytes", 0);
        a.storagePath = str(o, "storage_path");
        a.uploadStatus = str(o, "upload_status");
        if (a.uploadStatus == null) a.uploadStatus = AttachmentEntity.STATUS_UPLOADED;
        a.createdAt = TimeUtils.parseIso(str(o, "created_at"), 0);
        a.updatedAt = TimeUtils.parseIso(str(o, "updated_at"), a.createdAt);
        a.deletedAt = TimeUtils.parseIso(str(o, "deleted_at"), 0);
        a.revision = longValue(o, "revision", 0);
        return a;
    }

    public static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return null;
        try { return o.get(key).getAsString(); } catch (Exception e) { return null; }
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return fallback;
        try { return o.get(key).getAsBoolean(); } catch (Exception e) { return fallback; }
    }

    private static int integer(JsonObject o, String key, int fallback) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return fallback;
        try { return o.get(key).getAsInt(); } catch (Exception e) { return fallback; }
    }

    private static long longValue(JsonObject o, String key, long fallback) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return fallback;
        try { return o.get(key).getAsLong(); } catch (Exception e) { return fallback; }
    }

    public static JsonObject payloadObject(String payload) {
        return com.google.gson.JsonParser.parseString(payload).getAsJsonObject();
    }

    public static String changedAt(JsonObject change) { return str(change, "changed_at"); }

    public static JsonElement payload(JsonObject change) { return change.get("payload"); }
}
