package com.mkhdov.flux.util;

import org.dmfs.rfc5545.recur.InvalidRecurrenceRuleException;
import org.dmfs.rfc5545.recur.RecurrenceRule;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class RRuleUtils {
    private static final DateTimeFormatter ICS_UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US);

    private RRuleUtils() { }

    public static String stripPrefix(String rrule) {
        if (rrule == null) return null;
        String value = rrule.trim();
        if (value.regionMatches(true, 0, "RRULE:", 0, 6)) value = value.substring(6);
        return value;
    }

    public static String normalize(String rrule) {
        String value = stripPrefix(rrule);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    public static boolean isValid(String rrule) {
        String value = normalize(rrule);
        if (value == null) return false;
        try { new RecurrenceRule(value); return true; }
        catch (InvalidRecurrenceRuleException e) { return false; }
    }

    public static String untilFormatted(long epochMillis) {
        return ICS_UTC.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC));
    }

    public static String limitUntil(String rrule, long untilInclusiveMillis) {
        String value = normalize(rrule);
        if (value == null) return null;
        String until = untilFormatted(untilInclusiveMillis);
        StringBuilder out = new StringBuilder();
        for (String segment : value.split(";")) {
            String key = segment.split("=", 2)[0].toUpperCase(Locale.US);
            if (key.equals("UNTIL") || key.equals("COUNT")) continue;
            if (out.length() > 0) out.append(';');
            out.append(segment);
        }
        out.append(";UNTIL=").append(until);
        return out.toString();
    }

    public static String withUntilCount(String rrule, int count) {
        String value = normalize(rrule);
        if (value == null) return null;
        StringBuilder out = new StringBuilder();
        for (String segment : value.split(";")) {
            String key = segment.split("=", 2)[0].toUpperCase(Locale.US);
            if (key.equals("UNTIL") || key.equals("COUNT")) continue;
            if (out.length() > 0) out.append(';');
            out.append(segment);
        }
        out.append(";COUNT=").append(Math.max(1, count));
        return out.toString();
    }

    public static String build(String freq, int interval, String byDay, String untilIsoDate, Integer count) {
        StringBuilder rule = new StringBuilder("FREQ=").append(freq.toUpperCase(Locale.US));
        if (interval > 1) rule.append(";INTERVAL=").append(interval);
        if (byDay != null && !byDay.isEmpty()) rule.append(";BYDAY=").append(byDay);
        if (untilIsoDate != null && !untilIsoDate.isEmpty()) {
            rule.append(";UNTIL=").append(untilIsoDate.replace("-", "")).append("T235959Z");
        } else if (count != null && count > 0) {
            rule.append(";COUNT=").append(count);
        }
        return rule.toString();
    }

    public static String describe(String rrule) {
        String value = normalize(rrule);
        if (value == null) return "Does not repeat";
        try {
            RecurrenceRule rule = new RecurrenceRule(value);
            String freq = rule.getFreq().name().toLowerCase(Locale.US);
            int interval = rule.getInterval();
            String base = interval > 1 ? "Every " + interval + " " + freq.replace("ly", "") + "s" : "Every " + freq.replace("day", "day").replace("week", "week").replace("month", "month").replace("year", "year");
            if (interval <= 1) {
                switch (rule.getFreq()) {
                    case DAILY: base = "Daily"; break;
                    case WEEKLY: base = "Weekly"; break;
                    case MONTHLY: base = "Monthly"; break;
                    case YEARLY: base = "Yearly"; break;
                    default: base = "Repeats"; break;
                }
            }
            if (rule.getByDayPart() != null && !rule.getByDayPart().isEmpty()) {
                base += " · " + rule.getByDayPart().toString();
            }
            if (rule.getUntil() != null) base += " · until " + rule.getUntil();
            if (rule.getCount() != null) base += " · " + rule.getCount() + " times";
            return base;
        } catch (Exception e) {
            return "Custom repeat";
        }
    }
}
