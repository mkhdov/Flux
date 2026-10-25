package com.mkhdov.flux.util;

import com.mkhdov.flux.data.local.entity.EventEntity;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

public final class TimeUtils {
    public static final DateTimeFormatter TIME_12 = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault());
    public static final DateTimeFormatter TIME_24 = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault());
    public static final DateTimeFormatter DAY_HEADER = DateTimeFormatter.ofPattern("EEEE  d MMMM yyyy", Locale.getDefault());
    public static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault());
    public static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM", Locale.getDefault());
    public static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault());
    public static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault());

    private TimeUtils() { }

    public static ZoneId zoneOf(EventEntity event) {
        try {
            return ZoneId.of(event.timezone == null || event.timezone.isEmpty() ? ZoneId.systemDefault().getId() : event.timezone);
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    public static LocalDate dateOf(long millis, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
    }

    public static LocalDate dateOf(long millis) {
        return dateOf(millis, ZoneId.systemDefault());
    }

    public static long startOfDay(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    public static long startOfDay(LocalDate date) {
        return startOfDay(date, ZoneId.systemDefault());
    }

    public static long endExclusiveOfDay(LocalDate date, ZoneId zone) {
        return date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    public static LocalDateTime localDateTime(long millis, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime();
    }

    public static long toMillis(LocalDate date, LocalTime time, ZoneId zone) {
        return ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli();
    }

    public static long snap(long millis, int minutes, ZoneId zone) {
        ZonedDateTime zoned = Instant.ofEpochMilli(millis).atZone(zone);
        int minuteOfDay = zoned.getHour() * 60 + zoned.getMinute();
        int snapped = Math.round(minuteOfDay / (float) minutes) * minutes;
        LocalDate date = zoned.toLocalDate();
        int dayMinutes = 24 * 60;
        while (snapped < 0) { snapped += dayMinutes; date = date.minusDays(1); }
        while (snapped >= dayMinutes) { snapped -= dayMinutes; date = date.plusDays(1); }
        return date.atTime(snapped / 60, snapped % 60).atZone(zone).toInstant().toEpochMilli();
    }

    public static int minutesOfDay(long millis, ZoneId zone) {
        LocalTime time = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime();
        return time.getHour() * 60 + time.getMinute();
    }

    public static String formatTime(long millis, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(zone).format(is24Hour() ? TIME_24 : TIME_12);
    }

    public static String formatTimeRange(long start, long end, ZoneId zone) {
        String a = formatTime(start, zone);
        String b = formatTime(end, zone);
        return a + " – " + b;
    }

    public static String formatTimeRange(EventEntity event) {
        ZoneId zone = zoneOf(event);
        if (event.allDay) {
            LocalDate start = dateOf(event.startAt, zone);
            LocalDate endInclusive = dateOf(Math.max(event.startAt, event.endAt - 1), zone);
            if (start.equals(endInclusive)) return "All day";
            return "All day · " + SHORT_DATE.format(start) + " – " + SHORT_DATE.format(endInclusive);
        }
        return formatTimeRange(event.startAt, event.endAt, zone);
    }

    public static boolean is24Hour() {
        return android.text.format.DateFormat.is24HourFormat(
                com.mkhdov.flux.FluxApp.get().getApplicationContext());
    }

    public static DayOfWeek firstDayOfWeek() {
        return DayOfWeek.of(java.time.temporal.WeekFields.of(Locale.getDefault()).getFirstDayOfWeek().getValue());
    }

    public static LocalDate weekStart(LocalDate date) {
        DayOfWeek first = firstDayOfWeek();
        int diff = (date.getDayOfWeek().getValue() - first.getValue() + 7) % 7;
        return date.minusDays(diff);
    }

    public static String weekdayInitial(DayOfWeek day) {
        return day.getDisplayName(TextStyle.NARROW, Locale.getDefault());
    }

    public static String selectedDayLabel(LocalDate date) {
        return DAY_HEADER.format(date);
    }

    public static String relativeDay(LocalDate date) {
        LocalDate today = LocalDate.now();
        if (date.equals(today)) return "Today";
        if (date.equals(today.plusDays(1))) return "Tomorrow";
        if (date.equals(today.minusDays(1))) return "Yesterday";
        return FULL_DATE.format(date);
    }

    public static String reminderLabel(int minutes) {
        if (minutes <= 0) return "At start";
        if (minutes % 60 == 0) return (minutes / 60) + " hour" + (minutes / 60 == 1 ? "" : "s") + " before";
        return minutes + " minutes before";
    }

    public static String iso(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).toString();
    }

    public static long parseIso(String value, long fallback) {
        if (value == null) return fallback;
        try { return Instant.parse(value).toEpochMilli(); } catch (Exception ignored) { }
        try { return java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli(); } catch (Exception ignored) { }
        try { return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); } catch (Exception ignored) { }
        return fallback;
    }

    public static String monthTitle(LocalDate date) {
        return MONTH_YEAR.format(date);
    }

    public static String shortDate(LocalDate date) {
        return SHORT_DATE.format(date);
    }

    public static String durationLabel(long minutes) {
        if (minutes < 60) return minutes + " min";
        long hours = minutes / 60;
        long rest = minutes % 60;
        return hours + " h" + (rest > 0 ? " " + rest + " min" : "");
    }

    public static LocalDate nextOccurrenceDate(LocalDate from, int dow) {
        int current = from.getDayOfWeek().getValue();
        int diff = (dow - current + 7) % 7;
        return from.plusDays(diff == 0 ? 7 : diff);
    }

    public static long plusMinutes(long millis, int minutes) {
        return millis + minutes * 60000L;
    }

    public static int daysBetweenInclusive(LocalDate start, LocalDate end) {
        return (int) ChronoUnit.DAYS.between(start, end) + 1;
    }
}
