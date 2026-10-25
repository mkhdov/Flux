package com.mkhdov.flux.ui.event;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mkhdov.flux.R;
import com.mkhdov.flux.ui.common.PickerUtils;
import com.mkhdov.flux.util.RRuleUtils;
import com.mkhdov.flux.util.TimeUtils;

import org.dmfs.rfc5545.DateTime;
import org.dmfs.rfc5545.Weekday;
import org.dmfs.rfc5545.recur.Freq;
import org.dmfs.rfc5545.recur.RecurrenceRule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class RecurrenceDialog {
    public interface Callback { void onRule(String rrule); }

    private static final String[] FREQ_LABELS = {"Does not repeat", "Daily", "Weekly", "Monthly", "Yearly"};
    private static final String[] FREQ_VALUES = {null, "DAILY", "WEEKLY", "MONTHLY", "YEARLY"};
    private static final Weekday[] WEEKDAYS = {Weekday.MO, Weekday.TU, Weekday.WE, Weekday.TH, Weekday.FR, Weekday.SA, Weekday.SU};

    private RecurrenceDialog() { }

    public static void show(Activity activity, String currentRule, LocalDate startDate, ZoneId zone, Callback callback) {
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_recurrence, null, false);
        Spinner frequency = content.findViewById(R.id.recurrence_frequency);
        EditText interval = content.findViewById(R.id.recurrence_interval);
        TextView unit = content.findViewById(R.id.recurrence_unit);
        LinearLayout weekdaysRow = content.findViewById(R.id.recurrence_weekdays_row);
        LinearLayout weekdays = content.findViewById(R.id.recurrence_weekdays);
        View intervalRow = content.findViewById(R.id.recurrence_interval_row);
        View untilRow = content.findViewById(R.id.recurrence_until_row);
        TextView untilValue = content.findViewById(R.id.recurrence_until);
        View untilClear = content.findViewById(R.id.recurrence_until_clear);
        EditText count = content.findViewById(R.id.recurrence_count);
        View afterLabel = content.findViewById(R.id.recurrence_after_label);
        View afterCount = content.findViewById(R.id.recurrence_count);
        View afterUnit = content.findViewById(R.id.recurrence_after_unit);
        TextView error = content.findViewById(R.id.recurrence_error);

        ArrayAdapter<String> freqAdapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item,
                Arrays.asList(FREQ_LABELS));
        freqAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        frequency.setAdapter(freqAdapter);

        List<CheckBox> boxes = new ArrayList<>();
        int accent = ContextCompat.getColor(activity, R.color.accent);
        for (int i = 0; i < 7; i++) {
            CheckBox box = new CheckBox(activity);
            box.setText(WEEKDAYS[i].name().substring(0, 1));
            box.setTextSize(12f);
            box.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
            box.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
            box.setGravity(android.view.Gravity.CENTER);
            box.setPadding(0, 0, 0, 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            weekdays.addView(box, params);
            boxes.add(box);
        }

        final int[] selectedFrequency = {0};
        final LocalDate[] untilDate = {null};

        String normalized = RRuleUtils.normalize(currentRule);
        LocalDate initialUntil = null;
        Set<Weekday> initialDays = new HashSet<>();
        int initialInterval = 1;
        Integer initialCount = null;
        boolean weekly = false;
        if (normalized != null) {
            try {
                RecurrenceRule rule = new RecurrenceRule(normalized);
                for (int i = 0; i < FREQ_VALUES.length; i++) {
                    if (rule.getFreq().name().equals(FREQ_VALUES[i])) { selectedFrequency[0] = i; break; }
                }
                initialInterval = Math.max(1, rule.getInterval());
                initialCount = rule.getCount();
                if (rule.getByDayPart() != null) {
                    for (RecurrenceRule.WeekdayNum wd : rule.getByDayPart()) initialDays.add(wd.weekday);
                }
                if (rule.getUntil() != null) {
                    DateTime until = rule.getUntil();
                    initialUntil = Instant.ofEpochMilli(until.getTimestamp()).atZone(zone).toLocalDate();
                }
                weekly = rule.getFreq() == Freq.WEEKLY;
            } catch (Exception ignored) { }
        }
        frequency.setSelection(selectedFrequency[0]);
        interval.setText(String.valueOf(initialInterval));
        untilDate[0] = initialUntil;
        count.setText(initialCount == null ? "" : String.valueOf(initialCount));
        if (weekly && initialDays.isEmpty()) initialDays.add(WEEKDAYS[Math.max(0, startDate.getDayOfWeek().getValue() - 1)]);
        for (int i = 0; i < 7; i++) boxes.get(i).setChecked(initialDays.contains(WEEKDAYS[i]));

        Runnable render = () -> {
            int index = frequency.getSelectedItemPosition();
            int freq = index < 0 ? 0 : index;
            selectedFrequency[0] = freq;
            boolean repeats = freq > 0;
            intervalRow.setVisibility(repeats ? View.VISIBLE : View.GONE);
            weekdaysRow.setVisibility(freq == 2 ? View.VISIBLE : View.GONE);
            untilRow.setVisibility(repeats ? View.VISIBLE : View.GONE);
            afterLabel.setVisibility(repeats ? View.VISIBLE : View.GONE);
            afterCount.setVisibility(repeats ? View.VISIBLE : View.GONE);
            afterUnit.setVisibility(repeats ? View.VISIBLE : View.GONE);
            if (repeats) {
                switch (freq) {
                    case 1: unit.setText(R.string.recurrence_days); break;
                    case 2: unit.setText(R.string.recurrence_weeks); break;
                    case 3: unit.setText(R.string.recurrence_months); break;
                    default: unit.setText(R.string.recurrence_years); break;
                }
                if (freq == 2 && !anyChecked(boxes) && selectedFrequency[0] == 2) {
                    int indexDay = Math.max(0, startDate.getDayOfWeek().getValue() - 1);
                    boxes.get(indexDay).setChecked(true);
                }
            }
            untilValue.setText(untilDate[0] == null ? activity.getString(R.string.recurrence_never)
                    : TimeUtils.SHORT_DATE.format(untilDate[0]));
            untilClear.setVisibility(untilDate[0] == null ? View.GONE : View.VISIBLE);
        };
        frequency.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { render.run(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        untilRow.setOnClickListener(v -> PickerUtils.pickDate(activity, untilDate[0] == null ? startDate : untilDate[0], date -> {
            untilDate[0] = date;
            count.setText("");
            render.run();
        }));
        untilClear.setOnClickListener(v -> { untilDate[0] = null; render.run(); });
        count.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                if (s.length() > 0 && untilDate[0] != null) {
                    untilDate[0] = null;
                    render.run();
                }
            }
        });
        render.run();

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(content)
                .setPositiveButton(R.string.save, null)
                .setNeutralButton(R.string.recurrence_clear, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                int freqIndex = frequency.getSelectedItemPosition();
                if (freqIndex <= 0) {
                    callback.onRule(null);
                    dialog.dismiss();
                    return;
                }
                int every;
                try { every = Integer.parseInt(interval.getText().toString().trim()); }
                catch (Exception e) { every = 0; }
                if (every < 1) {
                    error.setText(R.string.recurrence_interval_invalid);
                    error.setVisibility(View.VISIBLE);
                    return;
                }
                String byDay = null;
                if (freqIndex == 2) {
                    StringBuilder days = new StringBuilder();
                    for (int i = 0; i < 7; i++) {
                        if (boxes.get(i).isChecked()) {
                            if (days.length() > 0) days.append(',');
                            days.append(WEEKDAYS[i].name());
                        }
                    }
                    if (days.length() > 0) byDay = days.toString();
                }
                Integer times = null;
                try {
                    String raw = count.getText().toString().trim();
                    if (!raw.isEmpty()) times = Integer.parseInt(raw);
                } catch (Exception ignored) { }
                if (times != null && times <= 0) times = null;
                String rule;
                if (untilDate[0] != null) {
                    long untilMillis = untilDate[0].plusDays(1).atStartOfDay(zone).minusSeconds(1).toInstant().toEpochMilli();
                    rule = build(FREQ_VALUES[freqIndex], every, byDay, RRuleUtils.untilFormatted(untilMillis), null);
                } else {
                    rule = build(FREQ_VALUES[freqIndex], every, byDay, null, times);
                }
                if (!RRuleUtils.isValid(rule)) {
                    error.setText(R.string.recurrence_invalid);
                    error.setVisibility(View.VISIBLE);
                    return;
                }
                callback.onRule(rule);
                dialog.dismiss();
            });
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                callback.onRule(null);
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private static boolean anyChecked(List<CheckBox> boxes) {
        for (CheckBox box : boxes) if (box.isChecked()) return true;
        return false;
    }

    private static String build(String freq, int interval, String byDay, String until, Integer count) {
        StringBuilder rule = new StringBuilder("FREQ=").append(freq);
        if (interval > 1) rule.append(";INTERVAL=").append(interval);
        if (byDay != null && !byDay.isEmpty()) rule.append(";BYDAY=").append(byDay);
        if (until != null) rule.append(";UNTIL=").append(until);
        else if (count != null && count > 0) rule.append(";COUNT=").append(count);
        return rule.toString();
    }
}
