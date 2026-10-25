package com.mkhdov.flux.ui.common;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mkhdov.flux.R;
import com.mkhdov.flux.util.TimeUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class DialogFactory {
    public interface TimezoneCallback { void onTimezone(String zoneId); }
    public interface ReminderCallback { void onMinutes(int minutes); }

    private DialogFactory() { }

    public static void pickTimezone(Activity activity, String current, TimezoneCallback callback) {
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_timezone, null, false);
        EditText search = content.findViewById(R.id.timezone_search);
        ListView list = content.findViewById(R.id.timezone_list);
        TextView empty = content.findViewById(R.id.timezone_empty);
        List<String> all = new ArrayList<>(ZoneId.getAvailableZoneIds());
        Collections.sort(all, String.CASE_INSENSITIVE_ORDER);
        final List<String> shown = new ArrayList<>(all);
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity, R.layout.item_timezone,
                R.id.timezone_name, shown) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                String id = getItem(position);
                String label = id.replace('_', ' ');
                if (id.equals(current)) label = label + "   ✓";
                view.setText(label);
                return view;
            }
        };
        list.setAdapter(adapter);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            callback.onTimezone(shown.get(position));
            dialog.dismiss();
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                String query = s.toString().trim().toLowerCase(Locale.ROOT).replace(' ', '_');
                shown.clear();
                for (String id : all) {
                    if (query.isEmpty() || id.toLowerCase(Locale.ROOT).contains(query)) shown.add(id);
                }
                adapter.notifyDataSetChanged();
                empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
                list.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
            }
        });
        dialog.show();
    }

    public static void pickReminder(Context context, int current, ReminderCallback callback) {
        int[] values = {0, 5, 10, 15, 20, 30, 45, 60, 120, 1440};
        String[] labels = new String[values.length + 1];
        for (int i = 0; i < values.length; i++) labels[i] = reminderLabel(values[i]);
        labels[values.length] = context.getString(R.string.custom_minutes);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.add_reminder_hint)
                .setItems(labels, (dialog, which) -> {
                    if (which == values.length) customReminder(context, current, callback);
                    else callback.onMinutes(values[which]);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void customReminder(Context context, int current, ReminderCallback callback) {
        EditText input = new EditText(context);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.minutes_before);
        if (current > 0) input.setText(String.valueOf(current));
        int pad = UiUtils.dp(context, 22);
        input.setPadding(pad, UiUtils.dp(context, 8), pad, UiUtils.dp(context, 8));
        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.custom_minutes)
                .setView(input)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String raw = input.getText().toString().trim();
            int minutes;
            try { minutes = Integer.parseInt(raw); } catch (Exception e) { minutes = -1; }
            if (minutes < 0 || minutes > 10080) {
                input.setError(context.getString(R.string.error_generic));
                return;
            }
            callback.onMinutes(minutes);
            dialog.dismiss();
        }));
        dialog.show();
    }

    public static String reminderLabel(int minutes) {
        if (minutes <= 0) return "At time of event";
        if (minutes == 1440) return "1 day before";
        return TimeUtils.reminderLabel(minutes);
    }

    public static void pickEventDeleteScope(Activity activity, boolean recurring, Runnable thisOnly,
                                            Runnable future, Runnable all) {
        if (!recurring) {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.delete_event)
                    .setMessage(R.string.delete_confirm)
                    .setPositiveButton(R.string.delete, (d, w) -> all.run())
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        String[] options = {activity.getString(R.string.delete_occurrence), activity.getString(R.string.delete_future),
                activity.getString(R.string.delete_series)};
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.delete_scope_title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) thisOnly.run();
                    else if (which == 1) future.run();
                    else all.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public static void info(Activity activity, String title, String message) {
        new MaterialAlertDialogBuilder(activity).setTitle(title).setMessage(message)
                .setPositiveButton(R.string.done, null).show();
    }

    public static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    public static String timezoneOffsetLabel(String zoneId) {
        try {
            ZoneId zone = ZoneId.of(zoneId);
            ZoneOffset offset = zone.getRules().getOffset(Instant.now());
            return "GMT" + offset.getId().replace(":", "");
        } catch (Exception e) { return "GMT"; }
    }
}
