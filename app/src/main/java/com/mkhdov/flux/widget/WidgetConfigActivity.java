package com.mkhdov.flux.widget;

import com.mkhdov.flux.ui.common.BaseActivity;
import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.mkhdov.flux.R;
import com.mkhdov.flux.data.local.entity.CalendarEntity;
import com.mkhdov.flux.data.repo.CalendarRepository;
import com.mkhdov.flux.util.EventPalette;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WidgetConfigActivity extends Activity {
    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private RadioGroup backgroundGroup;
    private SeekBar daysSeek;
    private SeekBar maxSeek;
    private TextView daysLabel;
    private TextView maxLabel;
    private LinearLayout calendarsContainer;
    private SwitchMaterial showDetails;
    private SwitchMaterial dimPast;
    private SwitchMaterial showPlus;
    private Spinner dateAction;
    private final List<CheckBox> calendarBoxes = new ArrayList<>();
    private CheckBox allCalendars;
    private Set<String> selectedCalendarIds = new HashSet<>();
    private String rawCalendars = "*";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        Bundle extras = getIntent().getExtras();
        if (extras != null) appWidgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return; }
        setContentView(R.layout.widget_config);
        backgroundGroup = findViewById(R.id.config_bg_group);
        daysSeek = findViewById(R.id.config_days);
        maxSeek = findViewById(R.id.config_max_events);
        daysLabel = findViewById(R.id.config_days_label);
        maxLabel = findViewById(R.id.config_max_label);
        calendarsContainer = findViewById(R.id.config_calendars);
        showDetails = findViewById(R.id.config_show_details);
        dimPast = findViewById(R.id.config_dim_past);
        showPlus = findViewById(R.id.config_show_plus);
        dateAction = findViewById(R.id.config_date_action);

        ArrayAdapter<String> actions = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{getString(R.string.widget_date_day), getString(R.string.widget_date_new)});
        actions.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        dateAction.setAdapter(actions);

        WidgetPrefs prefs = WidgetPrefs.get(this, appWidgetId);
        String style = prefs.style();
        if (WidgetPrefs.STYLE_WHITE.equals(style)) backgroundGroup.check(R.id.config_bg_white);
        else if (WidgetPrefs.STYLE_DARK.equals(style)) backgroundGroup.check(R.id.config_bg_dark);
        else backgroundGroup.check(R.id.config_bg_transparent);
        daysSeek.setProgress(Math.max(0, Math.min(13, prefs.days() - 1)));
        maxSeek.setProgress(Math.max(0, Math.min(45, prefs.maxEvents() - 5)));
        showDetails.setChecked(prefs.showDetails());
        dimPast.setChecked(prefs.dimPast());
        showPlus.setChecked(prefs.showPlus());
        dateAction.setSelection("new".equals(prefs.dateAction()) ? 1 : 0);
        rawCalendars = prefs.rawCalendars();
        updateLabels();

        daysSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { updateLabels(); }
        });
        maxSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { updateLabels(); }
        });

        loadCalendars();
        findViewById(R.id.config_apply).setOnClickListener(v -> save());
        findViewById(R.id.config_cancel).setOnClickListener(v -> finish());
    }

    private void updateLabels() {
        daysLabel.setText((daysSeek.getProgress() + 1) + " days");
        maxLabel.setText((maxSeek.getProgress() + 5) + " events");
    }

    private void loadCalendars() {
        CalendarRepository.get(this).loadCalendars(new CalendarRepository.Callback<List<CalendarEntity>>() {
            @Override public void onSuccess(List<CalendarEntity> calendars) {
                calendarsContainer.removeAllViews();
                calendarBoxes.clear();
                allCalendars = new CheckBox(WidgetConfigActivity.this);
                allCalendars.setText(R.string.all_calendars);
                allCalendars.setTextColor(getColor(R.color.text_primary));
                boolean all = rawCalendars == null || rawCalendars.isEmpty() || rawCalendars.equals("*");
                allCalendars.setChecked(all);
                calendarsContainer.addView(allCalendars);
                Set<String> selected = WidgetPrefs.get(WidgetConfigActivity.this, appWidgetId).calendars();
                for (CalendarEntity calendar : calendars) {
                    CheckBox box = new CheckBox(WidgetConfigActivity.this);
                    box.setText(calendar.name);
                    box.setTextColor(getColor(R.color.text_primary));
                    box.setTag(calendar.id);
                    box.setChecked(all || selected.contains(calendar.id));
                    box.setEnabled(!all);
                    int color = EventPalette.parse(calendar.color, Color.GRAY);
                    box.setButtonTintList(android.content.res.ColorStateList.valueOf(color));
                    calendarsContainer.addView(box);
                    calendarBoxes.add(box);
                }
                allCalendars.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    for (CheckBox box : calendarBoxes) {
                        box.setEnabled(!isChecked);
                        if (isChecked) box.setChecked(true);
                    }
                });
            }
            @Override public void onError(String message) {
                Toast.makeText(WidgetConfigActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void save() {
        String style = WidgetPrefs.STYLE_TRANSPARENT;
        int checked = backgroundGroup.getCheckedRadioButtonId();
        if (checked == R.id.config_bg_white) style = WidgetPrefs.STYLE_WHITE;
        else if (checked == R.id.config_bg_dark) style = WidgetPrefs.STYLE_DARK;
        Set<String> calendars = new HashSet<>();
        boolean all = allCalendars != null && allCalendars.isChecked();
        if (!all) {
            for (CheckBox box : calendarBoxes) {
                if (box.isChecked()) calendars.add((String) box.getTag());
            }
        }
        WidgetPrefs prefs = WidgetPrefs.get(this, appWidgetId);
        String raw = all ? "*" : (calendars.isEmpty() ? "#none" : String.join(",", calendars));
        prefs.save(style, daysSeek.getProgress() + 1, maxSeek.getProgress() + 5,
                showDetails.isChecked(), dimPast.isChecked(), showPlus.isChecked(),
                dateAction.getSelectedItemPosition() == 1 ? "new" : "day", calendars);
        if (all) prefs.saveRawCalendars("*");
        else prefs.saveRawCalendars(raw);
        AgendaWidgetProvider.updateWidget(this, appWidgetId);
        Toast.makeText(this, R.string.configuration_saved, Toast.LENGTH_SHORT).show();
        Intent result = new Intent();
        result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        setResult(RESULT_OK, result);
        finish();
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) { }
        @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    }
}
