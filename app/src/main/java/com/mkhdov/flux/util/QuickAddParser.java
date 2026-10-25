package com.mkhdov.flux.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class QuickAddParser {
    private static final Pattern TIME = Pattern.compile("(?i)\\b(?:at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b|\\b(?:at\\s+)?([01]?\\d|2[0-3]):([0-5]\\d)\\b");
    private static final Pattern RANGE = Pattern.compile("(?i)\\b(?:at\\s+)?(?:(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)|([01]?\\d|2[0-3]):([0-5]\\d))\\s*[-–]\\s*(?:(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)|([01]?\\d|2[0-3]):([0-5]\\d))\\b");
    private static final Pattern WEEKDAY_ABBR = Pattern.compile("(?i)\\b(mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)\\b");
    private static final Pattern DURATION = Pattern.compile("(?i)\\bfor\\s+(\\d+)\\s*(minutes?|mins?|hours?|hrs?)\\b");
    private static final Pattern COUNT = Pattern.compile("(?i)\\b(\\d+)\\s*(minutes?|mins?|hours?|hrs?)\\b");

    private QuickAddParser() { }

    public static Result parse(String input, LocalDate reference, ZoneId zone) {
        Result result = new Result();
        String working = input == null ? "" : input.trim();
        if (working.isEmpty()) return result;
        working = normalizeWeekdays(working);

        LocalDate date = reference;
        String lower = working.toLowerCase(Locale.getDefault());
        boolean dateMatched = false;

        if (lower.contains("day after tomorrow")) { date = reference.plusDays(2); working = removeWord(working, "day after tomorrow"); dateMatched = true; }
        else if (lower.contains("tomorrow")) { date = reference.plusDays(1); working = removeWord(working, "tomorrow"); dateMatched = true; }
        else if (lower.contains("today")) { date = reference; working = removeWord(working, "today"); dateMatched = true; }
        else {
            String[] days = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"};
            for (int i = 0; i < days.length; i++) {
                if (lower.matches(".*\\b(next\\s+)?" + days[i] + "\\b.*")) {
                    DayOfWeek target = DayOfWeek.of(i + 1);
                    int diff = (target.getValue() - reference.getDayOfWeek().getValue() + 7) % 7;
                    if (diff == 0 || lower.contains("next " + days[i])) diff += 7;
                    date = reference.plusDays(diff);
                    working = removeWord(working, "next " + days[i]);
                    working = removeWord(working, days[i]);
                    dateMatched = true;
                    break;
                }
            }
        }

        Matcher isoDate = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b").matcher(working);
        if (isoDate.find()) {
            try { date = LocalDate.of(Integer.parseInt(isoDate.group(1)), Integer.parseInt(isoDate.group(2)), Integer.parseInt(isoDate.group(3))); working = working.replace(isoDate.group(), ""); dateMatched = true; }
            catch (Exception ignored) { }
        }

        LocalTime time = null;
        boolean matchedTime = false;
        int rangeMinutes = 0;
        Matcher range = RANGE.matcher(working);
        if (range.find()) {
            matchedTime = true;
            time = parseTime(range.group(1), range.group(2), range.group(3), range.group(4), range.group(5));
            LocalTime endTime = parseTime(range.group(6), range.group(7), range.group(8), range.group(9), range.group(10));
            if (time != null && endTime != null) {
                rangeMinutes = (endTime.toSecondOfDay() - time.toSecondOfDay()) / 60;
                if (rangeMinutes <= 0) rangeMinutes += 24 * 60;
            }
            working = working.replace(range.group(), " ");
        } else {
            Matcher tm = TIME.matcher(working);
            if (tm.find()) {
                matchedTime = true;
                time = parseTime(tm.group(1), tm.group(2), tm.group(3), tm.group(4), tm.group(5));
                working = working.replace(tm.group(), " ");
            }
        }

        int durationMinutes = rangeMinutes > 0 ? rangeMinutes : 60;
        Matcher dm = DURATION.matcher(working);
        if (dm.find()) {
            int value = Integer.parseInt(dm.group(1));
            durationMinutes = dm.group(2).toLowerCase(Locale.US).startsWith("h") ? value * 60 : value;
            working = working.replace(dm.group(), " ");
        } else if (rangeMinutes == 0) {
            Matcher cm = COUNT.matcher(working);
            if (cm.find() && time != null) {
                int value = Integer.parseInt(cm.group(1));
                durationMinutes = cm.group(2).toLowerCase(Locale.US).startsWith("h") ? value * 60 : value;
                working = working.replace(cm.group(), " ");
            }
        }

        if (time == null) {
            LocalDateTime now = LocalDateTime.now(zone);
            time = now.toLocalTime().withSecond(0).withNano(0).plusMinutes(30);
            int rounded = ((time.getMinute() + 14) / 15) * 15;
            time = time.withMinute(0).plusMinutes(rounded);
            if (!dateMatched && time.isBefore(now.toLocalTime())) date = date.plusDays(1);
        }

        LocalDateTime start = LocalDateTime.of(date, time);
        result.title = cleanup(working);
        result.startAt = start.atZone(zone).toInstant().toEpochMilli();
        result.endAt = result.startAt + durationMinutes * 60000L;
        result.matchedTime = matchedTime;
        return result;
    }

    private static LocalTime parseTime(String hour12, String minute12, String ampm, String hour24, String minute24) {
        try {
            if (hour12 != null) {
                int hour = Integer.parseInt(hour12) % 12;
                int minute = minute12 == null ? 0 : Integer.parseInt(minute12);
                if (ampm != null && ampm.equalsIgnoreCase("pm")) hour += 12;
                return LocalTime.of(hour, Math.min(59, minute));
            }
            if (hour24 != null) {
                return LocalTime.of(Integer.parseInt(hour24), Integer.parseInt(minute24));
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static String normalizeWeekdays(String text) {
        Matcher matcher = WEEKDAY_ABBR.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String replacement;
            switch (matcher.group(1).toLowerCase(Locale.US)) {
                case "mon": replacement = "monday"; break;
                case "tue":
                case "tues": replacement = "tuesday"; break;
                case "wed": replacement = "wednesday"; break;
                case "thu":
                case "thur":
                case "thurs": replacement = "thursday"; break;
                case "fri": replacement = "friday"; break;
                case "sat": replacement = "saturday"; break;
                case "sun": replacement = "sunday"; break;
                default: replacement = matcher.group(1); break;
            }
            matcher.appendReplacement(out, replacement);
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String removeWord(String text, String word) {
        return text.replaceAll("(?i)\\b" + Pattern.quote(word) + "\\b", " ");
    }

    private static String cleanup(String text) {
        String value = text.replaceAll("\\s+", " ").trim();
        value = value.replaceAll("(?i)^on\\s+", "").replaceAll("(?i)\\s+on\\s*$", "");
        value = value.replaceAll("(?i)^for\\s+", "").trim();
        value = value.replaceAll("[,;]+\\s*$", "").trim();
        return value.isEmpty() ? "New event" : value;
    }

    public static class Result {
        public String title = "New event";
        public long startAt;
        public long endAt;
        public boolean matchedTime;
    }
}
