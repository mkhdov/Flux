package com.mkhdov.flux.ui.main;

import com.mkhdov.flux.ui.common.BaseActivity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.mkhdov.flux.R;
import com.mkhdov.flux.data.local.entity.EventEntity;
import com.mkhdov.flux.data.model.DaySnapshot;
import com.mkhdov.flux.data.model.EventInstance;
import com.mkhdov.flux.data.repo.AuthManager;
import com.mkhdov.flux.data.repo.CalendarRepository;
import com.mkhdov.flux.sync.SyncEngine;
import com.mkhdov.flux.sync.SyncStatus;
import com.mkhdov.flux.ui.auth.LoginActivity;
import com.mkhdov.flux.ui.calendar.DayTimelineView;
import com.mkhdov.flux.ui.calendar.MonthView;
import com.mkhdov.flux.ui.calendar.WeekStripView;
import com.mkhdov.flux.ui.common.UiUtils;
import com.mkhdov.flux.ui.event.EventEditorActivity;
import com.mkhdov.flux.ui.search.SearchActivity;
import com.mkhdov.flux.ui.settings.CalendarManagerActivity;
import com.mkhdov.flux.ui.settings.SyncStatusActivity;
import com.mkhdov.flux.util.DataBus;
import com.mkhdov.flux.util.QuickAddParser;
import com.mkhdov.flux.util.TimeUtils;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Locale;

public class MainActivity extends BaseActivity implements DayTimelineView.Listener, MonthView.Listener {
    public static final String EXTRA_SELECTED_DATE = "selected_date";

    private CalendarRepository repository;
    private AuthManager auth;
    private ViewPager2 dayPager;
    private ViewPager2 monthPager;
    private WeekStripView weekStrip;
    private TextView monthTitle;
    private TextView dayLabel;
    private TextView syncLabel;
    private ImageView syncIcon;
    private View syncDot;
    private ImageButton monthToggle;
    private View loading;
    private DayPagerAdapter dayAdapter;
    private MonthPagerAdapter monthAdapter;
    private final DayTimelineView.ScrollState scrollState = new DayTimelineView.ScrollState();
    private DaySnapshot snapshot;
    private LocalDate selectedDate = LocalDate.now();
    private YearMonth visibleMonth = YearMonth.now();
    private boolean monthMode;
    private boolean suppressPageCallback;
    private int loadRequest;
    private long lastLoadAt;

