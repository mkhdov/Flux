package com.mkhdov.flux.ui.settings;

import com.mkhdov.flux.ui.common.BaseActivity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.mkhdov.flux.R;
import com.mkhdov.flux.data.local.entity.CalendarEntity;
import com.mkhdov.flux.data.repo.CalendarRepository;
import com.mkhdov.flux.ui.common.ColorPaletteView;
import com.mkhdov.flux.ui.common.DialogFactory;
import com.mkhdov.flux.ui.common.UiUtils;
import com.mkhdov.flux.util.DataBus;
import com.mkhdov.flux.util.EventPalette;
import com.mkhdov.flux.util.TimeUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class CalendarManagerActivity extends BaseActivity implements CalendarAdapter.Listener {
    private CalendarRepository repository;
    private CalendarAdapter adapter;
    private RecyclerView list;
    private View empty;
    private final List<CalendarEntity> calendars = new ArrayList<>();
    private ActivityResultLauncher<String[]> importLauncher;
    private ActivityResultLauncher<String> exportLauncher;

    private final DataBus.Listener dataListener = () -> load(false);

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calendar_manager);
        repository = CalendarRepository.get(this);
        list = findViewById(R.id.calendars_list);
        empty = findViewById(R.id.calendars_empty);
        adapter = new CalendarAdapter(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        findViewById(R.id.calendars_back).setOnClickListener(v -> finish());
        findViewById(R.id.calendars_add).setOnClickListener(v -> showCalendarDialog(null));
        findViewById(R.id.calendars_more).setOnClickListener(this::showMainMenu);
        importLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::handleImport);
        exportLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/calendar"), this::handleExport);
        DataBus.add(dataListener);
        load(true);
    }

    private void load(boolean showLoading) {
        repository.loadCalendars(new CalendarRepository.Callback<List<CalendarEntity>>() {
            @Override public void onSuccess(List<CalendarEntity> value) {
                calendars.clear();
                calendars.addAll(value);
                Map<String, Integer> counts = new HashMap<>();
                repository.executor().execute(() -> {
                    for (CalendarEntity c : value) {
                        int count;
                        try { count = repository.db().eventDao().countForCalendar(c.id); }
                        catch (Exception e) { count = 0; }
                        counts.put(c.id, count);
                    }
                    repository.mainHandler().post(() -> {
                        adapter.setItems(value, counts);
                        empty.setVisibility(value.isEmpty() ? View.VISIBLE : View.GONE);
                        list.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
                    });
                });
            }

            @Override public void onError(String message) { UiUtils.snack(list, message); }
        });
    }

    private void showMainMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.import_ics);
        menu.getMenu().add(0, 2, 1, R.string.export_ics);
        menu.getMenu().add(0, 3, 2, R.string.notification_settings);
        menu.getMenu().add(0, 4, 3, R.string.sync_status);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                importLauncher.launch(new String[]{"text/calendar", "application/octet-stream", "*/*"});
                return true;
            }
            if (item.getItemId() == 2) {
                exportLauncher.launch("flux-" + LocalDate.now() + ".ics");
                return true;
            }
            if (item.getItemId() == 3) {
                startActivity(new Intent(this, NotificationSettingsActivity.class));
                return true;
            }
            if (item.getItemId() == 4) {
                startActivity(new Intent(this, SyncStatusActivity.class));
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void handleImport(Uri uri) {
        if (uri == null) return;
        try {
            InputStream input = getContentResolver().openInputStream(uri);
            if (input == null) { UiUtils.snack(list, getString(R.string.error_generic)); return; }
            CalendarEntity target = defaultCalendar();
            repository.importIcs(input, target == null ? null : target.id, new CalendarRepository.Callback<Integer>() {
                @Override public void onSuccess(Integer value) {
                    try { input.close(); } catch (Exception ignored) { }
                    UiUtils.snack(list, getString(R.string.import_ics) + ": " + value);
                    load(false);
                }

                @Override public void onError(String message) {
                    try { input.close(); } catch (Exception ignored) { }
                    UiUtils.snack(list, message);
                }
            });
        } catch (Exception e) {
            UiUtils.snack(list, e.getMessage() == null ? getString(R.string.error_generic) : e.getMessage());
        }
    }

    private void handleExport(Uri uri) {
        if (uri == null) return;
        try {
            OutputStream output = getContentResolver().openOutputStream(uri);
            if (output == null) { UiUtils.snack(list, getString(R.string.error_generic)); return; }
            repository.exportIcs(output, new CalendarRepository.Callback<Integer>() {
                @Override public void onSuccess(Integer value) {
                    try { output.close(); } catch (Exception ignored) { }
                    UiUtils.snack(list, getString(R.string.export_ics) + ": " + value);
                }

                @Override public void onError(String message) {
                    try { output.close(); } catch (Exception ignored) { }
                    UiUtils.snack(list, message);
                }
            });
        } catch (Exception e) {
            UiUtils.snack(list, e.getMessage() == null ? getString(R.string.error_generic) : e.getMessage());
        }
    }

    private CalendarEntity defaultCalendar() {
        for (CalendarEntity c : calendars) if (c.isDefault) return c;
        return calendars.isEmpty() ? null : calendars.get(0);
    }

    private void showCalendarDialog(CalendarEntity existing) {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_calendar_edit, null, false);
        EditText name = content.findViewById(R.id.calendar_name_input);
        ColorPaletteView palette = content.findViewById(R.id.calendar_color_palette);
        TextView timezone = content.findViewById(R.id.calendar_timezone_value);
        TextView reminder = content.findViewById(R.id.calendar_reminder_value);
        SwitchMaterial setDefault = content.findViewById(R.id.calendar_set_default);
        SwitchMaterial visible = content.findViewById(R.id.calendar_visible);
        TextView dialogTitle = content.findViewById(R.id.calendar_dialog_title);
        TextView error = content.findViewById(R.id.calendar_dialog_error);
        String[] zone = {existing != null ? existing.timezone : ZoneId.systemDefault().getId()};
        int[] reminderMinutes = {existing != null ? existing.defaultReminderMinutes : 0};
        String[] color = {existing != null ? existing.color : EventPalette.COLORS.get(0).hex};
        if (existing != null) {
            dialogTitle.setText(R.string.calendar_edit);
            name.setText(existing.name);
            timezone.setText(zone[0]);
            reminder.setText(DialogFactory.reminderLabel(reminderMinutes[0]));
            setDefault.setChecked(existing.isDefault);
            visible.setChecked(existing.visible);
        } else {
            timezone.setText(zone[0]);
            reminder.setText(DialogFactory.reminderLabel(0));
            visible.setChecked(true);
        }
        palette.setSelectedColor(color[0]);
        palette.setOnColorSelectedListener(hex -> color[0] = hex);
        content.findViewById(R.id.calendar_timezone_row).setOnClickListener(v ->
                DialogFactory.pickTimezone(this, zone[0], value -> {
                    zone[0] = value;
                    timezone.setText(value);
                }));
        content.findViewById(R.id.calendar_reminder_row).setOnClickListener(v ->
                DialogFactory.pickReminder(this, reminderMinutes[0], value -> {
                    reminderMinutes[0] = value;
                    reminder.setText(DialogFactory.reminderLabel(value));
                }));
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(content)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String calendarName = name.getText().toString().trim();
                    if (calendarName.isEmpty()) {
                        error.setText(R.string.calendar_name_required);
                        error.setVisibility(View.VISIBLE);
                        return;
                    }
                    CalendarEntity calendar = existing == null ? new CalendarEntity() : copy(existing);
                    if (existing == null) calendar.id = UUID.randomUUID().toString();
                    calendar.name = calendarName;
                    calendar.color = color[0];
                    calendar.timezone = zone[0];
                    calendar.defaultReminderMinutes = reminderMinutes[0];
                    calendar.isDefault = setDefault.isChecked();
                    calendar.visible = visible.isChecked();
                    if (existing != null && existing.isDefault && !calendar.isDefault) {
                        calendar.isDefault = true;
                    }
                    dialog.dismiss();
                    repository.saveCalendar(calendar, new CalendarRepository.Callback<CalendarEntity>() {
                        @Override public void onSuccess(CalendarEntity value) {
                            UiUtils.snack(list, getString(R.string.calendar_saved));
                            load(false);
                        }
                        @Override public void onError(String message) { UiUtils.snack(list, message); }
                    });
                }));
        dialog.show();
    }

    private CalendarEntity copy(CalendarEntity source) {
        CalendarEntity c = new CalendarEntity();
        c.id = source.id;
        c.ownerId = source.ownerId;
        c.name = source.name;
        c.color = source.color;
        c.visible = source.visible;
        c.archived = source.archived;
        c.isDefault = source.isDefault;
        c.timezone = source.timezone;
        c.defaultReminderMinutes = source.defaultReminderMinutes;
        c.createdAt = source.createdAt;
        c.updatedAt = source.updatedAt;
        c.deletedAt = source.deletedAt;
        c.revision = source.revision;
        return c;
    }

    @Override public void onCalendarClick(CalendarEntity calendar) { showCalendarDialog(calendar); }

    @Override public void onToggleVisible(CalendarEntity calendar) {
        repository.setCalendarVisible(calendar.id, !calendar.visible);
        calendar.visible = !calendar.visible;
        adapter.notifyDataSetChanged();
    }

    @Override public void onMakeDefault(CalendarEntity calendar) {
        repository.setDefaultCalendar(calendar.id);
        for (CalendarEntity c : calendars) c.isDefault = c.id.equals(calendar.id);
        adapter.notifyDataSetChanged();
    }

    @Override public void onMore(CalendarEntity calendar, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.edit);
        menu.getMenu().add(0, 2, 1, calendar.archived ? R.string.calendar_unarchive : R.string.calendar_archive);
        menu.getMenu().add(0, 3, 2, R.string.calendar_delete);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) { showCalendarDialog(calendar); return true; }
            if (item.getItemId() == 2) {
                repository.setCalendarArchived(calendar.id, !calendar.archived);
                calendar.archived = !calendar.archived;
                adapter.notifyDataSetChanged();
                return true;
            }
            if (item.getItemId() == 3) { confirmDelete(calendar); return true; }
            return false;
        });
        menu.show();
    }

    private void confirmDelete(CalendarEntity calendar) {
        List<CalendarEntity> others = new ArrayList<>();
        for (CalendarEntity c : calendars) if (!c.id.equals(calendar.id)) others.add(c);
        List<String> options = new ArrayList<>();
        options.add(getString(R.string.calendar_delete_all));
        for (CalendarEntity c : others) options.add(getString(R.string.calendar_delete_move) + " · " + c.name);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.calendar_delete)
                .setMessage(R.string.calendar_delete_confirm)
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    if (which == 0) {
                        repository.deleteCalendar(calendar.id, true, null, deleteCallback());
                    } else {
                        CalendarEntity target = others.get(which - 1);
                        repository.deleteCalendar(calendar.id, false, target.id, deleteCallback());
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private CalendarRepository.Callback<Void> deleteCallback() {
        return new CalendarRepository.Callback<Void>() {
            @Override public void onSuccess(Void value) {
                UiUtils.snack(list, getString(R.string.calendar_deleted));
                load(false);
            }
            @Override public void onError(String message) { UiUtils.snack(list, message); }
        };
    }

    @Override protected void onDestroy() {
        DataBus.remove(dataListener);
        super.onDestroy();
    }
}
