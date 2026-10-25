package com.mkhdov.flux.ui.search;

import com.mkhdov.flux.ui.common.BaseActivity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mkhdov.flux.R;
import com.mkhdov.flux.data.local.entity.CalendarEntity;
import com.mkhdov.flux.data.local.entity.EventEntity;
import com.mkhdov.flux.data.repo.CalendarRepository;
import com.mkhdov.flux.ui.common.PickerUtils;
import com.mkhdov.flux.ui.common.UiUtils;
import com.mkhdov.flux.ui.event.EventEditorActivity;
import com.mkhdov.flux.util.DataBus;
import com.mkhdov.flux.util.EventPalette;
import com.mkhdov.flux.util.TimeUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

public class SearchActivity extends BaseActivity {
    public static final String EXTRA_UPCOMING = "upcoming";

    private EditText input;
    private ImageButton clear;
    private RecyclerView results;
    private View empty;
    private TextView emptyTitle;
    private TextView emptyHint;
    private ProgressBar progress;
    private LinearLayout filterChips;
    private View filterScroll;
    private SearchAdapter adapter;
    private CalendarRepository repository;
    private final List<CalendarEntity> calendars = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;
    private int searchGeneration;
    private boolean upcoming;

    private String filterCalendarId;
    private String filterColor;
    private int filterStatus;
    private LocalDate fromDate = LocalDate.now().minusYears(5);
    private LocalDate toDate = LocalDate.now().plusYears(5);
    private boolean includePast = true;

    private final DataBus.Listener dataListener = () -> {
        if (isFinishing() || isDestroyed()) return;
        loadCalendars(true);
        scheduleSearch(0);
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        repository = CalendarRepository.get(this);
        upcoming = getIntent().getBooleanExtra(EXTRA_UPCOMING, false);
        bind();
        loadCalendars(false);
        if (upcoming) {
            includePast = false;
            fromDate = LocalDate.now();
            toDate = LocalDate.now().plusMonths(3);
            input.setHint(R.string.upcoming_events);
            scheduleSearch(0);
        } else {
            scheduleSearch(0);
        }
        DataBus.add(dataListener);
    }

