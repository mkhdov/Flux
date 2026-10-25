package com.mkhdov.flux.widget;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import com.mkhdov.flux.R;
import com.mkhdov.flux.data.local.entity.AttachmentEntity;
import com.mkhdov.flux.data.local.entity.CalendarEntity;
import com.mkhdov.flux.data.model.DaySnapshot;
import com.mkhdov.flux.data.model.EventInstance;
import com.mkhdov.flux.data.repo.CalendarRepository;
import com.mkhdov.flux.util.EventPalette;
import com.mkhdov.flux.util.HashUtils;
import com.mkhdov.flux.util.TimeUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class AgendaWidgetFactory implements RemoteViewsService.RemoteViewsFactory {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_EVENT = 1;
    private static final int TYPE_EMPTY = 2;
    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault());

    private final Context context;
    private final int widgetId;
    private final List<Row> rows = new ArrayList<>();
    private String style;
    private int textPrimary;
    private int textSecondary;
    private int divider;
    private boolean showDetails;
    private boolean dimPast;
    private Map<String, Boolean> attachments = new HashMap<>();
    private Map<String, CalendarEntity> calendars = new HashMap<>();

    public AgendaWidgetFactory(Context context, int widgetId) {
        this.context = context;
        this.widgetId = widgetId;
    }

    @Override public void onCreate() { }

    @Override public void onDataSetChanged() {
        build();
    }

    private void build() {
        rows.clear();
        WidgetPrefs prefs = WidgetPrefs.get(context, widgetId);
        style = prefs.style();
        applyColors();
        showDetails = prefs.showDetails();
        dimPast = prefs.dimPast();
        int days = Math.max(1, Math.min(14, prefs.days()));
        int maxEvents = Math.max(1, Math.min(60, prefs.maxEvents()));
        Set<String> selected = prefs.calendars();
        try {
            CalendarRepository repo = CalendarRepository.get(context);
            LocalDate today = LocalDate.now();
            DaySnapshot snapshot = repo.buildSnapshot(today, today.plusDays(days - 1));
            calendars = snapshot.calendars;
            attachments = new HashMap<>();
            for (AttachmentEntity a : repo.db().attachmentDao().getAllActiveSync()) {
                if (!a.isDeleted()) attachments.put(a.eventId, true);
            }
            long now = System.currentTimeMillis();
            int eventCount = 0;
            for (int i = 0; i < days && eventCount < maxEvents; i++) {
                LocalDate date = today.plusDays(i);
                List<EventInstance> events = new ArrayList<>();
                for (EventInstance event : snapshot.eventsFor(date)) {
                    if (!selected.isEmpty() && !selected.contains(event.source.calendarId)) continue;
                    events.add(event);
                }
                if (events.isEmpty()) continue;
                Row header = new Row();
                header.type = TYPE_HEADER;
                header.date = date;
                header.label = dateLabel(date, today);
                rows.add(header);
                for (EventInstance event : events) {
                    if (eventCount >= maxEvents) break;
                    Row row = new Row();
                    row.type = TYPE_EVENT;
                    row.date = date;
                    row.instance = event;
                    row.past = event.endAt < now;
                    rows.add(row);
                    eventCount++;
                }
            }
            if (rows.isEmpty()) {
                Row empty = new Row();
                empty.type = TYPE_EMPTY;
                empty.label = context.getString(R.string.empty_widget);
                rows.add(empty);
            }
        } catch (Exception e) {
            rows.clear();
            calendars.clear();
            Row empty = new Row();
            empty.type = TYPE_EMPTY;
            empty.label = context.getString(R.string.empty_widget);
            rows.add(empty);
        }
    }

    private void applyColors() {
        if (WidgetPrefs.STYLE_WHITE.equals(style)) {
            textPrimary = Color.parseColor("#FF111114");
            textSecondary = Color.parseColor("#FF8A8A8E");
            divider = Color.parseColor("#FFE5E5EA");
        } else if (WidgetPrefs.STYLE_DARK.equals(style)) {
            textPrimary = Color.parseColor("#FFF5F5F7");
            textSecondary = Color.parseColor("#FF98989D");
            divider = Color.parseColor("#FF2C2C2E");
        } else {
            boolean night = (context.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            textPrimary = night ? Color.parseColor("#FFF5F5F7") : Color.parseColor("#FF111114");
            textSecondary = night ? Color.parseColor("#FF98989D") : Color.parseColor("#FF8A8A8E");
            divider = night ? Color.parseColor("#552C2C2E") : Color.parseColor("#33C7C7CC");
        }
    }

    private String dateLabel(LocalDate date, LocalDate today) {
        if (date.equals(today)) return "Today, " + DATE_LABEL.format(date);
        if (date.equals(today.plusDays(1))) return "Tomorrow, " + DATE_LABEL.format(date);
        return date.format(DATE_LABEL);
    }

    @Override public int getCount() { return rows.size(); }

    @Override public RemoteViews getViewAt(int position) {
        if (position < 0 || position >= rows.size()) return null;
        Row row = rows.get(position);
        WidgetPrefs prefs = WidgetPrefs.get(context, widgetId);
        if (row.type == TYPE_HEADER) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_agenda_header);
            views.setTextViewText(R.id.widget_header_text, row.label);
            views.setTextColor(R.id.widget_header_text, textSecondary);
            views.setTextColor(R.id.widget_header_text, row.date.equals(LocalDate.now()) ? Color.parseColor("#FFFF3B30") : textSecondary);
            Intent fill = new Intent().putExtra("date_epoch", row.date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());
            fill.putExtra("date_action", prefs.dateAction());
            views.setOnClickFillInIntent(R.id.widget_header_root, fill);
            return views;
        }
        if (row.type == TYPE_EMPTY) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_agenda_empty);
            views.setTextViewText(R.id.widget_empty_text, row.label);
            views.setTextColor(R.id.widget_empty_text, textSecondary);
            return views;
        }
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_agenda_event);
        EventInstance instance = row.instance;
        CalendarEntity calendar = calendars.get(instance.source.calendarId);
        int color = EventPalette.parse(instance.source.color, calendar == null ? Color.parseColor("#FF4285F4") : EventPalette.parse(calendar.color, Color.parseColor("#FF4285F4")));
        views.setInt(R.id.widget_color_bar, "setBackgroundColor", color);
        views.setTextColor(R.id.widget_event_title, textPrimary);
        views.setTextColor(R.id.widget_time, textSecondary);
        views.setTextColor(R.id.widget_event_details, textSecondary);
        views.setTextViewText(R.id.widget_event_title, instance.source.title == null ? "Untitled" : instance.source.title);
        ZoneId zone = TimeUtils.zoneOf(instance.source);
        String time = instance.source.allDay ? "All day" : TimeUtils.formatTime(instance.startAt, zone);
        views.setTextViewText(R.id.widget_time, time);
        boolean showAttachment = attachments.get(instance.source.id) != null;
        views.setViewVisibility(R.id.widget_attachment, showAttachment ? View.VISIBLE : View.GONE);
        views.setInt(R.id.widget_attachment, "setColorFilter", textSecondary);
        if (showDetails) {
            String details = null;
            if (instance.source.location != null && !instance.source.location.trim().isEmpty()) {
                details = instance.source.location.trim();
            } else if (instance.source.description != null && !instance.source.description.trim().isEmpty()) {
                details = instance.source.description.trim().replace('\n', ' ');
            } else if (!instance.source.allDay) {
                details = TimeUtils.formatTimeRange(instance.startAt, instance.endAt, zone);
            } else if (instance.endAt - instance.startAt > 86400000L) {
                details = "Multi-day";
            }
            if (details != null && !details.isEmpty()) {
                views.setViewVisibility(R.id.widget_event_details, View.VISIBLE);
                views.setTextViewText(R.id.widget_event_details, details);
            } else {
                views.setViewVisibility(R.id.widget_event_details, View.GONE);
            }
        } else {
            views.setViewVisibility(R.id.widget_event_details, View.GONE);
        }
        views.setInt(R.id.widget_divider, "setBackgroundColor", divider);
        if (dimPast && row.past) views.setFloat(R.id.widget_item_root, "setAlpha", 0.46f);
        else views.setFloat(R.id.widget_item_root, "setAlpha", 1f);
        Intent fill = new Intent()
                .putExtra("event_id", instance.source.id)
                .putExtra("occurrence_start", instance.occurrenceStart)
                .putExtra("date_epoch", row.date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());
        views.setOnClickFillInIntent(R.id.widget_item_root, fill);
        views.setContentDescription(R.id.widget_item_root, (time + ", " + instance.source.title).trim());
        return views;
    }

    @Override public RemoteViews getLoadingView() { return null; }
    @Override public int getViewTypeCount() { return 3; }

    @Override public long getItemId(int position) {
        if (position < 0 || position >= rows.size()) return position;
        Row row = rows.get(position);
        if (row.type == TYPE_EVENT && row.instance != null) return HashUtils.stableId(row.instance.instanceId + row.date);
        if (row.type == TYPE_HEADER && row.date != null) return HashUtils.stableId("header" + row.date);
        return HashUtils.stableId("empty" + position);
    }

    @Override public boolean hasStableIds() { return true; }

    @Override public void onDestroy() { rows.clear(); }

    private static class Row {
        int type;
        LocalDate date;
        String label;
        EventInstance instance;
        boolean past;
    }
}
