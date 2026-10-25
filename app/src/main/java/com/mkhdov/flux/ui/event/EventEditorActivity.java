package com.mkhdov.flux.ui.event;

import com.mkhdov.flux.ui.common.BaseActivity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.mkhdov.flux.R;
import com.mkhdov.flux.attachments.AttachmentManager;
import com.mkhdov.flux.data.local.entity.AttachmentEntity;
import com.mkhdov.flux.data.local.entity.CalendarEntity;
import com.mkhdov.flux.data.local.entity.EventEntity;
import com.mkhdov.flux.data.local.entity.ReminderEntity;
import com.mkhdov.flux.data.repo.CalendarRepository;
import com.mkhdov.flux.ui.common.ColorPaletteView;
import com.mkhdov.flux.ui.common.DialogFactory;
import com.mkhdov.flux.ui.common.PickerUtils;
import com.mkhdov.flux.ui.common.UiUtils;
import com.mkhdov.flux.util.DataBus;
import com.mkhdov.flux.util.EventPalette;
import com.mkhdov.flux.util.RRuleUtils;
import com.mkhdov.flux.util.TimeUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class EventEditorActivity extends BaseActivity {
    public static final String EXTRA_EVENT_ID = "event_id";
    public static final String EXTRA_OCCURRENCE_START = "occurrence_start";
    public static final String EXTRA_DATE = "date";
    public static final String EXTRA_START_AT = "start_at";
    public static final String EXTRA_END_AT = "end_at";
    public static final String EXTRA_TITLE = "title";

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault());

    private CalendarRepository repository;
    private AttachmentManager attachmentManager;

    private EditText titleInput;
    private EditText locationInput;
    private EditText descriptionInput;
    private SwitchMaterial allDaySwitch;
    private SwitchMaterial completedSwitch;
    private TextView titleView;
    private TextView saveView;
    private TextView startDateView;
    private TextView endDateView;
    private TextView startTimeView;
    private TextView endTimeView;
    private TextView timezoneView;
    private TextView recurrenceView;
    private TextView calendarNameView;
    private TextView attachmentsHint;
    private TextView remindersEmpty;
    private View calendarDot;
    private View startTime;
    private View endTime;
    private View completedRow;
    private View deleteView;
    private LinearLayout remindersContainer;
    private LinearLayout attachmentsContainer;
    private ColorPaletteView palette;

    private final List<CalendarEntity> calendars = new ArrayList<>();
    private final List<ReminderEntity> reminders = new ArrayList<>();
    private final List<AttachmentEntity> attachments = new ArrayList<>();

    private CalendarEntity calendar;
    private EventEntity base;
    private EventEntity editingEvent;
    private long occurrenceStart;
    private String selectedColor;
    private String timezone;
    private String rrule;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalTime startTimeValue = LocalTime.of(9, 0);
    private LocalTime endTimeValue = LocalTime.of(10, 0);
    private boolean loading = true;
    private boolean ready;
    private boolean saving;
    private boolean dirty;
    private int pendingLoads;

    private final ActivityResultLauncher<String[]> attachmentPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onAttachmentPicked);

    private final DataBus.Listener dataListener = () -> {
        if (!loading && ready && !saving && editingEvent != null) loadAttachmentsOnly();
    };

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_event_editor);
        repository = CalendarRepository.get(this);
        attachmentManager = AttachmentManager.get(this);
        bindViews();
        setupListeners();
        loadCalendars();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { handleClose(); }
        });
    }

    @Override protected void onStart() {
        super.onStart();
        DataBus.add(dataListener);
    }

    @Override protected void onStop() {
        DataBus.remove(dataListener);
        super.onStop();
    }

    private void bindViews() {
        titleInput = findViewById(R.id.editor_event_title);
        locationInput = findViewById(R.id.editor_location);
        descriptionInput = findViewById(R.id.editor_description);
        allDaySwitch = findViewById(R.id.editor_all_day);
        completedSwitch = findViewById(R.id.editor_completed);
        titleView = findViewById(R.id.editor_title);
        saveView = findViewById(R.id.editor_save);
        startDateView = findViewById(R.id.editor_start_date);
        endDateView = findViewById(R.id.editor_end_date);
        startTimeView = findViewById(R.id.editor_start_time);
        endTimeView = findViewById(R.id.editor_end_time);
        timezoneView = findViewById(R.id.editor_timezone);
        recurrenceView = findViewById(R.id.editor_recurrence);
        calendarNameView = findViewById(R.id.editor_calendar_name);
        attachmentsHint = findViewById(R.id.editor_attachments_hint);
        remindersEmpty = findViewById(R.id.editor_reminders_empty);
        calendarDot = findViewById(R.id.editor_calendar_dot);
        startTime = findViewById(R.id.editor_start_time);
        endTime = findViewById(R.id.editor_end_time);
        completedRow = findViewById(R.id.editor_completed_row);
        deleteView = findViewById(R.id.editor_delete);
        remindersContainer = findViewById(R.id.editor_reminders);
        attachmentsContainer = findViewById(R.id.editor_attachments);
        palette = findViewById(R.id.editor_color_palette);
        attachmentManager.progress().observe(this, this::onProgress);
    }

    private void setupListeners() {
        View back = findViewById(R.id.editor_back);
        back.setOnClickListener(v -> handleClose());
        saveView.setOnClickListener(v -> attemptSave());
        findViewById(R.id.editor_calendar_row).setOnClickListener(v -> pickCalendar());
        findViewById(R.id.editor_start_date_box).setOnClickListener(v -> pickStartDate());
        findViewById(R.id.editor_end_date_box).setOnClickListener(v -> pickEndDate());
        startTime.setOnClickListener(v -> pickStartTime());
        endTime.setOnClickListener(v -> pickEndTime());
        findViewById(R.id.editor_timezone_row).setOnClickListener(v -> pickTimezone());
        findViewById(R.id.editor_recurrence_row).setOnClickListener(v -> pickRecurrence());
        findViewById(R.id.editor_add_reminder).setOnClickListener(v -> addReminder());
        findViewById(R.id.editor_add_attachment).setOnClickListener(v -> addAttachment());
        deleteView.setOnClickListener(v -> confirmDelete());

        allDaySwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (loading) return;
            if (checked) {
                if (endDate.isBefore(startDate)) endDate = startDate;
            } else {
                normalizeEnd();
            }
            renderDateTime();
            markDirty();
        });
        completedSwitch.setOnCheckedChangeListener((buttonView, checked) -> markDirty());
        palette.setOnColorSelectedListener(hex -> {
            selectedColor = hex;
            markDirty();
        });
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { markDirty(); }
        };
        titleInput.addTextChangedListener(watcher);
        locationInput.addTextChangedListener(watcher);
        descriptionInput.addTextChangedListener(watcher);
    }

    private void loadCalendars() {
        repository.loadCalendars(new CalendarRepository.Callback<List<CalendarEntity>>() {
            @Override public void onSuccess(List<CalendarEntity> list) {
                calendars.clear();
                if (list != null) {
                    for (CalendarEntity item : list) {
                        if (item != null && !item.isDeleted()) calendars.add(item);
                    }
                }
                if (calendars.isEmpty()) {
                    loading = false;
                    new MaterialAlertDialogBuilder(EventEditorActivity.this)
                            .setTitle(R.string.no_calendars)
                            .setMessage(R.string.no_calendars_hint)
                            .setPositiveButton(R.string.done, (d, w) -> finish())
                            .show();
                    return;
                }
                String eventId = getIntent().getStringExtra(EXTRA_EVENT_ID);
                if (eventId != null && !eventId.isEmpty()) {
                    loadExisting(eventId);
                } else {
                    createNew();
                }
            }
            @Override public void onError(String message) {
                loading = false;
                Toast.makeText(EventEditorActivity.this, message, Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    private void loadExisting(String eventId) {
        repository.loadEvent(eventId, new CalendarRepository.Callback<EventEntity>() {
            @Override public void onSuccess(EventEntity event) {
                if (event == null || event.isDeleted()) {
                    loading = false;
                    Toast.makeText(EventEditorActivity.this, R.string.error_generic, Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                editingEvent = event;
                if (event.isOverride() && event.recurrenceParentId != null && !event.recurrenceParentId.isEmpty()) {
                    repository.loadEvent(event.recurrenceParentId, new CalendarRepository.Callback<EventEntity>() {
                        @Override public void onSuccess(EventEntity parent) {
                            base = parent == null || parent.isDeleted() ? event : parent;
                            completeExisting(event);
                        }
                        @Override public void onError(String message) {
                            base = event;
                            completeExisting(event);
                        }
                    });
                } else {
                    base = event;
                    completeExisting(event);
                }
            }
            @Override public void onError(String message) {
                loading = false;
                Toast.makeText(EventEditorActivity.this, message, Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    private void completeExisting(EventEntity displayed) {
        calendar = pickCalendar(displayed.calendarId);
        if (calendar == null) calendar = calendars.get(0);
        occurrenceStart = displayed.isOverride()
                ? (displayed.originalStart > 0 ? displayed.originalStart : displayed.startAt)
                : (getIntent().getLongExtra(EXTRA_OCCURRENCE_START, 0L) > 0
                        ? getIntent().getLongExtra(EXTRA_OCCURRENCE_START, 0L) : displayed.startAt);
        pendingLoads = 2;
        populate(displayed);
        titleView.setText(R.string.edit_event);
        deleteView.setVisibility(View.VISIBLE);
        completedRow.setVisibility(View.VISIBLE);
        loadReminders();
        loadAttachments();
    }

    private void createNew() {
        base = null;
        editingEvent = null;
        occurrenceStart = 0;
        long startAt = getIntent().getLongExtra(EXTRA_START_AT, 0L);
        long endAt = getIntent().getLongExtra(EXTRA_END_AT, 0L);
        String dateExtra = getIntent().getStringExtra(EXTRA_DATE);
        CalendarEntity preferred = null;
        if (dateExtra != null && startAt == 0) {
            try {
                LocalDate date = LocalDate.parse(dateExtra);
                preferred = pickCalendar(null);
                ZoneId zone = zoneOf(preferred == null ? null : preferred.timezone);
                startAt = TimeUtils.toMillis(date, LocalTime.of(9, 0), zone);
                endAt = TimeUtils.toMillis(date, LocalTime.of(10, 0), zone);
            } catch (Exception ignored) { }
        }
        if (startAt == 0) {
            LocalDateTime next = LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0);
            startAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            endAt = startAt + 3600000L;
        }
        if (endAt <= startAt) endAt = startAt + 3600000L;
        EventEntity event = new EventEntity();
        event.calendarId = preferred == null ? null : preferred.id;
        event.startAt = startAt;
        event.endAt = endAt;
        event.allDay = false;
        event.title = getIntent().getStringExtra(EXTRA_TITLE);
        calendar = pickCalendar(event.calendarId);
        if (calendar == null) calendar = calendars.get(0);
        event.calendarId = calendar.id;
        event.ownerId = calendar.ownerId;
        event.timezone = calendar.timezone == null || calendar.timezone.isEmpty()
                ? ZoneId.systemDefault().getId() : calendar.timezone;
        event.color = calendar.color;
        populate(event);
        titleView.setText(R.string.new_event_title);
        deleteView.setVisibility(View.GONE);
        completedRow.setVisibility(View.GONE);
        loading = false;
        ready = true;
        updateReadyState();
        if (calendar.defaultReminderMinutes >= 0) {
            addDefaultReminder();
        }
        dirty = false;
    }

    private void populate(EventEntity event) {
        selectedColor = EventPalette.normalize(event.color == null ? calendar.color : event.color);
        timezone = event.timezone == null || event.timezone.isEmpty()
                ? (calendar.timezone == null || calendar.timezone.isEmpty()
                ? ZoneId.systemDefault().getId() : calendar.timezone)
                : event.timezone;
        String recurrence = event.rrule;
        if ((recurrence == null || recurrence.trim().isEmpty()) && base != null && base.isRecurring()) {
            recurrence = base.rrule;
        }
        rrule = RRuleUtils.normalize(recurrence);
        ZoneId zone = zoneOf(timezone);
        if (event.allDay) {
            startDate = TimeUtils.dateOf(event.startAt, zone);
            endDate = TimeUtils.dateOf(Math.max(event.startAt, event.endAt - 1L), zone);
            if (endDate.isBefore(startDate)) endDate = startDate;
            startTimeValue = LocalTime.of(9, 0);
            endTimeValue = LocalTime.of(10, 0);
        } else {
            LocalDateTime start = TimeUtils.localDateTime(event.startAt, zone);
            LocalDateTime end = TimeUtils.localDateTime(event.endAt, zone);
            startDate = start.toLocalDate();
            startTimeValue = start.toLocalTime();
            endDate = end.toLocalDate();
            endTimeValue = end.toLocalTime();
            if (endDate.isBefore(startDate)
                    || (endDate.equals(startDate) && !endTimeValue.isAfter(startTimeValue))) {
                LocalDateTime adjusted = start.plusHours(1);
                endDate = adjusted.toLocalDate();
                endTimeValue = adjusted.toLocalTime();
            }
        }
        titleInput.setText(event.title == null ? "" : event.title);
        locationInput.setText(event.location == null ? "" : event.location);
        descriptionInput.setText(event.description == null ? "" : event.description);
        allDaySwitch.setChecked(event.allDay);
        completedSwitch.setChecked(event.completed);
        palette.setSelectedColor(selectedColor);
        renderCalendar();
        renderDateTime();
        renderRecurrence();
        renderReminders();
        attachmentsContainer.removeAllViews();
        attachments.clear();
        attachmentsHint.setVisibility(View.VISIBLE);
        loading = false;
        ready = pendingLoads == 0;
        updateReadyState();
    }

    private CalendarEntity pickCalendar(String preferredId) {
        CalendarEntity fallback = null;
        for (CalendarEntity c : calendars) {
            if (preferredId != null && preferredId.equals(c.id)) return c;
            if (fallback == null && c.isDefault) fallback = c;
            if (fallback == null && c.visible && !c.archived) fallback = c;
        }
        if (fallback == null && !calendars.isEmpty()) fallback = calendars.get(0);
        return fallback;
    }

    private void renderCalendar() {
        if (calendar == null) return;
        calendarNameView.setText(calendar.name == null ? getString(R.string.calendar) : calendar.name);
        UiUtils.colorDot(calendarDot, calendar.color);
    }

    private ZoneId zoneOf(String zoneId) {
        try {
            return ZoneId.of(zoneId == null || zoneId.isEmpty() ? ZoneId.systemDefault().getId() : zoneId);
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    private void renderDateTime() {
        startDateView.setText(DATE_FORMAT.format(startDate));
        endDateView.setText(DATE_FORMAT.format(endDate));
        startTimeView.setText(formatTime(startTimeValue));
        endTimeView.setText(formatTime(endTimeValue));
        boolean allDay = allDaySwitch.isChecked();
        startTime.setVisibility(allDay ? View.GONE : View.VISIBLE);
        endTime.setVisibility(allDay ? View.GONE : View.VISIBLE);
        timezoneView.setText(timezone.replace('_', ' ') + "   ·   " + offsetLabel());
    }

    private String offsetLabel() {
        try {
            ZoneId zone = zoneOf(timezone);
            ZoneOffset offset = zone.getRules().getOffset(Instant.now());
            String value = offset.getId();
            if ("Z".equals(value)) return "GMT";
            return "GMT" + value;
        } catch (Exception e) {
            return "GMT";
        }
    }

    private String formatTime(LocalTime time) {
        return time.format(TimeUtils.is24Hour() ? TimeUtils.TIME_24 : TimeUtils.TIME_12);
    }

    private void renderRecurrence() {
        if (rrule == null || rrule.trim().isEmpty()) {
            recurrenceView.setText(R.string.does_not_repeat_hint);
        } else {
            recurrenceView.setText(RRuleUtils.describe(rrule));
        }
    }

    private void pickCalendar() {
        if (calendars.isEmpty()) return;
        String[] labels = new String[calendars.size()];
        int selected = 0;
        for (int i = 0; i < calendars.size(); i++) {
            CalendarEntity c = calendars.get(i);
            labels[i] = c.name == null ? getString(R.string.calendar) : c.name;
            if (calendar != null && calendar.id.equals(c.id)) selected = i;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.choose_calendar)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    CalendarEntity previous = calendar;
                    calendar = calendars.get(which);
                    if (selectedColor == null || previous == null || selectedColor.equalsIgnoreCase(previous.color)) {
                        selectedColor = EventPalette.normalize(calendar.color);
                        palette.setSelectedColor(selectedColor);
                    }
                    renderCalendar();
                    if (isNew() && reminders.isEmpty() && calendar.defaultReminderMinutes >= 0) {
                        addDefaultReminder();
                    }
                    markDirty();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private boolean isNew() { return base == null && editingEvent == null; }

    private void pickStartDate() {
        PickerUtils.pickDate(this, startDate, date -> {
            startDate = date;
            if (endDate.isBefore(date)) endDate = date;
            if (!allDaySwitch.isChecked()) normalizeEnd();
            renderDateTime();
            markDirty();
        });
    }

    private void pickEndDate() {
        PickerUtils.pickDate(this, endDate, date -> {
            if (date.isBefore(startDate)) date = startDate;
            endDate = date;
            if (!allDaySwitch.isChecked()) normalizeEnd();
            renderDateTime();
            markDirty();
        });
    }

    private void pickStartTime() {
        PickerUtils.pickTime(this, startTimeValue, time -> {
            startTimeValue = time;
            if (endDate.isBefore(startDate) || (endDate.equals(startDate) && !endTimeValue.isAfter(startTimeValue))) {
                LocalDateTime end = startDate.atTime(time).plusHours(1);
                endDate = end.toLocalDate();
                endTimeValue = end.toLocalTime();
            }
            renderDateTime();
            markDirty();
        });
    }

    private void pickEndTime() {
        PickerUtils.pickTime(this, endTimeValue, time -> {
            endTimeValue = time;
            normalizeEnd();
            renderDateTime();
            markDirty();
        });
    }

    private void normalizeEnd() {
        if (endDate.isBefore(startDate)) endDate = startDate;
        if (endDate.equals(startDate) && !endTimeValue.isAfter(startTimeValue)) {
            LocalDateTime next = startDate.atTime(startTimeValue).plusHours(1);
            endDate = next.toLocalDate();
            endTimeValue = next.toLocalTime();
        }
    }

    private void pickTimezone() {
        DialogFactory.pickTimezone(this, timezone, zoneId -> {
            timezone = zoneId;
            renderDateTime();
            markDirty();
        });
    }

    private void pickRecurrence() {
        RecurrenceDialog.show(this, rrule, startDate, zoneOf(timezone), rule -> {
            rrule = rule;
            renderRecurrence();
            markDirty();
        });
    }

    private void addReminder() {
        if (loading) return;
        int current = reminders.isEmpty() ? 0 : reminders.get(0).minutesBefore;
        DialogFactory.pickReminder(this, current, minutes -> {
            for (ReminderEntity reminder : reminders) {
                if (reminder.minutesBefore == minutes) {
                    UiUtils.snack(titleInput, getString(R.string.reminder_exists));
                    return;
                }
            }
            ReminderEntity reminder = new ReminderEntity();
            reminder.minutesBefore = minutes;
            reminder.channel = "local";
            reminders.add(reminder);
            sortReminders();
            renderReminders();
            markDirty();
        });
    }

    private void addDefaultReminder() {
        if (calendar == null || calendar.defaultReminderMinutes < 0) return;
        for (ReminderEntity reminder : reminders) {
            if (reminder.minutesBefore == calendar.defaultReminderMinutes) return;
        }
        ReminderEntity reminder = new ReminderEntity();
        reminder.minutesBefore = Math.max(0, calendar.defaultReminderMinutes);
        reminder.channel = "local";
        reminders.add(reminder);
        sortReminders();
        renderReminders();
        markDirty();
    }

    private void sortReminders() {
        Collections.sort(reminders, Comparator.comparingInt(r -> r.minutesBefore));
    }

    private void loadReminders() {
        if (editingEvent == null) {
            maybeFinishLoading();
            return;
        }
        repository.loadReminders(editingEvent.id, new CalendarRepository.Callback<List<ReminderEntity>>() {
            @Override public void onSuccess(List<ReminderEntity> list) {
                reminders.clear();
                if (list != null) reminders.addAll(list);
                sortReminders();
                renderReminders();
                maybeFinishLoading();
            }
            @Override public void onError(String message) {
                maybeFinishLoading();
                UiUtils.snack(titleInput, message);
            }
        });
    }

    private void renderReminders() {
        remindersContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < reminders.size(); i++) {
            final int index = i;
            ReminderEntity reminder = reminders.get(i);
            View row = inflater.inflate(R.layout.item_reminder, remindersContainer, false);
            TextView label = row.findViewById(R.id.reminder_label);
            label.setText(DialogFactory.reminderLabel(reminder.minutesBefore));
            row.setOnClickListener(v -> DialogFactory.pickReminder(this, reminder.minutesBefore, minutes -> {
                for (int j = 0; j < reminders.size(); j++) {
                    if (j != index && reminders.get(j).minutesBefore == minutes) {
                        UiUtils.snack(titleInput, getString(R.string.reminder_exists));
                        return;
                    }
                }
                reminders.get(index).minutesBefore = minutes;
                sortReminders();
                renderReminders();
                markDirty();
            }));
            row.findViewById(R.id.reminder_delete).setOnClickListener(v -> {
                reminders.remove(index);
                renderReminders();
                markDirty();
            });
            remindersContainer.addView(row);
        }
        remindersEmpty.setVisibility(reminders.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void loadAttachments() {
        if (editingEvent == null) {
            maybeFinishLoading();
            return;
        }
        repository.loadAttachments(editingEvent.id, new CalendarRepository.Callback<List<AttachmentEntity>>() {
            @Override public void onSuccess(List<AttachmentEntity> list) {
                attachments.clear();
                if (list != null) attachments.addAll(list);
                renderAttachments();
                maybeFinishLoading();
            }
            @Override public void onError(String message) {
                maybeFinishLoading();
                UiUtils.snack(titleInput, message);
            }
        });
    }

    private void maybeFinishLoading() {
        if (pendingLoads > 0) pendingLoads--;
        if (pendingLoads <= 0) {
            ready = true;
            updateReadyState();
        }
    }

    private void updateReadyState() {
        if (saveView == null) return;
        saveView.setAlpha(ready ? 1f : 0.4f);
        saveView.setEnabled(ready);
    }

    private void onProgress(Map<String, Integer> map) {
        if (attachments.isEmpty()) return;
        renderAttachments();
    }

    private void renderAttachments() {
        attachmentsContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        Map<String, Integer> progress = attachmentManager.progress().getValue();
        for (AttachmentEntity attachment : attachments) {
            View row = inflater.inflate(R.layout.item_attachment, attachmentsContainer, false);
            TextView name = row.findViewById(R.id.attachment_name);
            TextView status = row.findViewById(R.id.attachment_status);
            TextView action = row.findViewById(R.id.attachment_action);
            ProgressBar bar = row.findViewById(R.id.attachment_progress);
            name.setText(attachment.fileName == null ? getString(R.string.attachments) : attachment.fileName);
            status.setText(AttachmentManager.formatBytes(attachment.sizeBytes) + " · " + statusLabel(attachment));
            if (AttachmentEntity.STATUS_UPLOADING.equals(attachment.uploadStatus)) {
                bar.setVisibility(View.VISIBLE);
                int value = 0;
                if (progress != null) {
                    Integer p = progress.get(attachment.id);
                    if (p != null) value = p;
                }
                bar.setProgress(value);
                action.setText(R.string.cancel_upload);
            } else {
                bar.setVisibility(View.GONE);
                if (AttachmentEntity.STATUS_FAILED.equals(attachment.uploadStatus)) {
                    action.setText(R.string.retry);
                } else if (AttachmentEntity.STATUS_UPLOADED.equals(attachment.uploadStatus)
                        || AttachmentEntity.STATUS_LOCAL.equals(attachment.uploadStatus)) {
                    action.setText(R.string.open_file);
                } else {
                    action.setText("");
                    action.setVisibility(View.GONE);
                }
            }
            action.setOnClickListener(v -> handleAttachmentAction(attachment));
            row.findViewById(R.id.attachment_delete).setOnClickListener(v -> confirmDeleteAttachment(attachment));
            attachmentsContainer.addView(row);
        }
        attachmentsHint.setVisibility(attachments.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private String statusLabel(AttachmentEntity attachment) {
        String status = attachment.uploadStatus;
        if (AttachmentEntity.STATUS_UPLOADED.equals(status)) return getString(R.string.attachment_uploaded_short);
        if (AttachmentEntity.STATUS_UPLOADING.equals(status)) return getString(R.string.attachment_uploading_short);
        if (AttachmentEntity.STATUS_FAILED.equals(status)) return getString(R.string.attachment_failed_short);
        if (AttachmentEntity.STATUS_LOCAL.equals(status)) return getString(R.string.attachment_local_only);
        if (!repository.auth().hasSession()) return getString(R.string.attachment_local_only);
        return getString(R.string.attachment_queued);
    }

    private void handleAttachmentAction(AttachmentEntity attachment) {
        if (AttachmentEntity.STATUS_UPLOADING.equals(attachment.uploadStatus)) {
            attachmentManager.cancelUpload(attachment.id);
        } else if (AttachmentEntity.STATUS_FAILED.equals(attachment.uploadStatus)) {
            attachmentManager.retry(attachment.id);
        } else {
            attachmentManager.openAttachment(this, attachment.id, new AttachmentManager.Callback<Void>() {
                @Override public void onSuccess(Void value) { }
                @Override public void onError(String message) { UiUtils.snack(titleInput, message); }
            });
        }
    }

    private void confirmDeleteAttachment(AttachmentEntity attachment) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_attachment)
                .setMessage(attachment.fileName)
                .setPositiveButton(R.string.delete, (d, w) -> attachmentManager.deleteAttachment(attachment.id,
                        new AttachmentManager.Callback<Void>() {
                            @Override public void onSuccess(Void value) {
                                UiUtils.snack(titleInput, getString(R.string.attachment_deleted));
                                loadAttachmentsOnly();
                            }
                            @Override public void onError(String message) { UiUtils.snack(titleInput, message); }
                        }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void loadAttachmentsOnly() {
        if (editingEvent == null) return;
        repository.loadAttachments(editingEvent.id, new CalendarRepository.Callback<List<AttachmentEntity>>() {
            @Override public void onSuccess(List<AttachmentEntity> list) {
                attachments.clear();
                if (list != null) attachments.addAll(list);
                renderAttachments();
            }
            @Override public void onError(String message) {
                UiUtils.snack(titleInput, message);
            }
        });
    }

    private void addAttachment() {
        if (isNew()) {
            UiUtils.snack(titleInput, getString(R.string.save_before_attaching));
            return;
        }
        if (attachments.size() >= AttachmentManager.MAX_ATTACHMENTS_PER_EVENT) {
            UiUtils.snack(titleInput, getString(R.string.attachment_limit_reached));
            return;
        }
        attachmentPicker.launch(new String[]{"*/*"});
    }

    private void onAttachmentPicked(Uri uri) {
        if (uri == null || editingEvent == null) return;
        attachmentManager.addAttachment(uri, editingEvent.id, new AttachmentManager.Callback<AttachmentEntity>() {
            @Override public void onSuccess(AttachmentEntity value) {
                loadAttachmentsOnly();
            }
            @Override public void onError(String message) { UiUtils.snack(titleInput, message); }
        });
    }

    private void markDirty() {
        if (loading || saving) return;
        dirty = true;
    }

    private void attemptSave() {
        if (!ready || saving || calendar == null) return;
        UiUtils.hideKeyboard(titleInput);
        EventEntity edited = buildEdited();
        if (base != null && base.isRecurring()) {
            showEditScope(edited);
        } else {
            doSave(edited, CalendarRepository.EditScope.ALL);
        }
    }

    private void showEditScope(EventEntity edited) {
        String[] options = {
                getString(R.string.edit_scope_this),
                getString(R.string.edit_scope_future),
                getString(R.string.edit_scope_all)
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.edit_scope_title)
                .setItems(options, (dialog, which) -> {
                    CalendarRepository.EditScope scope;
                    if (which == 0) scope = CalendarRepository.EditScope.THIS;
                    else if (which == 1) scope = CalendarRepository.EditScope.FUTURE;
                    else scope = CalendarRepository.EditScope.ALL;
                    doSave(edited, scope);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private EventEntity buildEdited() {
        EventEntity edited = editingEvent == null ? new EventEntity() : editingEvent.copy();
        edited.calendarId = calendar.id;
        edited.title = titleInput.getText().toString().trim();
        edited.location = locationInput.getText().toString().trim();
        edited.description = descriptionInput.getText().toString().trim();
        edited.allDay = allDaySwitch.isChecked();
        edited.timezone = timezone;
        edited.color = selectedColor == null ? EventPalette.normalize(calendar.color) : selectedColor;
        edited.rrule = rrule;
        edited.completed = completedSwitch.isChecked();
        ZoneId zone = zoneOf(timezone);
        if (edited.allDay) {
            if (endDate.isBefore(startDate)) endDate = startDate;
            edited.startAt = TimeUtils.startOfDay(startDate, zone);
            edited.endAt = TimeUtils.startOfDay(endDate.plusDays(1), zone);
        } else {
            normalizedDateTime();
            edited.startAt = TimeUtils.toMillis(startDate, startTimeValue, zone);
            edited.endAt = TimeUtils.toMillis(endDate, endTimeValue, zone);
            if (edited.endAt <= edited.startAt) edited.endAt = edited.startAt + 3600000L;
        }
        return edited;
    }

    private void normalizedDateTime() {
        if (endDate.isBefore(startDate)) endDate = startDate;
        if (endDate.equals(startDate) && !endTimeValue.isAfter(startTimeValue)) {
            LocalDateTime next = startDate.atTime(startTimeValue).plusHours(1);
            endDate = next.toLocalDate();
            endTimeValue = next.toLocalTime();
        }
    }

    private void doSave(EventEntity edited, CalendarRepository.EditScope scope) {
        saving = true;
        saveView.setAlpha(0.4f);
        long occurrence = base != null && base.isRecurring()
                ? (occurrenceStart > 0 ? occurrenceStart : base.startAt)
                : (base == null ? 0 : base.startAt);
        List<ReminderEntity> reminderList = new ArrayList<>(reminders);
        repository.saveEdited(base, occurrence, edited, reminderList, scope, new CalendarRepository.Callback<EventEntity>() {
            @Override public void onSuccess(EventEntity value) {
                saving = false;
                dirty = false;
                Toast.makeText(EventEditorActivity.this, R.string.saved, Toast.LENGTH_SHORT).show();
                finish();
            }
            @Override public void onError(String message) {
                saving = false;
                updateReadyState();
                UiUtils.snack(titleInput, message);
            }
        });
    }

    private void confirmDelete() {
        if (isNew()) return;
        boolean recurring = base != null && base.isRecurring();
        DialogFactory.pickEventDeleteScope(this, recurring,
                () -> performDelete(CalendarRepository.DeleteScope.THIS),
                () -> performDelete(CalendarRepository.DeleteScope.FUTURE),
                () -> performDelete(CalendarRepository.DeleteScope.ALL));
    }

    private void performDelete(CalendarRepository.DeleteScope scope) {
        EventEntity target = editingEvent != null ? editingEvent : base;
        EventEntity event = base != null ? base : target;
        if (event == null) return;
        long occurrence = event.isRecurring()
                ? (occurrenceStart > 0 ? occurrenceStart : event.startAt)
                : event.startAt;
        saving = true;
        repository.deleteEvent(event, occurrence, scope, new CalendarRepository.Callback<Void>() {
            @Override public void onSuccess(Void value) {
                saving = false;
                dirty = false;
                Toast.makeText(EventEditorActivity.this, R.string.event_deleted, Toast.LENGTH_SHORT).show();
                finish();
            }
            @Override public void onError(String message) {
                saving = false;
                UiUtils.snack(titleInput, message);
            }
        });
    }

    private void handleClose() {
        if (saving) return;
        if (!dirty) {
            finish();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.unsaved_changes)
                .setPositiveButton(R.string.discard_changes_button, (d, w) -> finish())
                .setNegativeButton(R.string.keep_editing, null)
                .show();
    }
}