    private void bind() {
        input = findViewById(R.id.search_input);
        clear = findViewById(R.id.search_clear);
        results = findViewById(R.id.search_results);
        empty = findViewById(R.id.search_empty);
        emptyTitle = findViewById(R.id.search_empty_title);
        emptyHint = findViewById(R.id.search_empty_hint);
        progress = findViewById(R.id.search_progress);
        filterChips = findViewById(R.id.search_filters_container);
        filterScroll = findViewById(R.id.search_filters_scroll);
        adapter = new SearchAdapter(calendars, event -> {
            Intent intent = new Intent(this, EventEditorActivity.class);
            intent.putExtra(EventEditorActivity.EXTRA_EVENT_ID, event.id);
            intent.putExtra(EventEditorActivity.EXTRA_START_AT, event.startAt);
            intent.putExtra(EventEditorActivity.EXTRA_END_AT, event.endAt);
            intent.putExtra(EventEditorActivity.EXTRA_OCCURRENCE_START, event.isRecurring() ? event.startAt : 0L);
            startActivity(intent);
        });
        results.setLayoutManager(new LinearLayoutManager(this));
        results.setAdapter(adapter);
        findViewById(R.id.search_back).setOnClickListener(v -> finish());
        clear.setOnClickListener(v -> input.setText(""));
        findViewById(R.id.search_filter).setOnClickListener(v -> showFilters());
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                clear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                scheduleSearch(260);
            }
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                scheduleSearch(0);
                return true;
            }
            return false;
        });
        results.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard();
            }
        });
    }

    private void scheduleSearch(long delay) {
        if (pendingSearch != null) handler.removeCallbacks(pendingSearch);
        pendingSearch = () -> runSearch();
        handler.postDelayed(pendingSearch, delay);
    }

    private void runSearch() {
        String query = input.getText().toString().trim();
        boolean hasFilters = filterCalendarId != null || filterColor != null || filterStatus != 0
                || !fromDate.equals(LocalDate.now().minusYears(5)) || !toDate.equals(LocalDate.now().plusYears(5))
                || !includePast;
        if (query.isEmpty() && !hasFilters && !upcoming) {
            adapter.setItems(new ArrayList<>());
            showEmpty(R.string.search_events, R.string.search_prompt);
            progress.setVisibility(View.GONE);
            return;
        }
        progress.setVisibility(View.VISIBLE);
        int generation = ++searchGeneration;
        LocalDate from = fromDate;
        if (!includePast) {
            LocalDate today = LocalDate.now();
            if (from.isBefore(today)) from = today;
        }
        ZoneId zone = ZoneId.systemDefault();
        long start = from.atStartOfDay(zone).toInstant().toEpochMilli();
        long end = toDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1;
        if (end < start) end = start;
        repository.search(query, filterCalendarId, filterColor, start, end,
                new CalendarRepository.Callback<List<EventEntity>>() {
                    @Override public void onSuccess(List<EventEntity> value) {
                        if (generation != searchGeneration) return;
                        progress.setVisibility(View.GONE);
                        List<EventEntity> filtered = new ArrayList<>();
                        for (EventEntity event : value) {
                            if (filterStatus == 1 && event.completed) continue;
                            if (filterStatus == 2 && !event.completed) continue;
                            filtered.add(event);
                        }
                        adapter.setItems(filtered);
                        if (filtered.isEmpty()) {
                            showEmpty(R.string.search_empty_title, R.string.search_empty_hint);
                        } else {
                            empty.setVisibility(View.GONE);
                            results.setVisibility(View.VISIBLE);
                        }
                    }

                    @Override public void onError(String message) {
                        if (generation != searchGeneration) return;
                        progress.setVisibility(View.GONE);
                        UiUtils.snack(results, message);
                    }
                });
    }

    private void showEmpty(int title, int hint) {
        empty.setVisibility(View.VISIBLE);
        results.setVisibility(View.GONE);
        emptyTitle.setText(title);
        emptyHint.setText(hint);
    }

    private void showFilters() {
        View content = getLayoutInflater().inflate(R.layout.dialog_filters, null, false);
        Spinner calendarSpinner = content.findViewById(R.id.filter_calendar);
        Spinner colorSpinner = content.findViewById(R.id.filter_color);
        Spinner statusSpinner = content.findViewById(R.id.filter_status);
        TextView from = content.findViewById(R.id.filter_from_date);
        TextView to = content.findViewById(R.id.filter_to_date);
        CheckBox past = content.findViewById(R.id.filter_include_past);

        List<String> calendarNames = new ArrayList<>();
        calendarNames.add(getString(R.string.all_calendars));
        for (CalendarEntity calendar : calendars) calendarNames.add(calendar.name);
        calendarSpinner.setAdapter(spinnerAdapter(calendarNames));
        int calendarIndex = 0;
        if (filterCalendarId != null) {
            for (int i = 0; i < calendars.size(); i++) {
                if (calendars.get(i).id.equals(filterCalendarId)) { calendarIndex = i + 1; break; }
            }
        }
        calendarSpinner.setSelection(calendarIndex);

        List<String> colorNames = new ArrayList<>();
        colorNames.add(getString(R.string.all_colors));
        for (EventPalette.NamedColor color : EventPalette.COLORS) colorNames.add(color.name);
        colorSpinner.setAdapter(spinnerAdapter(colorNames));
        int colorIndex = 0;
        if (filterColor != null) {
            for (int i = 0; i < EventPalette.COLORS.size(); i++) {
                if (EventPalette.COLORS.get(i).hex.equalsIgnoreCase(filterColor)) { colorIndex = i + 1; break; }
            }
        }
        colorSpinner.setSelection(colorIndex);

        statusSpinner.setAdapter(spinnerAdapter(java.util.Arrays.asList(
                getString(R.string.filter_any_status), getString(R.string.filter_open), getString(R.string.filter_completed))));
        statusSpinner.setSelection(filterStatus);

        from.setText(TimeUtils.shortDate(fromDate));
        to.setText(TimeUtils.shortDate(toDate));
        past.setChecked(includePast);
        from.setOnClickListener(v -> PickerUtils.pickDate(this, fromDate, date -> {
            fromDate = date;
            if (toDate.isBefore(fromDate)) toDate = fromDate;
            from.setText(TimeUtils.shortDate(fromDate));
            to.setText(TimeUtils.shortDate(toDate));
        }));
        to.setOnClickListener(v -> PickerUtils.pickDate(this, toDate, date -> {
            toDate = date;
            if (toDate.isBefore(fromDate)) fromDate = toDate;
            from.setText(TimeUtils.shortDate(fromDate));
            to.setText(TimeUtils.shortDate(toDate));
        }));

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(content)
                .setPositiveButton(R.string.apply, null)
                .setNeutralButton(R.string.clear_filters, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                int selectedCalendar = calendarSpinner.getSelectedItemPosition();
                filterCalendarId = selectedCalendar <= 0 ? null : calendars.get(selectedCalendar - 1).id;
                int selectedColor = colorSpinner.getSelectedItemPosition();
                filterColor = selectedColor <= 0 ? null : EventPalette.COLORS.get(selectedColor - 1).hex;
                filterStatus = statusSpinner.getSelectedItemPosition();
                includePast = past.isChecked();
                updateChips();
                dialog.dismiss();
                scheduleSearch(0);
            });
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                filterCalendarId = null;
                filterColor = null;
                filterStatus = 0;
                fromDate = LocalDate.now().minusYears(5);
                toDate = LocalDate.now().plusYears(5);
                includePast = true;
                updateChips();
                dialog.dismiss();
                scheduleSearch(0);
            });
        });
        dialog.show();
    }

    private ArrayAdapter<String> spinnerAdapter(List<String> items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private void loadCalendars(boolean refreshSearch) {
        repository.loadCalendars(new CalendarRepository.Callback<List<CalendarEntity>>() {
            @Override public void onSuccess(List<CalendarEntity> value) {
                calendars.clear();
                calendars.addAll(value);
                adapter.notifyDataSetChanged();
                if (refreshSearch) scheduleSearch(0);
            }

            @Override public void onError(String message) { }
        });
    }

    private void updateChips() {
        filterChips.removeAllViews();
        boolean any = false;
        if (filterCalendarId != null) {
            for (CalendarEntity c : calendars) if (c.id.equals(filterCalendarId)) {
                addChip(c.name); any = true; break;
            }
        }
        if (filterColor != null) { addChip(EventPalette.nameFor(filterColor)); any = true; }
        if (filterStatus != 0) { addChip(getString(filterStatus == 1 ? R.string.filter_open : R.string.filter_completed)); any = true; }
        if (!includePast) { addChip(getString(R.string.upcoming)); any = true; }
        if (!fromDate.equals(LocalDate.now().minusYears(5)) || !toDate.equals(LocalDate.now().plusYears(5))) {
            addChip(TimeUtils.shortDate(fromDate) + " – " + TimeUtils.shortDate(toDate));
            any = true;
        }
        filterScroll.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    private void addChip(String label) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(11.5f);
        chip.setTextColor(getColor(R.color.accent));
        chip.setBackgroundResource(R.drawable.bg_chip_accent);
        int h = UiUtils.dp(this, 25);
        int p = UiUtils.dp(this, 9);
        chip.setPadding(p, 0, p, 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, h);
        params.rightMargin = UiUtils.dp(this, 6);
        chip.setLayoutParams(params);
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setOnClickListener(v -> showFilters());
        filterChips.addView(chip);
    }

    private void hideKeyboard() {
        UiUtils.hideKeyboard(input);
    }

    @Override protected void onDestroy() {
        DataBus.remove(dataListener);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

}
