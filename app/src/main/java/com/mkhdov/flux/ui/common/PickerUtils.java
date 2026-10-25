package com.mkhdov.flux.ui.common;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;

import java.time.LocalDate;
import java.time.LocalTime;

public final class PickerUtils {
    public interface OnDatePicked { void onPicked(LocalDate date); }
    public interface OnTimePicked { void onPicked(LocalTime time); }

    private PickerUtils() { }

    public static void pickDate(Context context, LocalDate initial, OnDatePicked callback) {
        LocalDate start = initial == null ? LocalDate.now() : initial;
        DatePickerDialog dialog = new DatePickerDialog(context, (view, year, month, day) ->
                callback.onPicked(LocalDate.of(year, month + 1, day)),
                start.getYear(), start.getMonthValue() - 1, start.getDayOfMonth());
        dialog.show();
    }

    public static void pickTime(Context context, LocalTime initial, OnTimePicked callback) {
        LocalTime start = initial == null ? LocalTime.now() : initial;
        TimePickerDialog dialog = new TimePickerDialog(context, (view, hour, minute) ->
                callback.onPicked(LocalTime.of(hour, minute)), start.getHour(), start.getMinute(),
                android.text.format.DateFormat.is24HourFormat(context));
        dialog.show();
    }
}