    private final DataBus.Listener dataListener = this::reload;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        auth = AuthManager.get(this);
        if (!auth.canUseApp()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);
        repository = CalendarRepository.get(this);
        bindViews();
        selectedDate = dateFromIntent(getIntent());
        visibleMonth = YearMonth.from(selectedDate);
        setupPagers();
        setupActions();
        updateHeader();
        scheduleLoad(true);
        initScroll();
        SyncEngine.get(this).status().observe(this, this::renderSyncStatus);
        DataBus.add(dataListener);
    }

    private void bindViews() {
        dayPager = findViewById(R.id.main_day_pager);
        monthPager = findViewById(R.id.main_month_pager);
        weekStrip = findViewById(R.id.main_week_strip);
        monthTitle = findViewById(R.id.main_month_title);
        dayLabel = findViewById(R.id.main_day_label);
        syncLabel = findViewById(R.id.main_sync_label);
        syncIcon = findViewById(R.id.main_sync_icon);
        syncDot = findViewById(R.id.main_sync_dot);
        monthToggle = findViewById(R.id.main_month_toggle);
        loading = findViewById(R.id.main_loading);
        scrollState.hourHeight = UiUtils.dp(this, 64);
    }

    private void setupPagers() {
        dayAdapter = new DayPagerAdapter(scrollState, this);
        dayPager.setAdapter(dayAdapter);
        dayPager.setOffscreenPageLimit(2);
        dayPager.setCurrentItem(DayPagerAdapter.positionFor(selectedDate), false);
        monthAdapter = new MonthPagerAdapter(this);
        monthPager.setAdapter(monthAdapter);
        monthPager.setOffscreenPageLimit(1);
        monthPager.setCurrentItem(MonthPagerAdapter.positionFor(visibleMonth), false);
        weekStrip.setSelectedDate(selectedDate);
        monthAdapter.setSelectedDate(selectedDate);
        dayPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                if (suppressPageCallback) return;
                LocalDate date = DayPagerAdapter.dateFor(position);
                if (!date.equals(selectedDate)) {
                    selectedDate = date;
                    visibleMonth = YearMonth.from(date);
                    weekStrip.setSelectedDate(date);
                    monthAdapter.setSelectedDate(date);
                    updateHeader();
                    scheduleLoad(false);
                }
            }
        });
        monthPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                YearMonth month = MonthPagerAdapter.monthFor(position);
                if (!month.equals(visibleMonth)) {
                    visibleMonth = month;
                    if (monthMode) {
                        monthTitle.setText(TimeUtils.monthTitle(month.atDay(1)));
                        scheduleLoad(false);
                    }
                }
            }
        });
    }

    private void setupActions() {
        monthTitle.setOnClickListener(v -> toggleMonthMode());
        monthToggle.setOnClickListener(v -> toggleMonthMode());
        weekStrip.setOnDateSelectedListener(date -> selectDate(date, true));
        findViewById(R.id.main_add_event).setOnClickListener(v -> showQuickAdd());
        findViewById(R.id.main_search).setOnClickListener(v -> {
            Intent intent = new Intent(this, SearchActivity.class);
            startActivity(intent);
        });
        findViewById(R.id.main_sync_button).setOnClickListener(v -> openSyncStatus());
        syncLabel.setOnClickListener(v -> openSyncStatus());
        findViewById(R.id.main_bottom_today).setOnClickListener(v -> goToToday());
        findViewById(R.id.main_bottom_calendars).setOnClickListener(v ->
                startActivity(new Intent(this, CalendarManagerActivity.class)));
        findViewById(R.id.main_bottom_inbox).setOnClickListener(v -> {
            Intent intent = new Intent(this, SearchActivity.class);
            intent.putExtra(SearchActivity.EXTRA_UPCOMING, true);
            startActivity(intent);
        });
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (monthMode) setMonthMode(false);
                else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    private void initScroll() {
        int minute = 8 * 60;
        if (selectedDate.equals(LocalDate.now())) {
            java.time.LocalTime now = java.time.LocalTime.now();
            minute = Math.max(0, now.getHour() * 60 + now.getMinute() - 120);
        }
        scrollState.scrollY = Math.round(minute * scrollState.hourHeight / 60f);
        final int target = minute;
        dayPager.post(() -> scrollCurrentDayTo(target));
    }

    private void goToToday() {
        LocalDate today = LocalDate.now();
        if (monthMode) setMonthMode(false);
        selectDate(today, true);
        java.time.LocalTime now = java.time.LocalTime.now();
        int minute = Math.max(0, now.getHour() * 60 + now.getMinute() - 120);
        dayPager.post(() -> scrollCurrentDayTo(minute));
    }

    private void scrollCurrentDayTo(int minute) {
        RecyclerView.ViewHolder holder = null;
        View child = dayPager.getChildAt(0);
        if (child instanceof RecyclerView) {
            holder = ((RecyclerView) child).findViewHolderForAdapterPosition(dayPager.getCurrentItem());
        }
        if (holder instanceof DayPagerAdapter.Holder) {
            ((DayPagerAdapter.Holder) holder).timeline.scrollToTime(minute);
        } else {
            scrollState.scrollY = Math.round(minute * scrollState.hourHeight / 60f);
            dayAdapter.notifyItemChanged(dayPager.getCurrentItem());
        }
    }

    private void selectDate(LocalDate date, boolean animate) {
        if (date == null) return;
        selectedDate = date;
        visibleMonth = YearMonth.from(date);
        weekStrip.setSelectedDate(date);
        monthAdapter.setSelectedDate(date);
        if (monthMode) setMonthMode(false);
        int position = DayPagerAdapter.positionFor(date);
        if (dayPager.getCurrentItem() != position) {
            suppressPageCallback = true;
            dayPager.setCurrentItem(position, animate);
            suppressPageCallback = false;
        }
        updateHeader();
        scheduleLoad(false);
    }

    private void toggleMonthMode() { setMonthMode(!monthMode); }

    private void setMonthMode(boolean enabled) {
        if (monthMode == enabled) return;
        monthMode = enabled;
        View show = enabled ? monthPager : dayPager;
        View hide = enabled ? dayPager : monthPager;
        hide.animate().alpha(0f).setDuration(120).withEndAction(() -> hide.setVisibility(View.GONE)).start();
        show.setAlpha(0f);
        show.setVisibility(View.VISIBLE);
        show.animate().alpha(1f).setDuration(160)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();
        if (enabled) {
            visibleMonth = YearMonth.from(selectedDate);
            monthAdapter.setSelectedDate(selectedDate);
            int pos = MonthPagerAdapter.positionFor(visibleMonth);
            if (monthPager.getCurrentItem() != pos) monthPager.setCurrentItem(pos, false);
        } else {
            int pos = DayPagerAdapter.positionFor(selectedDate);
            if (dayPager.getCurrentItem() != pos) dayPager.setCurrentItem(pos, false);
        }
        monthToggle.setImageResource(enabled ? R.drawable.ic_chevron_up : R.drawable.ic_chevron_down);
        monthToggle.setContentDescription(getString(enabled ? R.string.collapse_month : R.string.expand_month));
        updateHeader();
        scheduleLoad(false);
    }

    private void updateHeader() {
        LocalDate titleDate = monthMode ? visibleMonth.atDay(1) : selectedDate;
        monthTitle.setText(TimeUtils.monthTitle(titleDate));
        dayLabel.setText(TimeUtils.selectedDayLabel(selectedDate));
        int count = snapshot == null ? 0 : snapshot.eventsFor(selectedDate).size();
        if (!monthMode && count > 0) {
            dayLabel.setText(TimeUtils.selectedDayLabel(selectedDate) + "  ·  "
                    + getResources().getQuantityString(R.plurals.event_count_today, count, count));
        }
    }

    private void scheduleLoad(boolean immediate) {
        long now = System.currentTimeMillis();
        if (!immediate && now - lastLoadAt < 250) return;
        lastLoadAt = now;
        YearMonth focus = monthMode ? visibleMonth : YearMonth.from(selectedDate);
        LocalDate from = focus.minusMonths(2).atDay(1);
        LocalDate to = focus.plusMonths(2).atEndOfMonth();
        int request = ++loadRequest;
        if (snapshot == null) loading.setVisibility(View.VISIBLE);
        repository.loadSnapshot(from, to, new CalendarRepository.Callback<DaySnapshot>() {
            @Override public void onSuccess(DaySnapshot value) {
                if (request != loadRequest) return;
                snapshot = value;
                dayAdapter.setSnapshot(value);
                monthAdapter.setSnapshot(value);
                loading.setVisibility(View.GONE);
                updateHeader();
            }

            @Override public void onError(String message) {
                if (request != loadRequest) return;
                loading.setVisibility(View.GONE);
                UiUtils.snack(dayPager, message);
            }
        });
    }

    private void reload() {
        if (isFinishing() || isDestroyed()) return;
        scheduleLoad(true);
        if (snapshot != null) weekStrip.invalidate();
    }

    private void renderSyncStatus(SyncStatus status) {
        if (status == null) return;
        syncLabel.setText(syncLabelFor(status));
        boolean attention = status.state == SyncStatus.State.NEEDS_ATTENTION;
        int color = ContextCompat.getColor(this, attention ? R.color.accent : R.color.text_secondary);
        syncLabel.setTextColor(color);
        ImageViewCompat.setImageTintList(syncIcon, ColorStateList.valueOf(color));
        syncDot.setVisibility(status.pending > 0 || status.conflicts > 0 ? View.VISIBLE : View.GONE);
        syncDot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent)));
        syncIcon.setImageResource(status.state == SyncStatus.State.OFFLINE
                || status.state == SyncStatus.State.LOCAL_ONLY ? R.drawable.ic_cloud_off : R.drawable.ic_cloud);
    }

    private String syncLabelFor(SyncStatus status) {
        switch (status.state) {
            case SYNCING: return getString(R.string.syncing);
            case SYNCED: return getString(R.string.synced);
            case OFFLINE: return getString(R.string.offline);
            case NEEDS_ATTENTION: return getString(R.string.needs_attention);
            case SIGNED_OUT: return getString(R.string.sync_sign_in);
            case LOCAL_ONLY:
            default: return getString(R.string.local_only);
        }
    }

    private void openSyncStatus() {
        startActivity(new Intent(this, SyncStatusActivity.class));
    }

    private void showQuickAdd() {
        View content = getLayoutInflater().inflate(R.layout.dialog_quick_add, null, false);
        EditText input = content.findViewById(R.id.quick_add_input);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(content)
                .setPositiveButton(R.string.add, null)
                .setNeutralButton(R.string.more_options, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            UiUtils.showKeyboard(input);
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String raw = input.getText().toString().trim();
                if (raw.isEmpty()) {
                    input.setError(getString(R.string.quick_add_hint));
                    return;
                }
                QuickAddParser.Result parsed = QuickAddParser.parse(raw, selectedDate, ZoneId.systemDefault());
                dialog.dismiss();
                createFromQuickAdd(parsed);
            });
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                String raw = input.getText().toString().trim();
                dialog.dismiss();
                openEditorWithRaw(raw);
            });
        });
        dialog.show();
    }

    private void createFromQuickAdd(QuickAddParser.Result parsed) {
        repository.quickAdd(parsed.title, parsed.startAt, parsed.endAt, new CalendarRepository.Callback<EventEntity>() {
            @Override public void onSuccess(EventEntity value) {
                LocalDate date = TimeUtils.dateOf(value.startAt, TimeUtils.zoneOf(value));
                selectDate(date, false);
                Snackbar bar = Snackbar.make(dayPager, getString(R.string.quick_add_created), Snackbar.LENGTH_LONG)
                        .setAnchorView(findViewById(R.id.main_bottom_today))
                        .setAction(R.string.undo, v -> repository.undoLast(new CalendarRepository.Callback<Void>() {
                            @Override public void onSuccess(Void ignored) { }
                            @Override public void onError(String message) { UiUtils.snack(dayPager, message); }
                        }));
                bar.show();
            }

            @Override public void onError(String message) { UiUtils.snack(dayPager, message); }
        });
    }

    private void openEditorWithRaw(String raw) {
        Intent intent = new Intent(this, EventEditorActivity.class);
        long start = selectedDate.atTime(java.time.LocalTime.of(9, 0)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (!raw.isEmpty()) {
            QuickAddParser.Result parsed = QuickAddParser.parse(raw, selectedDate, ZoneId.systemDefault());
            start = parsed.startAt;
            intent.putExtra(EventEditorActivity.EXTRA_END_AT, parsed.endAt);
            intent.putExtra(EventEditorActivity.EXTRA_TITLE, parsed.title);
        } else {
            intent.putExtra(EventEditorActivity.EXTRA_END_AT, start + 3600000L);
        }
        intent.putExtra(EventEditorActivity.EXTRA_START_AT, start);
        startActivity(intent);
    }

    @Override public void onEventClick(EventInstance event) {
        Intent intent = new Intent(this, EventEditorActivity.class);
        intent.putExtra(EventEditorActivity.EXTRA_EVENT_ID, event.source.id);
        intent.putExtra(EventEditorActivity.EXTRA_OCCURRENCE_START, event.occurrenceStart);
        intent.putExtra(EventEditorActivity.EXTRA_START_AT, event.startAt);
        intent.putExtra(EventEditorActivity.EXTRA_END_AT, event.endAt);
        startActivity(intent);
    }

    @Override public void onEventMoved(EventInstance event, long newStart, long newEnd, boolean resized) {
        repository.moveOccurrence(event, newStart, newEnd, new CalendarRepository.Callback<EventEntity>() {
            @Override public void onSuccess(EventEntity value) {
                LocalDate movedTo = TimeUtils.dateOf(newStart, ZoneId.systemDefault());
                if (movedTo != null && !movedTo.equals(selectedDate)) selectDate(movedTo, false);
                Snackbar bar = Snackbar.make(dayPager, resized ? getString(R.string.event_updated)
                                : getString(R.string.event_moved, TimeUtils.shortDate(movedTo)),
                        Snackbar.LENGTH_LONG)
                        .setAnchorView(findViewById(R.id.main_bottom_today))
                        .setAction(R.string.undo, v -> repository.undoLast(new CalendarRepository.Callback<Void>() {
                            @Override public void onSuccess(Void ignored) { }
                            @Override public void onError(String message) { UiUtils.snack(dayPager, message); }
                        }));
                bar.show();
            }

            @Override public void onError(String message) { UiUtils.snack(dayPager, message); }
        });
    }

    @Override public void onEmptyLongPress(long startEpochMillis) {
        Intent intent = new Intent(this, EventEditorActivity.class);
        intent.putExtra(EventEditorActivity.EXTRA_START_AT, startEpochMillis);
        intent.putExtra(EventEditorActivity.EXTRA_END_AT, startEpochMillis + 3600000L);
        startActivity(intent);
    }

    @Override public void onDateSelected(LocalDate date) {
        if (date != null && !date.equals(selectedDate)) selectDate(date, false);
    }

    @Override public void onEventMoved(EventInstance event, LocalDate targetDate) {
        ZoneId zone = ZoneId.systemDefault();
        java.time.LocalDateTime original = java.time.Instant.ofEpochMilli(event.startAt).atZone(zone).toLocalDateTime();
        long newStart;
        long newEnd;
        if (event.isAllDay()) {
            LocalDate originalStartDate = original.toLocalDate();
            LocalDate originalEndDate = TimeUtils.dateOf(Math.max(event.startAt, event.endAt - 1), zone);
            long days = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(originalStartDate, originalEndDate) + 1);
            newStart = targetDate.atStartOfDay(zone).toInstant().toEpochMilli();
            newEnd = targetDate.plusDays(days).atStartOfDay(zone).toInstant().toEpochMilli();
        } else {
            newStart = targetDate.atTime(original.toLocalTime()).atZone(zone).toInstant().toEpochMilli();
            newEnd = newStart + Math.max(60000L, event.endAt - event.startAt);
        }
        repository.moveOccurrence(event, newStart, newEnd, new CalendarRepository.Callback<EventEntity>() {
            @Override public void onSuccess(EventEntity value) {
                Snackbar.make(dayPager, getString(R.string.event_moved, TimeUtils.shortDate(targetDate)), Snackbar.LENGTH_LONG)
                        .setAnchorView(findViewById(R.id.main_bottom_today))
                        .setAction(R.string.undo, v -> repository.undoLast(new CalendarRepository.Callback<Void>() {
                            @Override public void onSuccess(Void ignored) { }
                            @Override public void onError(String message) { UiUtils.snack(dayPager, message); }
                        })).show();
            }

            @Override public void onError(String message) { UiUtils.snack(dayPager, message); }
        });
    }

    @Override protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        LocalDate date = dateFromIntent(intent);
        if (!date.equals(selectedDate)) selectDate(date, false);
    }

    private LocalDate dateFromIntent(Intent intent) {
        if (intent == null) return LocalDate.now();
        long epoch = intent.getLongExtra(EXTRA_SELECTED_DATE, 0);
        if (epoch <= 0) return LocalDate.now();
        return TimeUtils.dateOf(epoch, ZoneId.systemDefault());
    }

    @Override protected void onResume() {
        super.onResume();
        if (!auth.canUseApp()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        if (snapshot != null) scheduleLoad(true);
    }

    @Override protected void onDestroy() {
        DataBus.remove(dataListener);
        super.onDestroy();
    }
}
